package io.github.iseaprojects.rivo

import android.app.Application
import io.github.iseaprojects.rivo.di.AppGraph
import io.github.iseaprojects.rivo.platform.NotificationChannels

class RivoApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        NotificationChannels.create(this)
    }
}
