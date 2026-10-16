package com.xheghun.devicetrust

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceTrustMonitorTest {
    @Test fun `first scan establishes baseline and unchanged scans still update freshness`() = runTest {
        val client = FakeClient()
        val monitor = client.monitor()
        val first = monitor.refresh()
        val second = monitor.refresh()
        assertNull(first.changes)
        assertEquals(first.current, second.baseline)
        assertFalse(second.changes!!.hasChanges)
        assertEquals(2L, second.sequence)
        assertNotEquals(first.current.evidence.collectedAtEpochMillis, second.current.evidence.collectedAtEpochMillis)
    }

    @Test fun `diff includes additions removals meaningful edits and baseline comparison`() = runTest {
        val client = FakeClient(listOf(signal("a"), signal("b")))
        val monitor = client.monitor()
        monitor.refresh()
        client.signals = listOf(signal("b", detail = "changed"), signal("c"))
        client.evaluated = setOf("a", "b", "c")
        val update = monitor.refresh()
        assertEquals(listOf("c"), update.changes!!.addedSignals.map { it.id })
        assertEquals(listOf("a"), update.changes.removedSignals.map { it.id })
        assertEquals("changed", update.changes.changedSignals.single().current.detail)
        client.signals = emptyList()
        val third = monitor.refresh()
        assertEquals(setOf("a", "b"), third.changesSinceBaseline!!.removedSignals.map { it.id }.toSet())
        assertEquals(setOf("b", "c"), third.changes!!.removedSignals.map { it.id }.toSet())
    }

    @Test fun `unavailable signals remain unconfirmed across scans until evaluated`() = runTest {
        val client = FakeClient(listOf(signal("a")))
        val monitor = client.monitor()
        monitor.refresh()
        client.signals = emptyList()
        client.evaluated = emptySet()
        repeat(2) {
            val changes = monitor.refresh().changes!!
            assertTrue(changes.removedSignals.isEmpty())
            assertEquals("a", changes.unconfirmedSignals.single().id)
        }
        client.evaluated = setOf("a")
        assertEquals("a", monitor.refresh().changes!!.removedSignals.single().id)
        assertTrue(monitor.refresh().changes!!.removedSignals.isEmpty())
    }

    @Test fun `unknown signal reappearing is not falsely added`() = runTest {
        val client = FakeClient(listOf(signal("a")))
        val monitor = client.monitor()
        monitor.refresh()
        client.signals = emptyList()
        client.evaluated = emptySet()
        monitor.refresh()
        client.signals = listOf(signal("a"))
        val changes = monitor.refresh().changes!!
        assertTrue(changes.addedSignals.isEmpty())
        assertTrue(changes.unconfirmedSignals.isEmpty())
    }

    @Test fun `signal order titles and timestamps do not create changes`() = runTest {
        val client = FakeClient(listOf(signal("a"), signal("b")))
        val monitor = client.monitor()
        monitor.refresh()
        client.signals = listOf(signal("b").copy(title = "localized"), signal("a"))
        assertFalse(monitor.refresh().changes!!.hasChanges)
    }

    @Test fun `polling stops on cancellation and resumes with the same baseline`() = runTest {
        val client = FakeClient()
        val monitor = client.monitor(2.seconds)
        val events = mutableListOf<MonitoringEvent>()
        val job = launch { monitor.observe().collect(events::add) }
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(2, client.calls)
        job.cancel()
        runCurrent()
        advanceTimeBy(10000)
        assertEquals(2, client.calls)
        val baseline = (events.first() as MonitoringEvent.Assessed).update.baseline
        val resumed = launch { monitor.observe().collect(events::add) }
        runCurrent()
        assertEquals(3, client.calls)
        assertEquals(baseline, (events.last() as MonitoringEvent.Assessed).update.baseline)
        resumed.cancel()
    }

    @Test fun `manual refresh is published to observer and scans cannot overlap`() = runTest {
        val client = FakeClient().apply { latency = 100 }
        val monitor = client.monitor(2.seconds)
        val events = mutableListOf<MonitoringEvent>()
        val job = launch { monitor.observe().collect(events::add) }
        runCurrent()
        val manual = async { monitor.refresh() }
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, client.maxActive)
        assertEquals(manual.await(), (events.last() as MonitoringEvent.Assessed).update)
        job.cancel()
    }

    @Test fun `failure preserves baseline publishes failure and polling recovers`() = runTest {
        val client = FakeClient()
        val monitor = client.monitor(1.seconds)
        val events = mutableListOf<MonitoringEvent>()
        val job = launch { monitor.observe().collect(events::add) }
        runCurrent()
        client.failure = IllegalStateException("private diagnostic")
        advanceTimeBy(1000)
        runCurrent()
        val failure = events.last() as MonitoringEvent.Failed
        assertNotNull(failure.lastSuccessful)
        assertFalse(failure.toString().contains("private diagnostic"))
        client.failure = null
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(failure.lastSuccessful!!.baseline, (events.last() as MonitoringEvent.Assessed).update.baseline)
        job.cancel()
    }

    @Test fun `manual failures propagate and cancellation is not emitted as failure`() = runTest {
        val client = FakeClient()
        val monitor = client.monitor()
        val first = monitor.refresh()
        client.failure = CancellationException()
        try { monitor.refresh(); fail("Expected cancellation") } catch (_: CancellationException) { }
        client.failure = IllegalStateException()
        try { monitor.refresh(); fail("Expected failure") } catch (_: IllegalStateException) { }
        client.failure = null
        val next = monitor.refresh()
        assertEquals(first.baseline, next.baseline)
        assertEquals(3L, next.sequence)
    }

    @Test fun `second observer is rejected without stopping first observer`() = runTest {
        val monitor = FakeClient().monitor()
        val first = launch { monitor.observe().collect() }
        runCurrent()
        var rejected = false
        val second = launch {
            try { monitor.observe().collect() } catch (_: IllegalStateException) { rejected = true }
        }
        runCurrent()
        assertTrue(rejected)
        assertTrue(first.isActive)
        first.cancel()
        second.join()
    }

    @Test fun `cancellation during scan stops polling without retry`() = runTest {
        var calls = 0
        val client = object : DeviceTrustClient {
            override suspend fun collectEvidence(): DeviceEvidence = error("unused")
            override suspend fun assess(): TrustAssessment { calls++; awaitCancellation() }
        }
        val monitor = client.monitor(1.seconds)
        val job = launch { monitor.observe().collect() }
        runCurrent()
        job.cancel()
        runCurrent()
        advanceTimeBy(5000)
        assertEquals(1, calls)
    }

    @Test fun `score and risk transitions are reported independently of signal identity`() = runTest {
        val client = FakeClient(listOf(signal("a")))
        val monitor = client.monitor()
        monitor.refresh()
        client.signals = listOf(signal("a").copy(weight = 70))
        val changes = monitor.refresh().changes!!
        assertEquals(40, changes.scoreDelta)
        assertEquals(TrustLevel.REVIEW, changes.previousLevel)
        assertEquals(TrustLevel.HIGH_RISK, changes.currentLevel)
        assertTrue(changes.hasChanges)
        assertEquals(1, changes.changedSignals.size)
    }

    @Test fun `polling waits for scan completion before starting the interval`() = runTest {
        val client = FakeClient().apply { latency = 500 }
        val monitor = client.monitor(1.seconds)
        val job = launch { monitor.observe().collect() }
        runCurrent()
        advanceTimeBy(1499)
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, client.calls)
        job.cancel()
    }

    @Test fun `invalid polling intervals are rejected`() {
        listOf(Duration.ZERO, (-1).seconds, 999.milliseconds, Duration.INFINITE).forEach {
            try { FakeClient().monitor(it); fail("Expected invalid interval") } catch (_: IllegalArgumentException) { }
        }
    }

    private fun signal(id: String, detail: String = "detail") =
        TrustSignal(id, SignalCategory.HOOKING, 30, id, detail)

    private class FakeClient(var signals: List<TrustSignal> = emptyList()) : DeviceTrustClient {
        var evaluated = signals.map { it.id }.toSet()
        var calls = 0
        var latency = 0L
        var failure: Exception? = null
        var active = 0
        var maxActive = 0
        override suspend fun collectEvidence() = DeviceEvidence(2, calls.toLong(), signals, evaluated)
        override suspend fun assess(): TrustAssessment {
            calls++
            active++
            maxActive = maxOf(maxActive, active)
            try {
                delay(latency)
                failure?.let { throw it }
                return DefaultTrustPolicy().evaluate(collectEvidence())
            } finally { active-- }
        }
    }
}
