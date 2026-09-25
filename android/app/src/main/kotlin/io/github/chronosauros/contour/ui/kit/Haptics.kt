package io.github.chronosauros.contour.ui.kit

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Haptics recipe from research/TECH-UX.md section 3. Pixel: one-primitive compositions (LOW_TICK per step,
 * TICK at labelled values, CLICK at the end of a hold); elsewhere performHapticFeedback. At most one tick
 * per 33 ms - extra ticks are dropped, never queued. Call only from gesture handlers, when the quantized
 * value changes - never from composition.
 */
class Haptics(context: Context, private val view: View) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private val primitives: Boolean = Build.VERSION.SDK_INT >= 31 && vibrator != null &&
        vibrator.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
        )

    private var last = 0L

    private fun gate(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - last < 33) return false
        last = now
        return true
    }

    private fun primitive(id: Int, scale: Float) {
        if (Build.VERSION.SDK_INT < 31) return
        val effect = VibrationEffect.startComposition().addPrimitive(id, scale.coerceIn(0f, 1f)).compose()
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(effect)
        }
    }

    private fun feedback(constant: Int) {
        view.performHapticFeedback(constant)
    }

    /** A quantization step / ISO centre / Q detent: light tick. */
    fun step() {
        if (!gate()) return
        if (primitives) primitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.4f)
        else feedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }

    /** 0 dB and the labelled scale values: stronger tick. */
    fun detent() {
        if (!gate()) return
        if (primitives) primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1f)
        else feedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }

    /** 1 = step, 2 = detent (see [Scale.crossing]). */
    fun crossing(kind: Int) {
        when (kind) {
            1 -> step()
            2 -> detent()
        }
    }

    /** A segment of a rocker was selected. */
    fun segment() {
        if (!gate()) return
        if (primitives) primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f)
        else feedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }

    /** The rising texture of HOLD TO SEND: a LOW_TICK with the given scale. */
    fun texture(scale: Float) {
        if (!gate()) return
        if (primitives) primitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale)
        else if (scale > 0.4f) feedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun click() {
        last = SystemClock.uptimeMillis()
        if (primitives) primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
        else feedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun tap() {
        if (!gate()) return
        feedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun longPress() {
        last = SystemClock.uptimeMillis()
        feedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun reject() {
        last = SystemClock.uptimeMillis()
        feedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }

    fun confirm() {
        last = SystemClock.uptimeMillis()
        feedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

val LocalHaptics = staticCompositionLocalOf<Haptics> { error("no Haptics") }

@Composable
fun rememberHaptics(): Haptics {
    val context = LocalContext.current
    val view = LocalView.current
    return remember(view) { Haptics(context, view) }
}
