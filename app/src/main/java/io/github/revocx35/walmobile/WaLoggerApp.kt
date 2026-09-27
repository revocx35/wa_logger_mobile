package io.github.revocx35.walmobile

import android.app.Application
import io.github.revocx35.walmobile.data.AppGraph
import io.github.revocx35.walmobile.data.Diagnostics
import io.github.revocx35.walmobile.media.MediaOps

class WaLoggerApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Debug builds: log disk and network access on the main thread (scripts/e2e.py runs with this on).
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        }
        val diagnostics = Diagnostics(this).also { it.install() }
        Thread { MediaOps.cleanup(this) }.start()
        graph = AppGraph(this, diagnostics)
    }
}
