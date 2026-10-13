# Sea Glass

Frosted, softly tumbled glass for Jetpack Compose and Compose Multiplatform. The library ports the approved [web playground](https://sea-glass-playground.mttmcknn.chatgpt.site): seeded outlines, microscopic frost, a curved colored interior, a luminous lower edge, and soft transmitted light beneath each piece.

```kotlin
SeaGlassButton(
    onClick = { composeMessage() },
    palette = SeaGlassPalette.Lagoon,
    style = SeaGlassStyle.Reference.copy(seed = 33),
) {
    Text("Compose")
}
```

Use `SeaGlassSurface` for containers or `Modifier.seaGlass()` behind your existing controls. Text, icons, semantics, and click handling stay native Compose content. Material generation runs off the main thread and is cached per surface.

Set app-wide defaults with a theme, then override only what a particular piece needs:

```kotlin
SeaGlassTheme(style = SeaGlassStyle.Tumbled, palette = SeaGlassPalette.Mint) {
    SeaGlassButton(onClick = { composeMessage() }) { Text("Compose") }
    SeaGlassTheme(palette = SeaGlassPalette.Rose) {
        SeaGlassButton(onClick = { addMoment() }) { Text("Add") }
    }
}
```

`SeaGlassComponentStyle` bundles a recipe, palette, and shape into a reusable design-system value. Pass it to the theme, components, or modifier. [Themes and styles](docs/usage.md#themes) cover inheritance, per-piece seeds, and explicit overrides.

## Project

| Directory | Purpose |
| --- | --- |
| `sea-glass/` | Shared Kotlin material engine and Compose components; Android, iOS, and desktop targets |
| `sample/` | Shared Compose playground with recipes, palettes, sliders, and working sample controls |
| `androidApp/` | Android sample host |
| `desktopApp/` | Desktop sample host |
| `web/` | The exact approved HTML/CSS/JavaScript playground and its original tests |
| `tools/` | Reproducible web reference generation for Kotlin parity tests |

## Run

Use JDK 17. Android builds also require SDK 36 and Build Tools 36.0.0. Set `sdk.dir` in an untracked `local.properties`, or set `ANDROID_HOME`.

```sh
./gradlew :desktopApp:run
./gradlew :androidApp:assembleDebug
./gradlew :sea-glass:jvmTest
```

Without an Android SDK, pass `-PseaGlass.android=false` for desktop work:

```sh
./gradlew -PseaGlass.android=false :desktopApp:run
```

The Compose playground uses a live two-pane layout from 600 dp of available window width: components on the left, independently scrolling controls on the right. Narrower windows stack the sections. It adapts when resizing, rotating, or folding, preserves the current recipe and selections, and respects system-bar insets.

Choose **Music player**, **Phone call**, **Email**, or **AI chat** from the scene dropdown to try the material in familiar interfaces. The same sliders change their glass accents live. **Mix** gives each scene its own palette; choosing a color changes its main glass accents. These are interactive local mockups with sample content.

For screenshots, tap **Preview only** to hide the playground controls. Double-tap the background to return (or use the **Show controls** accessibility action). [Preview the four app interfaces](docs/screenshots/app-interfaces.png).

Check slider updates, independent scrolling, and the resize boundary with:

```sh
./gradlew -PseaGlass.android=false :desktopApp:verifyPlayground
```

The web demo is buildless:

```sh
cd web
npm test
python3 -m http.server 8000 --directory dist
```

## Use in an app

The current version is `0.1.0-SNAPSHOT`. To use the library from a checkout, include it as a composite build:

```kotlin
// settings.gradle.kts in your application
includeBuild("../sea-glass") {
    dependencySubstitution {
        substitute(module("dev.mttmcknn:sea-glass")).using(project(":sea-glass"))
    }
}
```

```kotlin
// Android dependencies, or commonMain.dependencies in a KMP app
implementation("dev.mttmcknn:sea-glass:0.1.0-SNAPSHOT")
```

See [the usage guide](docs/usage.md) for styles, containers, custom controls, clipping, and rendering costs. [Rendering notes](docs/rendering.md) explain how the port matches the web source and where platform rasterization can differ.

The [native Compose screenshot gallery](docs/rendering.md#native-compose-screenshots) shows the playground, component specimens, tumble/bulge combinations, and material recipes. Regenerate it with `./gradlew -PseaGlass.android=false :desktopApp:captureScreenshots`.

The build pins Kotlin 2.3.21, Compose Multiplatform 1.10.3, AGP 9.0.0, and Gradle 9.1.0. Android uses the dedicated Kotlin Multiplatform Android library plugin. iOS targets require Xcode on macOS.

## Validation

The library passes 19 Kotlin/Compose tests, including native theme inheritance, live updates, and explicit-style rendering checks. All 9 original web tests pass. Android AAR/APK packaging and desktop compilation are verified. The [native rendering comparison](docs/rendering.md) measures a maximum difference of 3/255 per color channel against the web oracle. iOS compilation and Android/iOS device rendering remain to be verified.
