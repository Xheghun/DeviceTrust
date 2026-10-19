package com.xheghun.devicetrust.internal

import com.xheghun.devicetrust.SignalCategory
import org.junit.Assert.*
import org.junit.Test

class NativeSignalSourceTest {
    @Test fun `coverage records are separate from risk signals`() {
        val result = NativeSignalSource.decode(arrayOf("root_artifact\tCHECK\t0\t\t", "test_keys\tROM\t25\tTest keys\ttest-keys"))
        assertEquals(setOf("root_artifact"), result.evaluatedSignalIds)
        assertEquals(SignalCategory.SYSTEM_INTEGRITY, result.signals.single().category)
        assertEquals("test_keys", result.signals.single().id)
    }

    @Test(expected = IllegalStateException::class)
    fun `malformed records fail collection instead of silently dropping evidence`() {
        NativeSignalSource.decode(arrayOf("malformed"))
    }

    @Test(expected = IllegalStateException::class)
    fun `invalid weights fail collection`() {
        NativeSignalSource.decode(arrayOf("tracer\tHOOKING\tbad\tTracer\tdetail"))
    }
}
