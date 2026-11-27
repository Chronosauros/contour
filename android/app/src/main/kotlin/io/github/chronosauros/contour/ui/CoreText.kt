package io.github.chronosauros.contour.ui

import android.content.Context
import androidx.annotation.StringRes
import io.github.chronosauros.contour.R

/**
 * The messages of :core that reach the screen of a release build, mapped to string resources. :core stays English and is not changed: a known message (or a pattern with its numbers and names)
 * becomes a resource with those values as arguments, anything else is shown as it is. The map works only where the boolean resource `translate_core_text` is true (the cn build); everywhere
 * else the raw text of :core is shown, exactly as it always was.
 *
 * The English values of these resources equal the messages of :core word for word, so a build that translates nothing would read the same.
 * Not mapped, on purpose (they stay English in every build; the list is in the cn report): the deep diagnostics of the codecs and sessions (register, frame and checksum texts), the
 * wording of a review (perf, debug) build, the Dawn Pro 2 / Echo-A / Allegro Mini texts that only a review build shows, and the "needs device preamp" refusal of a HIGH SHELF with a
 * combined number list.
 */
object CoreText {
    private class Rule(val pattern: Regex, @StringRes val id: Int)

    private fun rule(regex: String, @StringRes id: Int) = Rule(Regex(regex), id)

    /** The label HOLD TO SEND shows for a blocked EQ ([io.github.chronosauros.contour.core.DeviceTarget.blockLabel], [io.github.chronosauros.contour.core.DeviceTarget.invalidEqHeadline]) and the other labels of :core. */
    private val labels = listOf(
        rule("""^READ THE DAC FIRST$""", R.string.core_label_read_dac_first),
        rule("""^PREAMP OUT OF RANGE$""", R.string.core_label_preamp_out_of_range),
        rule("""^INVALID EQ - EDIT BAND$""", R.string.core_label_invalid_eq_edit_band),
        rule("""^INVALID EQ - adjust band count$""", R.string.core_label_invalid_adjust_band_count),
        rule("""^INVALID EQ - adjust shelf gain or Q$""", R.string.core_label_invalid_adjust_shelf),
        rule("""^INVALID EQ - adjust preamp$""", R.string.core_label_invalid_adjust_preamp),
        rule("""^INVALID EQ - adjust frequency$""", R.string.core_label_invalid_adjust_frequency),
        rule("""^INVALID EQ - adjust gain$""", R.string.core_label_invalid_adjust_gain),
        rule("""^INVALID EQ - adjust Q$""", R.string.core_label_invalid_adjust_q),
        rule("""^READ ONLY$""", R.string.core_label_read_only),
        rule("""^PREAMP$""", R.string.core_label_preamp),
        rule("""^PRE GAIN$""", R.string.core_label_pre_gain),
        rule("""^(\d+) BANDS - MAX (\d+)$""", R.string.core_label_bands_max),
        rule("""^BAND (\d+): TYPE UNSUPPORTED - EDIT BAND$""", R.string.core_label_band_type_edit),
        rule("""^BAND (\d+): TYPE UNSUPPORTED$""", R.string.core_label_band_type),
        rule("""^BAND (\d+): FREQ (.+) HZ - EDIT BAND$""", R.string.core_label_band_freq_edit),
        rule("""^BAND (\d+): FREQ (.+) HZ$""", R.string.core_label_band_freq),
        rule("""^BAND (\d+): GAIN (.+) DB - EDIT BAND$""", R.string.core_label_band_gain_edit),
        rule("""^BAND (\d+): GAIN (.+) DB$""", R.string.core_label_band_gain),
        rule("""^BAND (\d+): Q (\S+) - EDIT BAND$""", R.string.core_label_band_q_edit),
        rule("""^BAND (\d+): Q (\S+)$""", R.string.core_label_band_q),
        rule("""^BAND (\d+): SHELF Q (\S+) - EDIT BAND$""", R.string.core_label_band_shelf_q_edit),
        rule("""^BAND (\d+): SHELF Q (\S+)$""", R.string.core_label_band_shelf_q),
        rule("""^BAND (\d+): PEAK Q (\S+) - EDIT BAND$""", R.string.core_label_band_peak_q_edit),
        rule("""^BAND (\d+): PEAK Q (\S+)$""", R.string.core_label_band_peak_q),
    )

