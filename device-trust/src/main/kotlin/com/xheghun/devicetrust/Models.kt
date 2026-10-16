package com.xheghun.devicetrust

public enum class SignalCategory { ROOT, HOOKING, EMULATOR, SYSTEM_INTEGRITY }
public enum class TrustLevel { LOW_RISK, REVIEW, HIGH_RISK }

public data class TrustSignal(
    val id: String,
    val category: SignalCategory,
    val weight: Int,
    val title: String,
    val detail: String,
)

public data class DeviceEvidence(
    val schemaVersion: Int,
    val collectedAtEpochMillis: Long,
    val signals: List<TrustSignal>,
    val evaluatedSignalIds: Set<String> = emptySet(),
)

public data class TrustAssessment(
    val score: Int,
    val level: TrustLevel,
    val evidence: DeviceEvidence,
) {
    public val signals: List<TrustSignal> get() = evidence.signals
    public val isRootOrHookingSuspected: Boolean get() = signals.any {
        it.category == SignalCategory.ROOT || it.category == SignalCategory.HOOKING
    }
    public val isEmulatorSuspected: Boolean get() = signals.any { it.category == SignalCategory.EMULATOR }
    public val isSystemIntegritySuspected: Boolean get() = signals.any { it.category == SignalCategory.SYSTEM_INTEGRITY }
}
