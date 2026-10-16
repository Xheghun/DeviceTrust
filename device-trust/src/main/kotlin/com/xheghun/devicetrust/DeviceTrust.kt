package com.xheghun.devicetrust

/** Entry point for creating a device-risk client. */
public object DeviceTrust {
    public const val SIGNAL_SCHEMA_VERSION: Int = 2

    @JvmStatic
    @JvmOverloads
    public fun create(policy: TrustPolicy = DefaultTrustPolicy()): DeviceTrustClient =
        DefaultDeviceTrustClient(policy)
}

public interface DeviceTrustClient {
    /** Collects local evidence on a background dispatcher. */
    public suspend fun collectEvidence(): DeviceEvidence

    /** Collects evidence and evaluates it with the configured policy. */
    public suspend fun assess(): TrustAssessment
}
