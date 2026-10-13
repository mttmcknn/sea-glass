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

Each surface caches texture, geometry, optical depth, ground light, and the final raster. Static controls draw a cached image after the first render. Material work runs on a background dispatcher. Rapid parameter changes cancel obsolete jobs; the previous completed image remains visible while the next one is prepared.

This initial implementation uses a portable CPU material renderer and platform bitmap upload. It is suitable for mostly static controls and occasional recipe changes. Continuous full-screen material animation needs profiling on the target device; this version does not promise 60 fps shader updates. The sample renders at at most two material pixels per dp, with a one-megapixel safety budget for oversized surfaces. Text and icons still render at the display's native resolution.

Android's adapter uses `Bitmap`, while desktop and iOS use Skia. No WebView, bundled screenshot, network service, or Android 13 runtime-shader requirement is involved.

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
