package net.oh4ma.advancehotspot

import android.app.Application
import net.oh4ma.advancehotspot.root.RootShell

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RootShell.configure()
    }
}
