package app.site

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import app.site.design.SiteAppTheme
import app.site.ui.SiteNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SiteApplication).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                SiteAppTheme {
                    SiteNavHost()
                }
            }
        }
    }
}
