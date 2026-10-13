# Using Sea Glass

Import `dev.mttmcknn.seaglass.*`. The library exposes a material modifier and two small wrappers. All three are composable APIs.

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

## Design-system direction

The shipped API has three layers: immutable material recipes (`SeaGlassStyle`, `SeaGlassPalette`, `SeaGlassShape`), a drawing modifier, and ready-made components. This lets an application reuse the material without adopting the entire playground UI.

The recommended next layer is an app- or screen-level `SeaGlassTheme` backed by `CompositionLocal`. **This theme API is a proposal, not implemented in the current library.** It would provide material and palette defaults to the modifier and components while keeping typography, normal controls, and application layout under the existing `MaterialTheme`.

```kotlin
// Proposed API; not yet part of the library.
MaterialTheme {
    SeaGlassTheme(
        style = SeaGlassStyle.Tumbled,
        palette = SeaGlassPalette.Mint,
    ) {
        SeaGlassButton(onClick = onCompose) { Text("Compose") }
        SeaGlassSurface { /* Your native Compose content */ }
    }
}
```

Future semantic roles could select quiet, action, raised, and selected recipes without asking application code to tune every material property. A public tumbled `Shape` helper would let custom components match the material's content clip. Piece identity should stay explicit and stable per item.

An ordinary opaque Material button does not automatically adopt the material just by receiving `Modifier.seaGlass()`: its own fill can cover the material and its clip can crop the transmitted light. The provided foundation-based `SeaGlassButton` coordinates drawing, content clipping, and interaction behavior.

Our `SeaGlassStyle` is a material recipe, distinct from Google's [experimental Compose Styles API](https://developer.android.com/develop/ui/compose/styles). As of October 2026, that API still requires modifiers for custom drawing and does not support new styleable properties such as `tumble`. A future optional adapter could use Styles for typography, padding, content color, and interaction transforms while the sea-glass modifier handles the material. See the [official fundamentals](https://developer.android.com/develop/ui/compose/styles/fundamentals) and [custom design-system guidance](https://developer.android.com/develop/ui/compose/designsystems/custom).
