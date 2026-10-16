package com.xheghun.devicetrust

import com.xheghun.devicetrust.internal.BuildSignalSource
import com.xheghun.devicetrust.internal.NativeSignalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DefaultDeviceTrustClient(private val policy: TrustPolicy) : DeviceTrustClient {
    private val collectionMutex = Mutex()

    override suspend fun collectEvidence(): DeviceEvidence = collectionMutex.withLock {
        withContext(Dispatchers.IO) {
            val native = NativeSignalSource.collect()
            DeviceEvidence(
                schemaVersion = DeviceTrust.SIGNAL_SCHEMA_VERSION,
                collectedAtEpochMillis = System.currentTimeMillis(),
                signals = native.signals + BuildSignalSource.collect(),
                evaluatedSignalIds = native.evaluatedSignalIds + setOf("build_emulator", "java_test_keys"),
            )
        }
    }

    override suspend fun assess(): TrustAssessment = policy.evaluate(collectEvidence())
}
