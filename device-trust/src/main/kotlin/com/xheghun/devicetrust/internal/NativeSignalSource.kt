package com.xheghun.devicetrust.internal

import com.xheghun.devicetrust.SignalCategory
import com.xheghun.devicetrust.TrustSignal

internal data class NativeEvidence(val signals: List<TrustSignal>, val evaluatedSignalIds: Set<String>)

internal object NativeSignalSource {
    private val loaded by lazy { System.loadLibrary("devicetrust") }

    private external fun collectEncoded(): Array<String>

    fun collect(): NativeEvidence {
        try {
            loaded
            return decode(collectEncoded())
        } catch (failure: LinkageError) {
            throw IllegalStateException("Native evidence collection unavailable", failure)
        }
    }

    internal fun decode(encodedSignals: Array<String>): NativeEvidence {
        val evaluated = mutableSetOf<String>()
        val signals = mutableListOf<TrustSignal>()
        encodedSignals.forEach { encoded ->
            val parts = encoded.split('\t', limit = 5)
            check(parts.size == 5 && parts[0].isNotBlank()) { "Invalid native evidence" }
            if (parts[1] == "CHECK") {
                evaluated.add(parts[0])
            } else {
                val category = when (parts[1]) {
                    "ROM" -> SignalCategory.SYSTEM_INTEGRITY
                    else -> SignalCategory.valueOf(parts[1])
                }
                val weight = checkNotNull(parts[2].toIntOrNull()) { "Invalid signal weight" }
                check(weight in 0..100) { "Invalid signal weight" }
                signals.add(TrustSignal(parts[0], category, weight, parts[3], parts[4]))
            }
        }
        return NativeEvidence(signals.toList(), evaluated.toSet())
    }
}
