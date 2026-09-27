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
        val diagnostics = Diagnostics(this).also { it.install() }
        MediaOps.cleanup(this)
        graph = AppGraph(this, diagnostics)
    }
}
