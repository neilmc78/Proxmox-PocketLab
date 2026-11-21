package dev.pocketlab

import android.app.Application
import dev.pocketlab.data.Lab
import dev.pocketlab.data.SecureStore
import dev.pocketlab.ssh.SlimCryptoProvider

class PocketlabApp : Application() {
    lateinit var lab: Lab
        private set

    override fun onCreate() {
        super.onCreate()
        SlimCryptoProvider.install()
        lab = labOverride ?: Lab(SecureStore(this))
        instance = this
    }

    companion object {
        lateinit var instance: PocketlabApp
            private set
        /** Tests swap in a Lab with an in-memory key; the app never sets this. */
        @Volatile var labOverride: Lab? = null
        val lab get() = labOverride ?: instance.lab
    }
}
