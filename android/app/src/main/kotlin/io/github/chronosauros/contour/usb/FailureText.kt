package io.github.chronosauros.contour.usb

import io.github.chronosauros.contour.R
import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.ui.UiText
import java.io.IOException

/**
 * A failure the app words itself for the person (it says what to do): the technical [message] goes to the log and to a review build, [text] is what a release shows. Thrown where a message
 * used to be written as a sentence and found again by its words ([FailureText.of]); now it is found by its type.
 */
class AppFailure(val text: UiText, message: String) : IOException(message)

/**
 * The words a failure puts on screen. A review build ([Proven.channel] PERF) keeps the technical reason as it is - it is for the maintainer; a release says what happened in a few
 * plain sentences and what to do. Only what is shown goes through here: the USB log keeps the raw text.
 *
 * The raw messages of :core and of the USB code are English and are only classified here (a keyword picks one of the sentences below); no text of a resource is ever compared.
 */
internal object FailureText {
    private val NO_ANSWER = UiText.res(R.string.failure_no_answer)
    private val MISMATCH = UiText.res(R.string.failure_mismatch)
    private val GONE = UiText.res(R.string.failure_gone)
    private val SLOT = UiText.res(R.string.failure_slot)
    private val GENERIC = UiText.res(R.string.failure_generic)

    private val review: Boolean get() = Proven.channel == BuildChannel.PERF

    /** Messages of :core that are plain enough to be shown as they are. */
    private val plain = listOf("nothing was sent", "not confirmed")

    /** [e] as it is shown: the sentence an [AppFailure] carries, else the message classified by [of]. */
    fun of(e: Throwable): UiText {
        if (e is AppFailure && !review) return e.text
        return of(e.message ?: e.javaClass.simpleName)
    }

    /** [raw] (an exception's message or a session's reason) as it is shown. */
    fun of(raw: String): UiText {
        if (review) return UiText.Plain(raw)
        val m = raw.lowercase()
        return when {
            plain.any { it in m } -> UiText.Core(raw)
            "mismatch" in m || "differ" in m || "reads back" in m || "read back" in m -> MISMATCH
            "reports slot" in m || "slot switch" in m || "selection failed" in m -> SLOT
            "timed out" in m || "did not settle" in m || "no reply" in m || "no valid" in m || "acknowledgement" in m || "silent" in m -> NO_ANSWER
            "session changed" in m || "removed" in m || "detach" in m || "gone" in m || "no dac" in m -> GONE
            else -> GENERIC
        }
    }

    /** The reason of a write whose read-back did not match: [detail] is the first difference the codec found, or a sentence a release shows as written (the FreeDSP Mini's "nothing was saved"). */
    fun readBack(detail: String?): UiText = if (review) UiText.Plain("READ-BACK MISMATCH: ${detail ?: ""}") else if (detail != null && "nothing was saved" in detail.lowercase()) UiText.Core(detail) else MISMATCH

    /** A note that comes with a write that was verified (the slot name did not read back): the technical text in a review build, one plain sentence in a release. */
    fun note(raw: String?): UiText? = when {
        raw == null -> null
        review -> UiText.Plain(raw)
        "slot name" in raw.lowercase() -> UiText.res(R.string.failure_note_slot_name)
        else -> UiText.res(R.string.failure_note_small_difference)
    }
}
