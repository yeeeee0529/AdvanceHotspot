package me.yeeeee.advancehotspot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import me.yeeeee.advancehotspot.ui.environment.EnvironmentScreen
import me.yeeeee.advancehotspot.ui.theme.AdvanceHotspotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AdvanceHotspotTheme {
                EnvironmentScreen()
            }
        }
    }
}
