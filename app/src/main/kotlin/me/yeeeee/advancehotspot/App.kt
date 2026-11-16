package me.yeeeee.advancehotspot

import android.app.Application
import me.yeeeee.advancehotspot.root.RootShell

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RootShell.configure()
    }
}
