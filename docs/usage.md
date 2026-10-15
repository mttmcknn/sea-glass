# Using Sea Glass

Import `dev.mttmcknn.seaglass.*`. The library exposes immutable material/component styles, an inheritable theme, a material modifier, and two small component wrappers.

## Buttons

```kotlin
SeaGlassButton(
    onClick = onCompose,
    modifier = Modifier.width(270.dp).height(88.dp),
    style = SeaGlassStyle.Reference.copy(seed = 33),
    palette = SeaGlassPalette.Lagoon,
) {
    Icon(Icons.Outlined.Edit, contentDescription = null)
    Spacer(Modifier.width(16.dp))
    Text("Compose")
}
```

The wrapper provides the palette's foreground color through Material 3 `LocalContentColor`. It has button semantics, a minimum 48.dp target, a disabled state, a keyboard focus outline, and a 2.dp press offset. Content remains normal Compose text and vectors; it is never baked into a bitmap.

## Containers and existing controls

```kotlin
SeaGlassSurface(
    modifier = Modifier.size(80.dp),
    shape = SeaGlassShape.Disc,
    palette = SeaGlassPalette.Mint,
) {
    Icon(Icons.Outlined.Add, contentDescription = "Add", modifier = Modifier.align(Alignment.Center))
}

Box(
    Modifier
        .size(240.dp, 72.dp)
        .seaGlass(style = recipe, palette = SeaGlassPalette.Lilac)
) {
    // Your layout, gestures, focus handling, and semantics.
}
```

`SeaGlassSurface` is a non-interactive container. The modifier adds only drawing; it does not set content color, semantics, padding, or interaction behavior.

Reuse `SeaGlassStyle` and `SeaGlassComponentStyle` values across controls, but call `Modifier.seaGlass()` separately for each layout. Each call owns the renderer and ground-light layers for one piece; sharing the resulting modifier instance across multiple layouts would also share those mutable drawing resources.

## Recipes

`SeaGlassStyle.Reference`, `.Tumbled`, and `.Quiet` match the web presets. Copy a recipe to adjust it:

```kotlin
val recipe = SeaGlassStyle.Reference.copy(
    frost = .55f,
    grain = .48f,
    tumble = .025f,
    thickness = 6.dp,
    bulge = .65f,
    elevation = 8.dp,
    lightDegrees = 315f,
    transmission = .58f,
    seed = 42,
)
```

Keep each piece's seed stable across recompositions. Changing light or color then preserves its contour and frost pattern. Use a different seed for each neighboring piece. Shapes are `Rounded`, `Capsule`, and `Disc`; the last becomes an ellipse when its layout is not square.

Custom palettes use opaque sRGB pigment values for `body` and `edge`; their `Color` alpha is ignored because the material model produces translucency. The foreground `ink` color keeps its alpha.

Lengths map one web CSS pixel to one dp. `tumble` is a fraction of the shorter dimension. The renderer clamps frost, grain, bulge, and transmission to 0–1; tumble to 0–0.065; thickness to 0–14.dp; and elevation to 0–18.dp. The surface-light angle moves reflections. Cast shadow and transmitted ground light always point downward.

## Layout and rendering costs

Leave space beneath the control for its glow. The material draws into a transparent 48.dp gutter outside its layout size. A clipping parent, including some scrolling containers, can crop that gutter. Put layout padding around a control when needed; do not add a shadow modifier to simulate the material's existing shadow.

On **Android 13+ and Skia**, `LiveMaterialRenderer` draws the complete material during the first draw. The body uses a runtime shader; the three ground-light layers use native blur. There is no asynchronous flat-color placeholder or later completed-image swap on this path.

Each surface caches its geometry and tumbled contour. Size changes draw a complete material immediately. Shape, tumble and seed edits reuse cached geometry when available; otherwise a worker prepares the update while the previous complete shape stays visible. Rapid edits keep the active update and the newest pending request. Once editing stops, the final requested geometry is displayed. Body, seeded grain, content clipping and focus outline change together. Leaving composition cancels pending work.

Frost, grain, bulge, thickness, transmission, light angle and pigment changes update their affected shader uniforms immediately; affected ground-light layers are re-recorded separately. Elevation changes only the ground light. Foreground ink and ignored pigment alpha do not rewrite body uniforms. A density change that preserves logical size updates the native contour and density uniform without regenerating or uploading geometry.

The smooth field is sampled at up to one sample per dp and reduced toward a 262,144-sample budget for oversized surfaces. This is a geometry cache, not the final rendered image: the silhouette and procedural grain draw at display resolution. The shader's grain pattern differs from the CPU reference, with measured mean body color differences around 0.68–2.00/255 across four fixtures. See [rendering notes](rendering.md) for the visual comparison and its limits.

