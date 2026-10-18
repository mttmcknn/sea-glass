package dev.mttmcknn.seaglass.android

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.sample.SeaGlassPlayground

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The playground always draws a light canvas, even in system dark mode.
        // Older Android navigation bars need a dark scrim for their light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK),
        )
        setContent {
            // Examples have a light canvas; use its matching Material container roles.
            SeaGlassPlayground(
                materialColorScheme = rememberSeaGlassColorScheme(darkTheme = false),
                materialColorSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    "Wallpaper colors" else "Material theme colors · wallpaper colors need Android 12+",
                initialScene = intent.getStringExtra("seaglass.scene"),
                enableScrollDiagnostics = intent.getBooleanExtra("seaglass.profile", false),
            )
        }
    }
}