    /** The sentences of :core a person can read under the controls ("SEND BLOCKED: ...", an import report, a refusal of the preamp editor). */
    private val sentences = listOf(
        rule("""^Read the DAC first$""", R.string.core_issue_read_dac_first),
        rule("""^(.+) is read only in Contour$""", R.string.core_issue_read_only),
        rule("""^Band (\d+): type unsupported$""", R.string.core_issue_band_type_unsupported),
        rule("""^Band (\d+): freq (.+) Hz outside (.+)-(.+) Hz$""", R.string.core_issue_band_freq_outside),
        rule("""^Band (\d+): gain (.+) dB outside (.+)\.\.(.+) dB$""", R.string.core_issue_band_gain_outside),
        rule("""^Band (\d+): Q (\S+) outside (\S+)-(\S+)$""", R.string.core_issue_band_q_outside),
        rule("""^Band (\d+): gain (.+) dB outside (.+)\.\.(.+) dB \(a shelf on the (.+) is limited to what was measured\)$""", R.string.core_issue_band_gain_outside_shelf),
        rule("""^Band (\d+): Q (\S+) outside (\S+)-(\S+) \(a shelf on the (.+) is limited to what was measured\)$""", R.string.core_issue_band_q_outside_shelf),
        rule("""^Band (\d+): shelf gain/Q has no real response$""", R.string.core_issue_band_shelf_no_response),
        rule("""^Band (\d+): (.+) takes (.+) only$""", R.string.core_issue_band_takes_only),
        rule("""^KT supports (\d+) bands; no truncation$""", R.string.core_issue_kt_bands_over),
        rule("""^Band (\d+): shelf Q (\S+) outside (\S+)-(\S+)$""", R.string.core_issue_band_shelf_q_outside),
        rule("""^Band (\d+): peak Q (\S+) outside (\S+)-(\S+)$""", R.string.core_issue_band_peak_q_outside),
        rule("""^Pregain (\S+) outside (\S+)\.\.(\S+) dB$""", R.string.core_issue_pregain_outside),
        rule("""^(\d+) bands; (.+) accepts (\d+); no truncation$""", R.string.core_issue_bands_over),
        rule("""^(\d+) enabled bands; (.+) has (\d+)$""", R.string.core_issue_enabled_bands_over),
        rule("""^Band (\d+): (\S+) is not supported by (.+)$""", R.string.core_issue_band_type_not_supported),
        rule("""^Band (\d+): (\S+) Hz outside (\S+)-(\S+) Hz$""", R.string.core_issue_band_hz_outside),
        rule("""^Band (\d+): (\S+) dB outside (\S+)\.\.(\S+) dB$""", R.string.core_issue_band_db_outside),
        rule("""^Preamp (\S+) dB outside (\S+)\.\.(\S+) dB range$""", R.string.core_issue_preamp_range),
        rule("""^(.+) band (\d+) cannot be represented safely\. Reduce the boost, raise the shelf frequency or adjust Q\.$""", R.string.core_issue_band_unsafe_shelf),
        rule("""^(.+) band (\d+) cannot be represented safely\. Reduce the boost or adjust frequency/Q\.$""", R.string.core_issue_band_unsafe),
        rule("""^this EQ needs (\S+) dB; Contour supports down to (\S+) dB for this device\. Reduce the combined boosts\.$""", R.string.core_issue_auto_needs),
        rule("""^AUTO preamp: no finite response; cannot send to (.+)$""", R.string.core_issue_auto_no_response),
        rule("""^Contour does not write the EPZ TP65 preamp yet, so it stays at 0 dB; this EQ asks for (\S+) dB\. Reduce the boosts, or set the preamp to 0 dB \(boosts may then clip\)\.$""", R.string.core_issue_tp65_preamp),
        rule("""^The DAC is on EQ index (\d+), one of its own presets\. Switch the DAC to its PEQ mode \(EQ index (.+)\): HOLD TO SEND writes the EQ the DAC is on\.$""", R.string.core_note_dawn_bank),
        // import: lines of the file whose values could not be read (ApoText.parse)
        rule("""^filter (\S+) unreadable, skipped$""", R.string.core_import_filter_unreadable),
        rule("""^filters (.+) unreadable, skipped$""", R.string.core_import_filters_unreadable),
        rule("""^preamp unreadable, skipped$""", R.string.core_import_preamp_unreadable),
    )

