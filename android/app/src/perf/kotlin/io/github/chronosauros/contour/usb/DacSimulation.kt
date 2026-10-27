package io.github.chronosauros.contour.usb

import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.usb.sim.SimFiio
import io.github.chronosauros.contour.usb.sim.SimIo
import io.github.chronosauros.contour.usb.sim.SimKtMicro
import io.github.chronosauros.contour.usb.sim.SimMoondrop
import io.github.chronosauros.contour.usb.sim.SimWalkPlay

/**
 * The simulated DACs of the review builds (perf and debug; release and beta compile a stub of this object that makes none): `--es simulate <dac>` makes the app behave as if that DAC
 * were attached and connected, with no USB involved (see MainActivity and [DeviceController.simulate]). Each unit is a fake DAC speaking the real protocol, so the real sessions, the
 * real read and the real send run on it; its registers, slots and settings stay in memory for the process, so a read after a send shows what the send wrote, until the app is
 * force-stopped. Names: micro, max, trn, ka15, k13, melody, tinya, dawn, allegro, echo.
 */
object DacSimulation {
    private val units = HashMap<String, NativePort>()

    /** The simulated DAC [name], or null for a name this build does not simulate. Opening it again gives the same DAC with what it holds now. */
    fun open(name: String): DacHandle? {
        val target = when (name) {
            "micro" -> DeviceTarget.MICRO
            "max" -> DeviceTarget.MAX
            "trn" -> DeviceTarget.TRN
            "ka15" -> DeviceTarget.KA15
            "k13" -> DeviceTarget.K13
            "melody" -> DeviceTarget.MELODY
            "tinya" -> DeviceTarget.TINY_A
            "dawn" -> DeviceTarget.DAWN_PRO_2
            "allegro" -> DeviceTarget.ALLEGRO_MINI
            "echo" -> DeviceTarget.ECHO_A
            else -> return null
        }
        val dac = synchronized(units) {
            units.getOrPut(name) {
                when {
                    target.fiio != null -> SimFiio(target)
                    target.moondrop != null -> SimMoondrop()
                    target.ktMicro != null -> SimKtMicro()
                    else -> SimWalkPlay(requireNotNull(target.walkplayProfile))
                }
            }
        }
        return DacHandle(null, target, "simulated:$name") { guard -> SimIo(target, dac, guard) }
    }
}
