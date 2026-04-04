package com.marinenavigator

import android.app.Application
import io.sentry.android.core.SentryAndroid
import timber.log.Timber

class MarineNavigatorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        // ── Sentry ────────────────────────────────────────────
        // Para configurar:
        //  1. Crea un proyecto Android en https://sentry.io
        //  2. Copia el DSN desde Settings → Projects → Client Keys
        //  3. Sustituye el valor de io.sentry.dsn en AndroidManifest.xml
        SentryAndroid.init(this) { options ->
            options.isDebug = BuildConfig.DEBUG
            // DSN leído desde el Manifest (<meta-data android:name="io.sentry.dsn" ...>)
            // No hace falta repetirlo aquí salvo que quieras sobreescribirlo
        }
    }
}
