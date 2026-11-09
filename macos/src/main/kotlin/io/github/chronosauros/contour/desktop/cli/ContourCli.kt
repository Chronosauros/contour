package io.github.chronosauros.contour.desktop.cli

import io.github.chronosauros.contour.core.ApoText
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.desktop.model.DesktopProfileStore
import io.github.chronosauros.contour.desktop.usb.MacDacHandle
import io.github.chronosauros.contour.desktop.usb.MacDeviceController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.hid4java.HidManager
import org.hid4java.HidServicesSpecification
import java.io.File
import kotlin.system.exitProcess

/** Command-line interface: listing devices, reading/sending EQ profiles, and adjusting hardware settings. */
object ContourCli {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun run(args: Array<String>) = runBlocking {
        if (args.isEmpty() || args[0] in listOf("help", "--help", "-h")) {
            printHelp()
            return@runBlocking
        }

        val command = args[0]
        val spec = HidServicesSpecification().apply { isAutoStart = false }
        val services = HidManager.getHidServices(spec)
        services.start()

        val controller = MacDeviceController(services)

        try {
            when (command) {
                "list" -> handleList(services)
                "info" -> handleInfo(controller)
                "read" -> handleRead(controller, args)
                "send" -> handleSend(controller, args)
                "profiles" -> handleProfiles()
                "settings" -> handleSettings(controller)
                "set-setting" -> handleSetSetting(controller, args)
                else -> {
                    println("Unknown command: $command")
                    printHelp()
                    exitProcess(1)
                }
            }
        } finally {
            controller.close()
            services.shutdown()
        }
    }

    private fun printHelp() {
        println(
            """
            Contour for macOS v1.6.5
            Ported to macOS by: nu11p0t4t0
            Original by: Chronosauros | devicePEQ (0BSD)

            Usage: contour <command> [arguments]

            Commands:
              gui                          Launch the tactile Compose Desktop GUI
              list                         List attached USB HID devices and supported DACs
              info                         Display information about the connected DAC
              read [--format json|autoeq] [--out <file>]
                                           Read active EQ from DAC memory and export
              send <profile.json|eq.txt> [--slot <n>]
                                           Send EQ profile to DAC and verify bit-for-bit
              profiles                     List all locally saved EQ profiles
              settings                     Read DAC hardware settings (FiiO, WalkPlay, KT)
              set-setting <id> <value>     Write a hardware setting to the DAC
            """.trimIndent()
        )
    }

    private fun handleList(services: org.hid4java.HidServices) {
        val devices = services.attachedHidDevices
        println("Found ${devices.size} attached HID devices:")
        var dacFound = false
        for (d in devices) {
            val handle = MacDacHandle.of(d)
            if (handle != null) {
                dacFound = true
                println(" [SUPPORTED DAC] ${handle.target.caps.name}")
                println("   VID: 0x%04x  PID: 0x%04x  Product: ${d.product ?: "N/A"}  Path: ${d.path}".format(d.vendorId, d.productId))
            }
        }
        if (!dacFound) {
            println(" No supported USB DAC currently detected.")
        }
    }

    private suspend fun handleInfo(controller: MacDeviceController) {
        val handle = controller.connectedHandle
        if (handle == null) {
            println("No supported DAC connected.")
            return
        }
        val target = handle.target
        println("Connected DAC: ${target.caps.name}")
        println("USB VID/PID: 0x%04x : 0x%04x".format(handle.device.vendorId, handle.device.productId))
        println("Protocol: ${target.family}")
        println("Bands: ${target.caps.bands}")
        println("Gain Range: ${target.caps.gainMinDb} dB to ${target.caps.gainMaxDb} dB")
        println("Frequency Range: ${target.caps.freqMinHz} Hz to ${target.caps.freqMaxHz} Hz")
        println("Supported Filter Types: ${target.caps.types.joinToString()}")

        val snapshot = controller.readCurrentDac()
        if (snapshot != null) {
            println("Firmware: ${snapshot.firmware}")
            println("Current Slot: ${snapshot.slot}")
            println("Preamp: ${snapshot.shownPreamp} dB")
        }
    }

