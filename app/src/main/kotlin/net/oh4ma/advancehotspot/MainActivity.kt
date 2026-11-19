package net.oh4ma.advancehotspot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.oh4ma.advancehotspot.routing.RoutingRuntime
import net.oh4ma.advancehotspot.ui.MainScreen
import net.oh4ma.advancehotspot.ui.theme.AdvanceHotspotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // app 更新或行程被終止後服務不會自己回來；開啟畫面時就恢復，不必等使用者切到裝置分頁
        RoutingRuntime.sync(this)
        setContent {
            AdvanceHotspotTheme {
                MainScreen()
            }
        }
    }
}