On **Android 7–12L**, the compatibility path renders on a background dispatcher and uploads a bitmap. It retains one cached texture, geometry field, optical field, shaded body, ground-light image and final raster, using four running-sum box filters to approximate the ground Gaussian. During rapid changes it finishes the active render, then takes the latest pending recipe. Intermediate requests are coalesced and the previous image stays visible; leaving composition cancels the worker. This path is capped at two material pixels per dp and a one-megapixel material budget. Native text and icons remain independent of that cap.

The backend is selected automatically; the component, modifier and theme APIs are identical. Android 13 is required only for the live shader path, not for using the library. The implementation uses native graphics, with no WebView, bundled screenshot or network service.

The [performance report](performance/README.md) retains CPU reference benchmarks and interaction measurements. Exact CPU pixel checks do not establish exact shader parity or Android frame rates. The playground's `demoRelease` build enables R8 and resource shrinking for device testing. Large surfaces and continuous animation still need measurement on the intended device.

## How the controls interact

| Controls | What changes |
| --- | --- |
| Tumble + seed | Tumble increases smooth outline variation; seed selects the piece's contour and abrasion pattern. Keep the seed fixed to compare settings. |
| Bulge + thickness | Bulge rounds the interior profile and its shoulders. Thickness also increases apparent surface rise and pigment depth. Neither changes the component's measured layout size. |
| Frost + grain | Frost makes the pigment cloudier and reflections broader. Grain adds microscopic pits and perturbs surface normals; frost increases parts of that grain response. |
| Transmission + thickness | Transmission strengthens the luminous lower turn and tinted light beneath. Thickness slightly reduces the light-exit contribution while shifting ground light downward. |
| Transmission + elevation | Transmission favors tinted ground light over neutral shadow. Elevation shifts and softens those downward layers. |
| Light angle + bulge | Reflections move across the curved surface normals. Cast shadow and lower light exit retain their downward direction. |

