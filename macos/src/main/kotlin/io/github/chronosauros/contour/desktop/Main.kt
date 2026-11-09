package io.github.chronosauros.contour.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.chronosauros.contour.desktop.cli.ContourCli
import io.github.chronosauros.contour.desktop.model.DesktopAppModel
import io.github.chronosauros.contour.desktop.model.DesktopProfileStore
import io.github.chronosauros.contour.desktop.ui.MainScreen
import io.github.chronosauros.contour.desktop.usb.MacDeviceController
import org.hid4java.HidManager
import org.hid4java.HidServicesSpecification
import java.awt.Desktop

/** macOS desktop entry point: runs CLI subcommands when arguments are given, or opens the Compose window. */
fun main(args: Array<String>) {
    // subcommands run on the CLI; bare launch or "gui" opens the window
    if (args.isNotEmpty() && args[0] != "gui") {
        ContourCli.run(args)
        return
    }

    // hid4java scans attached devices on a background thread
    val spec = HidServicesSpecification().apply { isAutoStart = false }
    val services = HidManager.getHidServices(spec)
    services.start()

    val controller = MacDeviceController(services)
    val store = DesktopProfileStore()
    val model = DesktopAppModel(store, controller)

    // native macOS application menu: About Contour pops the in-window dialog
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
        try {
            Desktop.getDesktop().setAboutHandler {
                model.showAboutDialog = true
            }
        } catch (_: Throwable) {}
    }

    application {
        Window(
            onCloseRequest = {
                controller.close()
                services.shutdown()
                exitApplication()
            },
            title = "Contour",
            state = rememberWindowState(width = 980.dp, height = 820.dp)
        ) {
            MenuBar {
                Menu("Contour") {
                    Item("About Contour") {
                        model.showAboutDialog = true
                    }
                }
            }
            MainScreen(model = model)
        }
    }
}
