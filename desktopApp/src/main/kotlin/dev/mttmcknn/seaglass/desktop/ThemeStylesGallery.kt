package dev.mttmcknn.seaglass.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mttmcknn.seaglass.SeaGlassButton
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassStyle
import dev.mttmcknn.seaglass.SeaGlassSurface
import dev.mttmcknn.seaglass.SeaGlassTheme
import dev.mttmcknn.seaglass.seaGlass

private val ThemeInk = Color(0xFF283E37)
private val ThemeMuted = Color(0xFF65796F)

/** A real native capture of inherited defaults, nested themes, and reusable component styles. */
@Composable
fun ThemeStylesGallery() {
    MaterialTheme(colorScheme = lightColorScheme(primary = ThemeInk)) {
        SeaGlassTheme(style = SeaGlassStyle.Tumbled, palette = SeaGlassPalette.Mint) {
            // A named role can derive from the theme, then be reused by any glass component.
            val actionStyle = SeaGlassTheme.componentStyle.copy(
                palette = SeaGlassPalette.Amber,
                shape = SeaGlassShape.Capsule,
            ).withSeed(73)

            Column(Modifier.fillMaxSize().background(Color(0xFFF5F6F1)).padding(48.dp)) {
                ThemeEyebrow("04 / THEME AND REUSABLE STYLES")
                Spacer(Modifier.height(16.dp))
                Text("One material language. Room for character.", color = ThemeInk,
                    fontFamily = FontFamily.Serif, fontSize = 38.sp)
                Spacer(Modifier.height(10.dp))
                Text("Subtree defaults, a nested variation, and a named style — all rendered by the same composables.",
                    color = ThemeMuted, fontSize = 17.sp)
                Spacer(Modifier.height(32.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ThemeCard(Modifier.weight(1f).height(420.dp)) {
                        ThemeEyebrow("01 / INHERIT")
                        ThemeCardTitle("The app theme", "Tumbled · Mint · Rounded")
                        Spacer(Modifier.height(35.dp))
                        SeaGlassSurface(Modifier.size(324.dp, 90.dp).align(Alignment.CenterHorizontally)) {
                            Text("Shared surface", fontSize = 21.sp)
                        }
                        Spacer(Modifier.height(42.dp))
                        SeaGlassButton(onClick = {}, modifier = Modifier.size(260.dp, 72.dp).align(Alignment.CenterHorizontally)) {
                            Text("Save a moment", fontSize = 19.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.weight(1f))
                        ThemeCode("SeaGlassButton(onClick = …)")
                    }
                    ThemeCard(Modifier.weight(1f).height(420.dp)) {
                        ThemeEyebrow("02 / NEST")
                        ThemeCardTitle("A quieter section", "Quiet · Lilac · inherited shape")
                        SeaGlassTheme(style = SeaGlassStyle.Quiet, palette = SeaGlassPalette.Lilac) {
                            Spacer(Modifier.height(35.dp))
                            SeaGlassSurface(Modifier.size(324.dp, 90.dp).align(Alignment.CenterHorizontally)) {
                                Text("A softer surface", fontSize = 21.sp)
                            }
                            Spacer(Modifier.height(42.dp))
                            SeaGlassButton(onClick = {}, modifier = Modifier.size(260.dp, 72.dp).align(Alignment.CenterHorizontally)) {
                                Text("Keep this note", fontSize = 19.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        ThemeCode("SeaGlassTheme(style = Quiet, …)")
                    }
                    ThemeCard(Modifier.weight(1f).height(420.dp)) {
                        ThemeEyebrow("03 / REUSE")
                        ThemeCardTitle("An action style", "Tumbled · Amber · Capsule · seed 73")
                        Spacer(Modifier.height(35.dp))
                        SeaGlassSurface(Modifier.size(324.dp, 90.dp).align(Alignment.CenterHorizontally), style = actionStyle) {
                            Text("A named appearance", fontSize = 21.sp)
                        }
                        Spacer(Modifier.height(42.dp))
                        SeaGlassButton(onClick = {}, style = actionStyle,
                            modifier = Modifier.size(260.dp, 72.dp).align(Alignment.CenterHorizontally)) {
                            Text("Compose", fontSize = 19.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.weight(1f))
                        ThemeCode("SeaGlassButton(style = action, …)")
                    }
                }

                Spacer(Modifier.height(24.dp))
                ThemeCard(Modifier.fillMaxWidth().height(222.dp)) {
                    ThemeEyebrow("04 / BRING YOUR OWN LAYOUT")
                    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(400.dp, 96.dp).seaGlass(actionStyle), contentAlignment = Alignment.Center) {
                            Text("Your own Compose content", color = actionStyle.palette.ink,
                                fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.width(58.dp))
                        Column {
                            Text("The same style works as a modifier.", color = ThemeInk, fontSize = 22.sp,
                                fontFamily = FontFamily.Serif)
                            Spacer(Modifier.height(12.dp))
                            ThemeCode("Modifier.seaGlass(actionStyle)")
                            Spacer(Modifier.height(12.dp))
                            Text("MaterialTheme keeps typography and ordinary controls.\nSeaGlassTheme supplies the glass material, pigment, and shape.",
                                color = ThemeMuted, fontSize = 15.sp, lineHeight = 23.sp)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Explicit component choices → reusable style → nearest theme → original defaults",
                    color = ThemeMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ThemeCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(Color.White, RoundedCornerShape(20.dp))
        .border(1.dp, Color(0xFFE1E7DF), RoundedCornerShape(20.dp)).padding(24.dp), content = content)
}

@Composable
private fun ThemeCardTitle(title: String, detail: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, color = ThemeInk, fontSize = 27.sp, fontFamily = FontFamily.Serif)
    Spacer(Modifier.height(8.dp))
    Text(detail, color = ThemeMuted, fontSize = 12.sp)
}

@Composable
private fun ThemeEyebrow(value: String) {
    Text(value, color = ThemeMuted, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun ThemeCode(value: String) {
    Text(value, color = ThemeInk, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}