See the [native screenshot gallery](rendering.md#native-compose-screenshots) for controlled comparisons. The material is an art-directed optical approximation; it does not sample or distort the app content behind it.

## Themes

`SeaGlassTheme` supplies defaults to `SeaGlassButton`, `SeaGlassSurface`, and `Modifier.seaGlass()` throughout a Compose subtree. It works alongside `MaterialTheme`, leaving typography, ordinary control colors, layout, and spacing to the application.

```kotlin
MaterialTheme {
    SeaGlassTheme(
        style = SeaGlassStyle.Tumbled,
        palette = SeaGlassPalette.Mint,
    ) {
        SeaGlassButton(onClick = onCompose) { Text("Compose") }
        SeaGlassSurface(Modifier.size(120.dp, 72.dp)) { Text("Surface") }
        Box(Modifier.size(120.dp, 72.dp).seaGlass())

        SeaGlassTheme(palette = SeaGlassPalette.Rose) {
            // Keeps the parent's Tumbled recipe and shape, changes the pigment.
            SeaGlassButton(onClick = onAdd) { Text("Add") }
        }
    }
}
```

Read the current values through `SeaGlassTheme.style` (the material recipe), `.palette`, `.shape`, or `.componentStyle` (all three). A nested theme inherits every omitted field. Changing a provided value updates its consumers. Outside any provider, the original Reference recipe, Lagoon palette, and Rounded shape apply.

The provider itself adds no drawing, spacing, semantics, or foreground-color override. `SeaGlassSurface` and `SeaGlassButton` provide ink to their content as before. A material modifier is composable and reads its theme where it is created; create it inside the intended theme scope rather than caching it outside that scope.

## Material You and Material 3 colors

`SeaGlassMaterialTheme` derives a palette from the surrounding Material 3 `MaterialTheme`. It works with your app's custom colors and light/dark schemes on every platform. On Android 12 and later, it also works with wallpaper colors supplied through a dynamic scheme.

```kotlin
MaterialTheme(colorScheme = appColorScheme) {
    SeaGlassMaterialTheme(style = SeaGlassStyle.Tumbled) {
        SeaGlassButton(onClick = onCompose) { Text("Compose") }

        SeaGlassMaterialTheme(role = SeaGlassColorRole.Tertiary) {
            // Keeps the parent's Tumbled recipe and shape, changes the color role.
            SeaGlassButton(onClick = onAdd) { Text("Add") }
        }
    }
}
```

`SeaGlassColorRole.Primary` is the default. `Primary`, `Secondary`, and `Tertiary` map their Material container color to `body`, blend that container 35% toward white for `edge`, and use the matching on-container color for `ink`. The renderer still applies its optical effects to those pigments; the result is not a flat Material container, so check text contrast against the rendered surface in your app.

Omitted `style` and `shape` values inherit the enclosing sea-glass theme. Nested `SeaGlassTheme` palette overrides and explicit component palettes continue to work. The adapter leaves Material typography and ordinary component colors unchanged.

For a single component, use `SeaGlassPalette.materialTheme(role)` in composition. For a resolved palette outside composition, use `SeaGlassPalette.fromColorScheme(colorScheme, role)`. The composable factory retains the same palette instance until the selected container or foreground changes; unrelated recompositions and changes to other Material colors do not recreate it. Palette changes use the renderer's existing pigment update path and preserve cached geometry when the piece's size, shape, tumble, and seed stay the same.

Android applications can use the optional scheme helper:

```kotlin
val colors = rememberSeaGlassColorScheme(
    dynamicColor = useMaterialYou,
    darkTheme = isSystemInDarkTheme(),
    lightFallback = appLightColorScheme,
    darkFallback = appDarkColorScheme,
)
MaterialTheme(colorScheme = colors) {
    SeaGlassMaterialTheme {
        SeaGlassButton(onClick = onCompose) { Text("Compose") }
    }
}
```

`rememberSeaGlassColorScheme` is Android-only. When dynamic color is enabled on API 31+, it uses Material 3's dynamic light/dark schemes and remembers the result by context, resource configuration, and dark mode. On older Android versions, or with dynamic color disabled, it returns the chosen fallback unchanged. The default fallbacks are Material 3's standard light/dark schemes. This API 31 requirement is separate from the API 33 requirement for the live shader renderer: Android 12 can use wallpaper colors with the compatibility renderer. See the [official dynamic-color guidance](https://developer.android.com/develop/ui/compose/designsystems/material3#dynamic_color_schemes).

## Reusable component styles

`SeaGlassStyle` describes the optical recipe. `SeaGlassComponentStyle` combines that recipe with a palette and shape, so a design-system role can be declared once:

```kotlin
val PrimaryGlass = SeaGlassComponentStyle(
    material = SeaGlassStyle.Tumbled,
    palette = SeaGlassPalette.Mint,
    shape = SeaGlassShape.Rounded,
)

SeaGlassTheme(style = PrimaryGlass) {
    SeaGlassButton(onClick = onCompose) { Text("Compose") }
}

// The same style also works directly on components and the drawing modifier.
SeaGlassButton(onClick = onSave, style = PrimaryGlass.withSeed(73)) {
    Text("Save")
}
SeaGlassSurface(Modifier.size(96.dp), style = PrimaryGlass, shape = SeaGlassShape.Disc) {}
Box(Modifier.size(240.dp, 88.dp).seaGlass(PrimaryGlass))
```

A complete component style replaces all three defaults; it is not a sparse merge with its enclosing theme. Component calls can still override its `palette` or `shape` explicitly. To vary the recipe, copy `material`; `withSeed()` is a convenience for changing only the stable piece identity.

| Setting source | Resolution |
| --- | --- |
| Explicit component recipe/palette/shape | Wins for the specified field; omitted fields read the nearest theme |
| Complete component style | Supplies its material, palette, and shape; explicit palette/shape arguments still win |
| Nested theme with individual fields | Inherits omitted fields from the parent |
| Theme with a complete component style | Replaces the material, palette, and shape together |
| No theme | Reference / Lagoon / Rounded, with the existing seed 42 |

The original recipe-based component and modifier signatures remain available. No automatic seed generation is introduced: keep a seed stable for each item, including in lazy lists. Sharing a style without changing its seed intentionally produces repeated pieces. Styles use normal immutable data-class `copy()` operations rather than an implicit cascade of nullable fields.

See the [theme/style screenshot](screenshots/theme-styles.png) for inherited defaults, a nested recipe/palette override, and a complete component-style override rendered by the library.

An ordinary opaque Material button does not automatically adopt the material just by receiving `Modifier.seaGlass()`: its own fill can cover the material and its clip can crop the transmitted light. The provided foundation-based `SeaGlassButton` coordinates drawing, content clipping, and interaction behavior.

Our `SeaGlassStyle` is a material recipe, distinct from Google's [experimental Compose Styles API](https://developer.android.com/develop/ui/compose/styles). As of October 2026, that API still requires modifiers for custom drawing and does not support new styleable properties such as `tumble`. A future optional adapter could use Styles for typography, padding, content color, and interaction transforms while the sea-glass modifier handles the material. See the [official fundamentals](https://developer.android.com/develop/ui/compose/styles/fundamentals) and [custom design-system guidance](https://developer.android.com/develop/ui/compose/designsystems/custom).
