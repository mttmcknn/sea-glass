# Sea Glass

[![Featured in Android Weekly #749](https://androidweekly.net/issues/issue-749/badge)](https://androidweekly.net/issues/issue-749)

Frosted color, softly tumbled edges, and a little seaside whimsy for Jetpack Compose and Compose Multiplatform. Add glass with a modifier, or use the included buttons, surfaces, styles, and themes.

<p align="center">
  <img src="assets/demos/01-introducing-sea-glass.gif" width="480" alt="Sea Glass buttons, tabs, and sample app screens in pastel glass styles" />
</p>

## Install

[Sea Glass 0.1.0](https://central.sonatype.com/artifact/dev.mttmcknn/sea-glass/0.1.0) is available on Maven Central. Add `mavenCentral()` to your repositories, then add the dependency in an existing Compose app:

```kotlin
// Android dependencies, or commonMain.dependencies in a KMP app.
implementation("dev.mttmcknn:sea-glass:0.1.0")
```

Building the checkout requires JDK 17. See [build setup](docs/playground.md#run-the-sample) for Android SDK requirements and desktop-only builds.

## Add some glass

Apply `Modifier.seaGlass()` inside a composable:

```kotlin
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.seaGlass

@Composable
fun GlassLabel() {
    val palette = SeaGlassPalette.Lagoon
    Text(
        "Hello, sea glass",
        color = palette.ink,
        modifier = Modifier
            .seaGlass(palette = palette)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    )
}
```

The modifier draws the material; your content, layout, and interactions stay Compose. Leave room around it for the glow. Existing controls may need a transparent fill and explicit content colors—see [using glass with existing controls](docs/usage.md#containers-and-existing-controls).

Use [`SeaGlassButton` or `SeaGlassSurface`](docs/usage.md) for ready-made components. Customize [recipes and palettes](docs/usage.md#recipes), share [component styles](docs/usage.md#reusable-component-styles), and set defaults with [`SeaGlassTheme`](docs/usage.md#themes). [Material You](docs/usage.md#material-you-and-material-3-colors) is supported too.

## Platforms

- **Android 7.0+ (API 24):** live rendering on Android 13+, with a background bitmap fallback on older versions.
- **Desktop/JVM** via Compose Multiplatform.
- **iOS:** ARM64 devices and Apple Silicon simulators; building requires Xcode on macOS.

The glass does not sample or distort content behind it. Check text contrast with your chosen colors. iOS rendering, older-Android fallback behavior on devices, and physical-device performance remain unverified. See [rendering notes](docs/rendering.md) for details.

## Explore

- [Run the playground](docs/playground.md) — try palettes, recipes, and sample interfaces.
- [Demos and capture notes](assets/demos/README.md) · [Screenshot gallery](docs/rendering.md#native-compose-screenshots)
- [Usage and customization](docs/usage.md) · [Release process](docs/releasing.md) · [Publishing](docs/publishing.md)

Sea Glass ports the material from the original [web playground](https://sea-glass-playground.mttmcknn.chatgpt.site), whose [source is included](web/dist). Licensed under [Apache 2.0](LICENSE).
