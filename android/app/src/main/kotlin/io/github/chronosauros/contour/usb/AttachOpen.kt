package io.github.chronosauros.contour.usb

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * OPEN CONTOUR WHEN A DAC IS PLUGGED IN: whether Android offers to open Contour when a supported DAC is plugged in. The USB filter sits on
 * the activity alias `.UsbAttachLauncher` (AndroidManifest.xml), and this switches that alias on or off. Off = no match for the plug-in,
 * so no "Open Contour to handle ...?" pop-up; Contour opened by hand still finds the DAC and asks for access on TAP TO CONNECT.
 *
 * The state lives only in the package manager (no copy in preferences, so it can never disagree with what Android does). DEFAULT means
 * the manifest's value: on.
 */
object AttachOpen {
    /** The alias class as the manifest names it, from the namespace - the same in every build, whatever the package (perf, beta, cn). Never rename it. */
    private const val ALIAS = "io.github.chronosauros.contour.UsbAttachLauncher"

    private fun component(context: Context) = ComponentName(context.packageName, ALIAS)

    fun isOn(context: Context): Boolean = when (context.packageManager.getComponentEnabledSetting(component(context))) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> false
        else -> true
    }

    /** DONT_KILL_APP: the switch is flipped from inside the running app. */
    fun set(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        UsbLog.line("attach-open ${if (on) "on" else "off"}")
    }
}
