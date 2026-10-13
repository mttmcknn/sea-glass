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
