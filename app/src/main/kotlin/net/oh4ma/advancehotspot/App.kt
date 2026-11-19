package net.oh4ma.advancehotspot

import android.app.Application
import net.oh4ma.advancehotspot.privilege.PrivilegeSettings
import net.oh4ma.advancehotspot.privilege.ShizukuClient
import net.oh4ma.advancehotspot.root.RootShell

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RootShell.configure()
        PrivilegeSettings.init(this)
        ShizukuClient.init(this)
    }
}
