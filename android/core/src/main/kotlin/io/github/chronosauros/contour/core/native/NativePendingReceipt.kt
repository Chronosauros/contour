package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.Profile

/** In-memory only. A save attempt is not a save acknowledgement, flash proof or audio proof. */
sealed interface NativePendingExpected {
    fun matches(state: NativeState): Boolean
    class Fiio(private val config: FiioConfig, expected: FiioSnapshot) : NativePendingExpected {
        private val expected = expected.copy(bands = expected.bands.map { it.copy() })
        override fun matches(state: NativeState): Boolean = state is NativeState.Fiio &&
            state.codec.config == config && state.raw.count == state.raw.bands.size &&
            state.raw.bands.map { it.index } == (0 until state.raw.count).toList() &&
            FiioCodec(config).matches(expected, state.raw)
    }
    class Kt(private val model: KtMicroCatalog.Model, private val slot: Int,
             registers: List<KtMicroCodec.RegisterValue>) : NativePendingExpected {
        private val registers = registers.map { it.copy(value = it.value.toList()) }
        override fun matches(state: NativeState): Boolean = state is NativeState.Kt &&
            state.raw.model == model && state.raw.slot == slot && state.raw.bands.size == model.bandCount &&
            state.raw.pregainDb == KtMicroCodec.parsePregain(registers.single { it.register == KtMicroCodec.PREGAIN_REGISTER }) &&
            state.raw.registers == registers
    }
}

data class NativeUsbIdentity(val vendorId: Int, val productId: Int, val productName: String?, val serial: String? = null)

class NativePendingReceipt(val identity: NativeUsbIdentity, val target: DeviceTarget, profile: Profile,
                           val originatingGeneration: Long, private val expected: NativePendingExpected) {
    enum class Intent { EXPLICIT_HOLD_SAVE_READBACK }
    val intent = Intent.EXPLICIT_HOLD_SAVE_READBACK
    private val original = profile.copy(bands = profile.bands.map { it.copy() })
    val profile: Profile get() = original.copy(bands = original.bands.map { it.copy() })
    val identityWarning: String get() = if (identity.serial == null)
        "USB serial unavailable: same VID/PID/name cannot prove the same physical unit. Verify the original DAC and tap Connect / Read explicitly."
        else "Reconnect the original DAC and read its complete registers."
    val pendingReason: String get() = "Save attempted; unverified. $identityWarning No automatic resend/retry. App restart loses this verification intent."
    data class Check(val verified: Boolean, val reason: String, val state: NativeState? = null)

    /** Read only; no USB calls and no plan recompiled from an edited/replacement profile. */
    fun verify(identity: NativeUsbIdentity, target: DeviceTarget, currentProfile: Profile?, state: NativeState?,
               readGeneration: Long, currentGeneration: Long, explicitUserRead: Boolean): Check {
        fun pending(reason: String) = Check(false, "Pending readback: $reason")
        if (readGeneration != currentGeneration || readGeneration < originatingGeneration)
            return pending("DAC session changed; stale read discarded")
        if (target != this.target || identity.vendorId != this.identity.vendorId ||
            identity.productId != this.identity.productId || identity.productName != this.identity.productName)
            return pending("wrong exact USB target; original save remains unverified")
        if (this.identity.serial != null && identity.serial != this.identity.serial)
            return pending("USB serial differs/unavailable; original unit not established")
        if (this.identity.serial == null && !explicitUserRead) return pending(identityWarning)
        if (!explicitUserRead && readGeneration <= originatingGeneration)
            return pending("Explicit read or a serial-proven new attachment is required")
        if (currentProfile != original) return pending("original profile changed or removed; cannot mark LAST SENT / ON DAC")
        if (state == null || !runCatching { expected.matches(state) }.getOrDefault(false)) return pending("complete native register mismatch or incomplete read")
        val verifiedState = when (state) {
            is NativeState.Fiio -> state.copy(receiptProfile = profile)
            is NativeState.Kt -> state.copy(receiptProfile = profile)
            else -> return pending("wrong native family")
        }
        val reason = "Complete native registers match the original save plan; flash persistence/audio not proven." +
            (if (this.identity.serial == null) " Same physical unit is user-confirmed, not proven by USB identity." else "")
        return Check(true, reason, verifiedState)
    }
}
