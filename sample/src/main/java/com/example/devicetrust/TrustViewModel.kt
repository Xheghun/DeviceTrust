package com.example.devicetrust

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xheghun.devicetrust.DeviceTrust
import com.xheghun.devicetrust.DeviceTrustClient
import com.xheghun.devicetrust.MonitoringEvent
import com.xheghun.devicetrust.TrustAssessment
import com.xheghun.devicetrust.monitor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimelineEntryUi(
    val sequence: Long,
    val time: String,
    val baseline: Boolean,
    val added: String,
    val removed: String,
    val changed: String,
    val unconfirmed: String,
    val scoreDelta: Int,
    val previousLevel: String,
    val currentLevel: String,
)

data class TrustUiState(
    val scanning: Boolean = true,
    val assessment: TrustAssessment? = null,
    val error: Int? = null,
    val lastUpdated: String = "",
    val timeline: List<TimelineEntryUi> = emptyList(),
)

class TrustViewModel(client: DeviceTrustClient = DeviceTrust.create()) : ViewModel() {
    private val monitor = client.monitor()
    private val current = MutableStateFlow(TrustUiState())
    private var refreshJob: Job? = null
    private var lastSequence = 0L
    private val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

    val state = flow {
        coroutineScope {
            val observer = launch { monitor.observe().collect(::onEvent) }
            try { emitAll(current) } finally { observer.cancel() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), TrustUiState())

    fun scan() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            current.update { it.copy(scanning = true) }
            try {
                monitor.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                current.update { it.copy(error = R.string.monitor_scan_failed) }
            } finally {
                current.update { it.copy(scanning = false) }
            }
        }
    }

    private fun onEvent(event: MonitoringEvent) {
        when (event) {
            is MonitoringEvent.Failed -> current.update {
                it.copy(scanning = false, error = R.string.monitor_scan_failed)
            }
            is MonitoringEvent.Assessed -> {
                val update = event.update
                if (update.sequence <= lastSequence) return
                lastSequence = update.sequence
                val changes = update.changes
                val entry = TimelineEntryUi(
                    sequence = update.sequence,
                    time = formatter.format(Instant.ofEpochMilli(update.current.evidence.collectedAtEpochMillis)),
                    baseline = changes == null,
                    added = changes?.addedSignals.orEmpty().joinToString { it.title },
                    removed = changes?.removedSignals.orEmpty().joinToString { it.title },
                    changed = changes?.changedSignals.orEmpty().joinToString { it.current.title },
                    unconfirmed = changes?.unconfirmedSignals.orEmpty().joinToString { it.title },
                    scoreDelta = changes?.scoreDelta ?: 0,
                    previousLevel = changes?.previousLevel?.name?.replace('_', ' ').orEmpty(),
                    currentLevel = update.current.level.name.replace('_', ' '),
                )
                current.update {
                    it.copy(
                        scanning = false,
                        assessment = update.current,
                        error = null,
                        lastUpdated = entry.time,
                        timeline = (listOf(entry) + it.timeline).take(50),
                    )
                }
            }
        }
    }
}
