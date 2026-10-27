package io.github.chronosauros.contour.usb

/**
 * The release and beta builds simulate no DAC: this stub is all they have of `--es simulate` (the perf and debug builds compile the real object from src/perf), and it never
 * returns one, whatever extra an intent carries.
 */
object DacSimulation {
    fun open(name: String): DacHandle? = null
}
