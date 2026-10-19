package com.example.devicetrust

import com.xheghun.devicetrust.DefaultTrustPolicy
import com.xheghun.devicetrust.DeviceEvidence
import com.xheghun.devicetrust.DeviceTrustClient
import com.xheghun.devicetrust.TrustAssessment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrustViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `polling follows subscriptions and resume keeps the session baseline`() = runTest(dispatcher) {
        val client = FakeClient()
        val model = TrustViewModel(client)
        runCurrent()
        assertEquals(0, client.calls)
        val observer = backgroundScope.launch { model.state.collect() }
        runCurrent()
        assertEquals(1, client.calls)
        assertTrue(model.state.value.timeline.single().baseline)
        observer.cancel()
        runCurrent()
        advanceTimeBy(60000)
        runCurrent()
        assertEquals(1, client.calls)
        backgroundScope.launch { model.state.collect() }
        runCurrent()
        assertEquals(2, client.calls)
        assertEquals(1, model.state.value.timeline.count { it.baseline })
    }

    @Test fun `timeline is bounded and refresh avoids duplicate button requests`() = runTest(dispatcher) {
        val client = FakeClient()
        val model = TrustViewModel(client)
        backgroundScope.launch { model.state.collect() }
        runCurrent()
        repeat(55) { model.scan(); runCurrent() }
        assertEquals(50, model.state.value.timeline.size)
        assertEquals(56, client.calls)
        client.latency = 1000
        model.scan()
        model.scan()
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(57, client.calls)
        assertFalse(model.state.value.scanning)
    }

    @Test fun `failed refresh retains old assessment marks failure and later recovers`() = runTest(dispatcher) {
        val client = FakeClient()
        val model = TrustViewModel(client)
        backgroundScope.launch { model.state.collect() }
        runCurrent()
        val previous = model.state.value.assessment
        client.fail = true
        model.scan()
        runCurrent()
        assertEquals(previous, model.state.value.assessment)
        assertEquals(R.string.monitor_scan_failed, model.state.value.error)
        assertEquals(1, model.state.value.timeline.size)
        client.fail = false
        model.scan()
        runCurrent()
        assertNull(model.state.value.error)
        assertEquals(2, model.state.value.timeline.size)
    }

    private class FakeClient : DeviceTrustClient {
        var calls = 0
        var fail = false
        var latency = 0L
        override suspend fun collectEvidence() = DeviceEvidence(2, calls.toLong(), emptyList())
        override suspend fun assess(): TrustAssessment {
            calls++
            delay(latency)
            check(!fail) { "Private failure details" }
            return DefaultTrustPolicy().evaluate(collectEvidence())
        }
    }
}
