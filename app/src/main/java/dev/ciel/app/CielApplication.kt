package dev.ciel.app

import android.app.Application
import dev.ciel.app.platform.Notifications

class CielApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        instance = AppGraph(this)
    }

    companion object {
        @Volatile private var instance: AppGraph? = null
        val graph: AppGraph get() = instance ?: error("Ciel is not initialised")
        val graphOrNull: AppGraph? get() = instance
    }
}
