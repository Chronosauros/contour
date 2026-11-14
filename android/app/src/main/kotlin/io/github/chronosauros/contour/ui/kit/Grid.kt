package io.github.chronosauros.contour.ui.kit

/** The vertical lines of the response graph's grid, in Hz: drawn by the graph, felt by the FREQ slider and the dragged node (haptics round 2). */
internal val GRID_F = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0, 20_000.0)

/** The horizontal grid: every 6 dB inside [lo]..[hi]. */
internal fun gridDb(lo: Double, hi: Double): List<Double> = generateSequence(Math.ceil(lo / 6.0) * 6.0) { it + 6.0 }.takeWhile { it <= hi + 1e-9 }.toList()

/** The lines the graph labels (100 Hz, 1 kHz, 10 kHz): the strongest teeth. */
internal fun isDecadeLine(f: Double): Boolean = f == 100.0 || f == 1000.0 || f == 10_000.0

/** The value going from [a] to [b] arrived at [m] or went through it (also in one jump); leaving [m] does not count. */
internal fun reaches(m: Double, a: Double, b: Double): Boolean =
    if (b > a) m > a + 1e-9 && m <= b + 1e-9 else m >= b - 1e-9 && m < a - 1e-9
