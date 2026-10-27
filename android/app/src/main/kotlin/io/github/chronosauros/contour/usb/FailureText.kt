package io.github.chronosauros.contour.usb

import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.Proven

/**
 * The words a failure puts on screen. A review build ([Proven.channel] PERF) keeps the technical reason as it is - it is for the maintainer; a release says what happened in a few
 * plain sentences and what to do. Only what is shown goes through here: the USB log keeps the raw text.
 */
internal object FailureText {
    const val NO_ANSWER = "The DAC didn't answer. Unplug it, plug it back in and try again."
    const val MISMATCH = "The DAC reads back something different from what was sent. Send again."
    const val GONE = "The DAC was unplugged or changed. Plug it in and try again."
    const val SLOT = "The DAC switched to a different slot than expected. Try again."
    const val GENERIC = "Couldn't talk to the DAC. Unplug it, plug it back in and try again."

    private val review: Boolean get() = Proven.channel == BuildChannel.PERF

    /** Messages the app itself words for the person (they say what to do): shown as they are. */
    private val plain = listOf("nothing was sent", "plug it in", "plugged back in", "still working on the dac", "written down exactly", "saved on the phone", "not confirmed", "could not be read after the reset")

    /** [raw] (an exception's message or a session's reason) as it is shown. */
    fun of(raw: String): String {
        if (review) return raw
        val m = raw.lowercase()
        return when {
            plain.any { it in m } -> raw
            "mismatch" in m || "differ" in m || "reads back" in m || "read back" in m -> MISMATCH
            "reports slot" in m || "slot switch" in m || "selection failed" in m -> SLOT
            "timed out" in m || "did not settle" in m || "no reply" in m || "no valid" in m || "acknowledgement" in m || "silent" in m -> NO_ANSWER
            "session changed" in m || "removed" in m || "detach" in m || "gone" in m || "no dac" in m -> GONE
            else -> GENERIC
        }
    }

    /** The reason of a write whose read-back did not match: [detail] is the first difference the codec found. */
    fun readBack(detail: String?): String = if (review) "READ-BACK MISMATCH: ${detail ?: ""}" else MISMATCH

    /** A note that comes with a write that was verified (the slot name did not read back): the technical text in a review build, one plain sentence in a release. */
    fun note(raw: String?): String? = when {
        raw == null -> null
        review -> raw
        "slot name" in raw.lowercase() -> "The EQ is on the DAC, but the DAC didn't confirm the slot name."
        else -> "The EQ is on the DAC, but the DAC reported a small difference in what it read back."
    }
}