    private suspend fun handleRead(controller: MacDeviceController, args: Array<String>) {
        val handle = controller.connectedHandle ?: run {
            println("Error: No supported DAC connected.")
            return
        }
        val snapshot = controller.readCurrentDac() ?: run {
            println("Error: Failed to read DAC state.")
            return
        }
        val imported = snapshot.importExact()
        if (imported !is DacImport.Ready) {
            println("Error: Current DAC state cannot be imported exactly: ${(imported as? DacImport.Rejected)?.issues?.joinToString()}")
            return
        }

        val now = System.currentTimeMillis()
        val profile = Profile(
            id = "dac-read",
            name = "${handle.target.caps.name} Stored",
            bands = imported.eq.bands,
            preampDb = imported.eq.preampDb,
            createdAt = now,
            updatedAt = now
        )

        var format = "autoeq"
        var outFile: String? = null
        var i = 1
        while (i < args.size) {
            if (args[i] == "--format" && i + 1 < args.size) {
                format = args[++i].lowercase()
            } else if (args[i] == "--out" && i + 1 < args.size) {
                outFile = args[++i]
            }
            i++
        }

        val outputText = when (format) {
            "json" -> json.encodeToString(Profile.serializer(), profile)
            else -> ApoText.formatProfile(profile)
        }

        if (outFile != null) {
            File(outFile).writeText(outputText)
            println("Wrote EQ to $outFile")
        } else {
            println(outputText)
        }
    }

    private suspend fun handleSend(controller: MacDeviceController, args: Array<String>) {
        if (args.size < 2) {
            println("Usage: contour send <file> [--slot <n>]")
            return
        }
        val file = File(args[1])
        if (!file.exists()) {
            println("File not found: ${file.absolutePath}")
            return
        }
        var slot: Int? = null
        if (args.size >= 4 && args[2] == "--slot") {
            slot = args[3].toIntOrNull()
        }

        val text = file.readText()
        val now = System.currentTimeMillis()
        val profile = try {
            json.decodeFromString(Profile.serializer(), text)
        } catch (e: Exception) {
            val imported = ApoText.parse(text)
            if (imported.bands.isEmpty()) {
                println("Failed to parse profile from file: not valid JSON or AutoEQ text.")
                return
            }
            Profile("imported", file.nameWithoutExtension, bands = imported.bands, preampDb = imported.preampDb, createdAt = now, updatedAt = now)
        }

        println("Sending profile \"${profile.name}\" (${profile.bands.size} bands, preamp ${profile.preampDb} dB) to DAC...")
        val res = controller.sendProfile(profile, slot) { progress ->
            print("\rProgress: ${(progress * 100).toInt()}%")
        }
        println()
        if (res != null && res.verified) {
            println("SUCCESS: Profile verified bit-for-bit in DAC memory!")
        } else {
            println("Send finished with mismatches: ${res?.mismatches?.joinToString()}")
        }
    }

    private fun handleProfiles() {
        val store = DesktopProfileStore()
        val list = store.loadAll()
        println("Locally Saved Profiles (${list.size}):")
        list.forEachIndexed { i, p ->
            println(" ${i + 1}. ${p.name} - ${p.bands.size} bands, preamp ${p.preampDb?.let { "$it dB" } ?: "Auto"}")
        }
    }

    private suspend fun handleSettings(controller: MacDeviceController) {
        val handle = controller.connectedHandle ?: run {
            println("No supported DAC connected.")
            return
        }
        val settings = controller.readDacSettings()
        if (settings.isEmpty()) {
            println("No hardware settings available for ${handle.target.caps.name}.")
            return
        }
        println("Hardware Settings for ${handle.target.caps.name}:")
        settings.forEach { s ->
            val row = handle.target.settings.firstOrNull { it.id == s.id }
            val label = row?.label ?: s.id
            val opt = row?.options?.getOrNull(s.value ?: -1) ?: s.value?.toString() ?: "N/A"
            println(" - $label: $opt")
        }
    }

    private suspend fun handleSetSetting(controller: MacDeviceController, args: Array<String>) {
        if (args.size < 3) {
            println("Usage: contour set-setting <id> <value>")
            return
        }
        val id = args[1]
        val value = args[2].toIntOrNull() ?: run {
            println("Value must be an integer.")
            return
        }
        val outcome = controller.writeSetting(id, value)
        println("Outcome: $outcome")
    }
}
