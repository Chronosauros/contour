package io.github.chronosauros.contour.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.chronosauros.contour.R

/**
 * A text the person reads, kept apart from the words themselves until it is drawn: code that is not a composable (the model, the sender, the importer, the USB controller) says WHAT to show
 * and the UI turns it into a string with the resources of the build ([asString], [resolve]). That is what lets a build give the same screens other words (res/values in src/cn) and what keeps
 * the logic from ever comparing a text that may be translated: the code compares kinds and flags, never a [UiText] or its string.
 *
 * - [Plain]: a text as it is (a profile name, a device name, a DAC's own words).
 * - [Res] / [Plural]: a string or plurals resource with arguments. An argument that is itself a [UiText] is resolved first.
 * - [Core]: a message of :core (English); [CoreText] turns it into a resource where the build asks for it (`translate_core_text`), otherwise it is shown as it is.
 * - [Joined]: several texts with the list separator of the build.
 */
sealed interface UiText {
    fun resolve(context: Context): String

    data class Plain(val text: String) : UiText {
        override fun resolve(context: Context): String = text
    }

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(context: Context): String = context.getString(id, *resolveArgs(context, args))
    }

    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(context: Context): String = context.resources.getQuantityString(id, count, *resolveArgs(context, args))
    }

    data class Core(val raw: String) : UiText {
        override fun resolve(context: Context): String = CoreText.translate(context, raw)
    }

    data class Joined(val parts: List<UiText>) : UiText {
        override fun resolve(context: Context): String = parts.joinToString(context.getString(R.string.list_separator)) { it.resolve(context) }
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText = Plural(id, count, args.toList())
        fun core(raw: String): UiText = Core(raw)
        fun plain(text: String): UiText = Plain(text)
    }
}

private fun resolveArgs(context: Context, args: List<Any>): Array<Any> = Array(args.size) { i -> (args[i] as? UiText)?.resolve(context) ?: args[i] }

/** The text for the screen it is drawn on. */
@Composable
fun UiText.asString(): String = resolve(LocalContext.current)

/** [UiText.Core] of every message of :core, joined by the build's list separator. */
fun coreTexts(raw: List<String>): UiText = UiText.Joined(raw.map { UiText.Core(it) })
