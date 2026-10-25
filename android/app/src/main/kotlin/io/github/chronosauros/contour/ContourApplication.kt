package io.github.chronosauros.contour

import android.app.Application
import io.github.chronosauros.contour.core.Proven

/**
 * Sets the one policy switch of the app before anything else runs: which writes, resets and widened limits that no unit has proven yet are on ([Proven]).
 * The "perf" (Contour Private) and "debug" builds are review builds and run them, always labelled "not verified on hardware"; "release" and "beta" run only
 * what is proven.
 */
class ContourApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Proven.channel = Proven.channelFor(BuildConfig.BUILD_TYPE)
    }
}
