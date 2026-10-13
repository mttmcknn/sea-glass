package dev.mttmcknn.seaglass.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassStyle
import dev.mttmcknn.seaglass.SeaGlassTheme
import dev.mttmcknn.seaglass.sample.SeaGlassScene
import dev.mttmcknn.seaglass.sample.SeaGlassShowcase

private val SceneInk = Color(0xFF283E37)
private val SceneMuted = Color(0xFF65796F)

/** Four native app examples share the reference material and vary only their palette. */
@Composable
fun AppScenesGallery() {
    MaterialTheme(colorScheme = lightColorScheme(primary = SceneInk, onSurface = SceneInk)) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFFF4F5F0))
                .padding(horizontal = 64.dp, vertical = 36.dp),
        ) {
            Text("SEA GLASS  /  IN EVERYDAY INTERFACES", fontSize = 10.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium, color = SceneMuted)
            Spacer(Modifier.height(12.dp))
            Text("One material. Four different moments.", fontFamily = FontFamily.Serif,
                fontSize = 33.sp, color = SceneInk)
            Spacer(Modifier.height(10.dp))
            Text("Native Compose scenes · Reference recipe · four pigments", fontSize = 13.sp, color = SceneMuted)
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppSceneCard("01 / MUSIC", "Mint", SeaGlassScene.Music, SeaGlassPalette.Mint)
                AppSceneCard("02 / PHONE", "Rose", SeaGlassScene.Phone, SeaGlassPalette.Rose)
            }
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppSceneCard("03 / EMAIL", "Lagoon", SeaGlassScene.Email, SeaGlassPalette.Lagoon)
                AppSceneCard("04 / AI CHAT", "Lilac", SeaGlassScene.Chat, SeaGlassPalette.Lilac)
            }
        }
    }
}

@Composable
private fun AppSceneCard(title: String, pigment: String, scene: SeaGlassScene, palette: SeaGlassPalette) {
    Column(Modifier.width(400.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium, color = SceneMuted)
            Text(pigment, fontSize = 11.sp, color = SceneMuted)
        }
        Spacer(Modifier.height(10.dp))
        val paper = if (scene == SeaGlassScene.Music || scene == SeaGlassScene.Phone) Color(0xFFFAF8F1) else Color(0xFFFCFCF8)
        Box(Modifier.size(400.dp, 820.dp).clip(RoundedCornerShape(24.dp)).background(paper)
            .border(1.dp, Color(0xFFE1E7DF), RoundedCornerShape(24.dp))) {
            SeaGlassTheme(style = SeaGlassStyle.Reference, palette = palette) {
                SeaGlassShowcase(scene = scene, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