    /** Setting words of the DAC Settings page: row labels, section headers and option words, exact match. */
    private val words: Map<String, Int> = mapOf(
        "VOLUME" to R.string.core_setting_volume,
        "VOLUME STEPS" to R.string.core_setting_volume_steps,
        "BALANCE" to R.string.core_setting_balance,
        "IN-LINE MIC" to R.string.core_setting_in_line_mic,
        "LIGHT" to R.string.core_setting_light,
        "USB AUDIO" to R.string.core_setting_usb_audio,
        "FILTER" to R.string.core_setting_filter,
        "GAIN" to R.string.core_setting_gain,
        "OUTPUT" to R.string.core_setting_output,
        "DRE" to R.string.core_setting_dre,
        "BUTTONS" to R.string.core_setting_buttons,
        "BRIGHTNESS" to R.string.core_setting_brightness,
        "SCREEN OFF" to R.string.core_setting_screen_off,
        "ROTATION" to R.string.core_setting_rotation,
        "LANGUAGE" to R.string.core_setting_language,
        "MIC GAIN" to R.string.core_setting_mic_gain,
        "EQ INDEX" to R.string.core_setting_eq_index,
        "GLOBAL GAIN" to R.string.core_setting_global_gain,
        "PRE GAIN" to R.string.core_setting_pre_gain,
        "MAX VOLUME" to R.string.core_setting_max_volume,
        "PRESET" to R.string.core_setting_preset,
        "EQ" to R.string.core_setting_eq,
        "DEVICE" to R.string.core_setting_section_device,
        "SCREEN" to R.string.core_setting_section_screen,
        "SOUND" to R.string.core_setting_section_sound,
        "OFF" to R.string.core_setting_off,
        "ON" to R.string.core_setting_on,
        "HIGH" to R.string.core_setting_high,
        "LOW" to R.string.core_setting_low,
        "CLASS AB" to R.string.core_setting_class_ab,
        "CLASS H" to R.string.core_setting_class_h,
        "MODE A" to R.string.core_setting_mode_a,
        "MODE B" to R.string.core_setting_mode_b,
        "UAC 1.0" to R.string.core_setting_uac_1,
        "UAC 2.0" to R.string.core_setting_uac_2,
        "MINIMUM PHASE FAST" to R.string.core_setting_min_phase_fast,
        "LINEAR PHASE FAST" to R.string.core_setting_lin_phase_fast,
        "MINIMUM PHASE SLOW" to R.string.core_setting_min_phase_slow,
        "LINEAR PHASE SLOW" to R.string.core_setting_lin_phase_slow,
        "NON OVER-SAMPLING" to R.string.core_setting_non_oversampling,
        "MIN F" to R.string.core_setting_min_f,
        "LIN F" to R.string.core_setting_lin_f,
        "MIN S" to R.string.core_setting_min_s,
        "LIN S" to R.string.core_setting_lin_s,
        "NOS" to R.string.core_setting_nos,
        "FAST-LL" to R.string.core_setting_fast_ll,
        "FAST-PC" to R.string.core_setting_fast_pc,
        "SLOW-LL" to R.string.core_setting_slow_ll,
        "SLOW-PC" to R.string.core_setting_slow_pc,
        "NON-OS" to R.string.core_setting_non_os,
        "15 S" to R.string.core_setting_t_15s,
        "30 S" to R.string.core_setting_t_30s,
        "1 MIN" to R.string.core_setting_t_1min,
        "2 MIN" to R.string.core_setting_t_2min,
        "5 MIN" to R.string.core_setting_t_5min,
        "10 MIN" to R.string.core_setting_t_10min,
        "30 MIN" to R.string.core_setting_t_30min,
        "CHINESE" to R.string.core_setting_chinese,
        "ENGLISH" to R.string.core_setting_english,
        "no reply" to R.string.core_setting_no_reply,
    )

    /** The line of the DAC Settings page header for the firmware of the DAC ("FW 1.2.0"; "FW 2.20" on a DAC whose version has two parts). */
    private val firmware = rule("""^FW (\d+\.\d+(?:\.\d+)?)$""", R.string.core_setting_firmware)

    /**
     * Lines of a factory reset (what it changed, what the write-back did): "<setting>: <old> -> <new>" and "<setting>: restored". Their setting names and values are the words above,
     * so each is looked up there; every other line (slot names, slot EQs, bands, errors of the DAC) stays as :core wrote it.
     */
    private val resetLines = listOf(
        rule("""^(.+): (.+) -> (.+)$""", R.string.core_reset_diff_line),
        rule("""^(.+): restored$""", R.string.core_reset_restored),
        rule("""^(.+): unchanged$""", R.string.core_reset_unchanged),
        rule("""^(.+): read only, not written back$""", R.string.core_reset_read_only),
        rule("""^(.+): not restored \(the DAC session ended\)$""", R.string.core_reset_session_ended),
        rule("""^(.+): NOT restored: no reply$""", R.string.core_reset_no_reply),
        rule("""^(.+): NOT restored: the DAC reads (.+), saved (.+)$""", R.string.core_reset_reads),
    )

    private val all = labels + sentences + firmware

    /** [raw] of :core as the build shows it: a setting word, or a resource where the build translates [labels] and [sentences] and one matches, the raw text otherwise. */
    fun translate(context: Context, raw: String): String {
        if (!context.resources.getBoolean(R.bool.translate_core_text)) return raw
        val text = raw.trim()
        words[text]?.let { return context.getString(it) }
        for (rule in all) {
            val m = rule.pattern.find(text) ?: continue
            val args = m.groupValues.drop(1)
            return context.getString(rule.id, *args.toTypedArray())
        }
        return raw
    }

    /** One line of a factory reset ([resetLines]) as the build shows it; a line that names no setting word is returned as it is. */
    fun translateResetLine(context: Context, raw: String): String {
        if (!context.resources.getBoolean(R.bool.translate_core_text)) return raw
        for (rule in resetLines) {
            val m = rule.pattern.find(raw.trim()) ?: continue
            val args = m.groupValues.drop(1)
            // only a line about a setting is rewritten: its name or one of its values is a known word
            if (args.none { words.containsKey(it.trim()) }) return raw
            return context.getString(rule.id, *args.map { a -> words[a.trim()]?.let { context.getString(it) } ?: a }.toTypedArray())
        }
        return raw
    }
}
