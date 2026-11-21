package dev.pocketlab

import android.app.Application
import dev.pocketlab.data.Lab
import dev.pocketlab.data.SecureStore
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class PocketlabApp : Application() {
    lateinit var lab: Lab
        private set

    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider; sshj needs the full one (Ed25519, curve25519, etc.).
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
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
