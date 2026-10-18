# Sea Glass playground

The Android and desktop sample lets you try recipes and palettes on native Compose controls and local mock interfaces.

## Run the sample

Run these commands from the repository root with JDK 17. Android builds also require SDK 36 and Build Tools 36.0.0. Set `sdk.dir` in an untracked `local.properties`, or set `ANDROID_HOME`.

```sh
./gradlew :desktopApp:run
./gradlew :androidApp:assembleDebug
```

Without an Android SDK, disable the Android targets for desktop work:

```sh
./gradlew -PseaGlass.android=false :desktopApp:run
```

The build pins Kotlin 2.3.21, Compose Multiplatform 1.10.3, AGP 9.0.0, and Gradle 9.1.0. Android uses the Kotlin Multiplatform Android library plugin. The shared sample also provides an [iOS view controller](../sample/src/iosMain/kotlin/dev/mttmcknn/seaglass/sample/MainViewController.kt); iOS builds require Xcode on macOS, and native iOS rendering remains unverified.

## Explore the controls

Choose **Music player**, **Phone call**, **Email**, or **AI chat** from the scene dropdown. The sliders change their glass accents live. **Mix** gives each scene its own palette; choosing a color changes its main glass accents. These are interactive local mockups with sample content.

The **Long list** scene has 288 deterministic rows for checking lazy scrolling, revisits, and accessible actions. Use **Top** to return to the beginning and **Back** to return to the playground. See the [long-list measurements](performance/long-list-scrolling.md) for the profiling setup and its limits.

The **Material You** switch applies Material colors across the examples, while the active hang-up button keeps its semantic coral color. Android 12+ uses wallpaper colors; other hosts use the fallback Material scheme. The sample requests a light scheme for its light canvas. Turning the switch off restores the selected Sea Glass palette; choosing a palette or **Reset** exits Material You. The selection survives rotation and layout changes.

At 600 dp of available width, the playground uses two panes with independently scrolling controls. Narrower windows stack the sections. Recipes and selections survive resizing, rotating, and folding, and the layout respects system-bar insets. Palette choices expose their selected state to accessibility services.

**Preview only** hides the controls while preserving the palette, note state, and scene actions. Double-tap the background, press **Escape**, or use the **Show controls** accessibility action to return. Keyboard focus returns to the Preview only button.

See the [four sample interfaces](screenshots/app-interfaces.png), [full screenshot gallery](rendering.md#native-compose-screenshots), and [demo GIFs and capture notes](../assets/demos/README.md).

## Checks and captures

```sh
./gradlew -PseaGlass.android=false :sea-glass:jvmTest
./gradlew -PseaGlass.android=false :desktopApp:verifyPlayground
./gradlew -PseaGlass.android=false :desktopApp:verifyLongList
./gradlew -PseaGlass.android=false :desktopApp:captureScreenshots
```

The playground checks exercise slider updates, independent scrolling, resizing, keyboard actions, and preview state. Captures are written to `docs/screenshots/`. See the [rendering guide](rendering.md) for validation scope and the [performance report](performance/README.md) for existing measurements.

For an installable, non-debuggable performance build:

```sh
./gradlew :androidApp:assembleDemoRelease
```

This enables R8 optimization and resource shrinking. It uses the sample debug key so local demo builds can update each other; store-distributed apps need their own release signing configuration.

## Web playground

The original web playground is buildless. With Node.js and Python 3 installed:

```sh
cd web
npm test
python3 -m http.server 8000 --directory dist
```

Open <http://localhost:8000>. The original web source and tests live in `web/`; `tools/` generates the web reference fixtures used by the Kotlin checks.
