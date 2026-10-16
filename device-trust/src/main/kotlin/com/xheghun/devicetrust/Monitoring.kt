package com.xheghun.devicetrust

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public data class SignalChange(val previous: TrustSignal, val current: TrustSignal)

public data class AssessmentChanges(
    val addedSignals: List<TrustSignal>,
    val removedSignals: List<TrustSignal>,
    val changedSignals: List<SignalChange>,
    val unconfirmedSignals: List<TrustSignal>,
    val scoreDelta: Int,
    val previousLevel: TrustLevel,
    val currentLevel: TrustLevel,
) {
    public val hasChanges: Boolean get() = addedSignals.isNotEmpty() || removedSignals.isNotEmpty() ||
        changedSignals.isNotEmpty() || scoreDelta != 0 || previousLevel != currentLevel
}

public data class AssessmentUpdate(
    val sequence: Long,
    val current: TrustAssessment,
    val baseline: TrustAssessment,
    val changes: AssessmentChanges?,
    val changesSinceBaseline: AssessmentChanges?,
)

public sealed interface MonitoringEvent {
    public data class Assessed(val update: AssessmentUpdate) : MonitoringEvent
    public data class Failed(val sequence: Long, val lastSuccessful: AssessmentUpdate?) : MonitoringEvent
}

/** One in-memory session. Only one observer may poll; refreshes are serialized with polling. */
public class DeviceTrustMonitor(
    private val client: DeviceTrustClient,
    private val interval: Duration = 30.seconds,
) {
    init {
        require(interval.isFinite() && interval >= 1.seconds) { "interval must be finite and at least one second" }
    }

    private val mutex = Mutex()
    private val observing = AtomicBoolean(false)
    private val events = MutableStateFlow<MonitoringEvent?>(null)
    private var lastSuccessful: AssessmentUpdate? = null
    private var knownSignals = emptyMap<String, TrustSignal>()
    private var sequence = 0L

    /** Polls immediately, then waits [interval] after each scan. Cancellation stops polling. */
    public fun observe(): Flow<MonitoringEvent> = flow {
        check(observing.compareAndSet(false, true)) { "This monitor already has an observer" }
        try {
            coroutineScope {
                val polling = launch {
                    while (true) {
                        try {
                            refresh()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // refresh publishes failure without discarding the last successful assessment.
                        }
                        delay(interval)
                    }
                }
                try {
                    emitAll(events.filterNotNull())
                } finally {
                    polling.cancel()
                }
            }
        } finally {
            observing.set(false)
        }
    }

    /** Performs a fresh scan, publishes its result, and propagates collection failures to the caller. */
    public suspend fun refresh(): AssessmentUpdate = mutex.withLock {
        val current = try {
            client.assess().snapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            events.value = MonitoringEvent.Failed(++sequence, lastSuccessful)
            throw failure
        }
        currentCoroutineContext().ensureActive()
        val previous = lastSuccessful
        val baseline = previous?.baseline ?: current
        val update = AssessmentUpdate(
            sequence = ++sequence,
            current = current,
            baseline = baseline,
            changes = previous?.let { compare(it.current, current, knownSignals) },
            changesSinceBaseline = previous?.let { compare(baseline, current, baseline.signals.associateBy { it.id }) },
        )
        val nextKnown = knownSignals.toMutableMap()
        current.evidence.evaluatedSignalIds.forEach(nextKnown::remove)
        current.signals.forEach { nextKnown[it.id] = it }
        knownSignals = nextKnown.toMap()
        lastSuccessful = update
        events.value = MonitoringEvent.Assessed(update)
        update
    }
}

private fun TrustAssessment.snapshot(): TrustAssessment = copy(
    evidence = evidence.copy(signals = signals.toList(), evaluatedSignalIds = evidence.evaluatedSignalIds.toSet()),
)

private fun compare(
    previous: TrustAssessment,
    current: TrustAssessment,
    known: Map<String, TrustSignal>,
): AssessmentChanges {
    val now = current.signals.associateBy { it.id }
    val absent = known.values.filter { it.id !in now }.sortedBy { it.id }
    return AssessmentChanges(
        addedSignals = now.values.filter { it.id !in known }.sortedBy { it.id },
        removedSignals = absent.filter { it.id in current.evidence.evaluatedSignalIds },
        changedSignals = now.values.mapNotNull { signal ->
            known[signal.id]?.takeIf {
                it.category != signal.category || it.weight != signal.weight || it.detail != signal.detail
            }?.let { SignalChange(it, signal) }
        }.sortedBy { it.current.id },
        unconfirmedSignals = absent.filter { it.id !in current.evidence.evaluatedSignalIds },
        scoreDelta = current.score - previous.score,
        previousLevel = previous.level,
        currentLevel = current.level,
    )
}

public fun DeviceTrustClient.monitor(interval: Duration = 30.seconds): DeviceTrustMonitor =
    DeviceTrustMonitor(this, interval)
