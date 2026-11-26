package io.github.chronosauros.contour.ui.kit

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.chronosauros.contour.R

/**
 * Profile icons: the two-tone Bryla set (owner 16.11.2026, [BrylaIcons]: 40 icons in four groups; two tones only for the icons of profiles).
 * A profile keeps the id it was saved with: an id from the old 20-icon set that is no longer in the picker shows its alias (spiral and circle a
 * vinyl, triangle a bolt, eye a film), an empty or unknown id the headphones. Nothing is rewritten in the profile files.
 */
object ProfileIcons {
    /** The picker (the icon board of EDIT PROFILE), in its order. */
    val ALL: List<Pair<String, ImageVector>> by lazy { BrylaIcons.IDS.map { it to BrylaIcons.get(it)!! } }

    /** The id an icon is drawn as: itself when it is in the picker, else its alias, else the headphones. */
    fun shown(name: String): String = if (BrylaIcons.get(name) != null) name else BrylaIcons.ALIASES[name] ?: BrylaIcons.FALLBACK

    fun of(name: String): ImageVector = BrylaIcons.get(shown(name)) ?: BrylaIcons.get(BrylaIcons.FALLBACK)!!

    /** The name of an icon (the board's accessibility words and the name on the ICON card). */
    @StringRes
    fun descriptionRes(name: String): Int = when (shown(name)) {
        "in-ear" -> R.string.profile_icon_in_ear
        "earbuds" -> R.string.profile_icon_earbuds
        "speaker" -> R.string.profile_icon_speaker
        "mic" -> R.string.profile_icon_mic
        "jack" -> R.string.profile_icon_jack
        "car" -> R.string.profile_icon_car
        "phone" -> R.string.profile_icon_phone
        "computer" -> R.string.profile_icon_computer
        "tuning-fork" -> R.string.profile_icon_tuning_fork
        "note" -> R.string.profile_icon_note
        "vinyl" -> R.string.profile_icon_vinyl
        "guitar" -> R.string.profile_icon_guitar
        "keys" -> R.string.profile_icon_keys
        "wave" -> R.string.profile_icon_wave
        "cassette" -> R.string.profile_icon_cassette
        "drum" -> R.string.profile_icon_drum
        "trumpet" -> R.string.profile_icon_trumpet
        "metronome" -> R.string.profile_icon_metronome
        "radio" -> R.string.profile_icon_radio
        "moon" -> R.string.profile_icon_moon
        "sun" -> R.string.profile_icon_sun
        "bolt" -> R.string.profile_icon_bolt
        "game" -> R.string.profile_icon_game
        "film" -> R.string.profile_icon_film
        "coffee" -> R.string.profile_icon_coffee
        "train" -> R.string.profile_icon_train
        "plane" -> R.string.profile_icon_plane
        "dumbbell" -> R.string.profile_icon_dumbbell
        "book" -> R.string.profile_icon_book
        "flame" -> R.string.profile_icon_flame
        "snowflake" -> R.string.profile_icon_snowflake
        "drop" -> R.string.profile_icon_drop
        "leaf" -> R.string.profile_icon_leaf
        "star" -> R.string.profile_icon_star
        "mountain" -> R.string.profile_icon_mountain
        "heart" -> R.string.profile_icon_heart
        "cloud" -> R.string.profile_icon_cloud
        "gem" -> R.string.profile_icon_gem
        "anchor" -> R.string.profile_icon_anchor
        else -> R.string.profile_icon_headphones
    }
}
