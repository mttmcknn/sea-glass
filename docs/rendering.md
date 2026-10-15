# Material rendering and parity

The visual source of truth is `web/dist/sea-glass.mjs`, copied from approved playground commit `7ce166bf9412b8be6ec48d4acf3d0e2e5fa01efa`. The web source is retained unchanged.

## Default live renderer

`LiveMaterialRenderer` is the default on Android 13+ and Skia. It draws the complete material during the first draw, using Android `RuntimeShader` or Skia `RuntimeEffect` for the body and native graphics-layer blur for the cast shadow, contact shadow and transmitted light. A hardware-accelerated window can execute these through its GPU backend; the headless JVM checks use software Skia and are not GPU performance measurements.

The renderer retains the tumbled vector contour and a small smooth geometry field. First paint and logical-size changes produce a complete material synchronously. Subsequent shape, tumble and seed edits reuse ready geometry or prepare it on a worker; the last complete shape remains visible meanwhile. Lighting, pigment and other optical controls update their affected uniforms immediately. Relevant ground layers are re-recorded when their parameters change; the shadow direction stays fixed downward. The live path has no flat placeholder or completed material-image swap.

Each surface has one composition-owned geometry consumer and one conflated pending request. A shared dispatcher permits two concurrent geometry builds across surfaces. The worker creates its own mask bitmap/canvas and cloud cache, then publishes immutable geometry. Native shader uploads, paths and graphics layers stay on the drawing thread. Completion state is read only during drawing, so delivery invalidates drawing without feeding measured size back into composition.

Revision tracking prevents an older worker result from replacing a newer synchronous/cache adoption. The displayed geometry controls the body inputs, grain seed, ground contour, content clipping and focus border together. A density change with unchanged logical dimensions rebuilds only the native path and density uniform. CPU loops check cancellation and retain the original Float quantization and accumulation order.

Only smooth shape information is sampled, at up to one sample per dp and reduced toward a 262,144-sample budget for oversized surfaces. The shader evaluates microscopic grain at display resolution, independent of that geometry sampling. Its procedural grain uses a different pattern from the original CPU implementation. The vector silhouette also renders at display resolution.

Android 7–12L automatically uses a CPU compatibility renderer. It runs off the main thread, keeps one active request plus the latest pending request, and approximates ground blur with four running-sum box filters. An initial flat fill or the previous completed image remains visible while rendering. The exact Gaussian CPU implementation remains available internally as the reference and benchmark oracle; it is not the modern platform's default renderer.

## Shared material model

1. Mulberry32 creates a stable sequence with JavaScript-compatible 32-bit overflow and unsigned shifts.
2. A seeded contour is smoothed and fitted to the component bounds. A periodic cubic B-spline gives continuous position, tangent, and curvature through the closing seam.
3. A rasterized silhouette feeds the same Euclidean distance transform and curved height field. The silhouette remains crisp while the interior crest is smoothed before differentiation.
4. Microscopic noise stays in material coordinates. Surface normals and pigment absorption create a denser middle, broad upper reflection, and tinted lower light exit. The live shader evaluates these per fragment rather than caching a complete shaded bitmap.
5. Fixed downward cast/contact layers and a colored transmitted-light pool are composited under the body. A grazing catch follows the lower geometric normals. Grain perturbs surface reflections without destabilizing that catch.

The reference CPU kernel uses `Double` calculations with the original `Float32` array boundaries preserved as Kotlin `FloatArray` fields. Its geometry, random sequences, noise and body shading are compared with fixtures generated from the JavaScript implementation. The live path retains the form and optical behavior using interpolated shape fields, shader arithmetic and new procedural grain.

## Exactness and platform differences

![Web and native Compose rendering side by side](compose-comparison.png)

The checked-in comparison above was captured with the **exact CPU reference path**, including the Compose/Skia mask, bitmap upload and Canvas drawing. Its complete JVM comparison differs by at most 3/255 per channel, with an RMS difference of 0.72 over non-white channels. With a shared silhouette mask, the twelve reference body-shading fixtures are byte-identical to the JavaScript oracle; complete ground-and-body fixtures have RMS differences below 0.28/255. The CPU hill climb preserves all 336 measured pixel checksums. **None of these exactness claims apply to the default live shader.**

The live body is compared separately against the CPU reference over white at 2× density. Across the four current fixtures, its mean absolute RGB error is approximately **0.68–2.00 levels out of 255**. The grain positions differ, so this is a mean difference, not a maximum pixel bound:

| Fixture | Mean absolute difference | P95 difference | Maximum difference |
| --- | ---: | ---: | ---: |
| Lagoon rounded | 1.38/255 | 4/255 | 14/255 |
| Mint disc | 1.18/255 | 4/255 | 16/255 |
| Lilac capsule | 2.00/255 | 6/255 | 18/255 |
| Amber portrait | 0.68/255 | 2/255 | 4/255 |

These measurements cover the body, not the full component and native ground layers. Native blur, rasterization, numeric texture precision and display density can introduce additional platform differences. The fallback's four-box blur is also measured separately: the twelve ground cases remain below 0.18/255 RMS and 1.21/255 maximum channel difference over white relative to exact Gaussian ground.

The palettes, stable piece identity, downward light exit and ground-layer order remain consistent. Pixels are not universally identical across browser Canvas, Android Canvas and Skia. The compatibility renderer retains the original 2× material sampling cap and one-megapixel budget; the live shader's final body, grain and silhouette render at display resolution. Native text and icons are independent of either material path.

## Reproduce checks

```sh
cd tools
npm ci
npm run generate-reference
cd ..
./gradlew -PseaGlass.android=false :sea-glass:jvmTest
cd web
npm test
```

The oracle appends a temporary debug export to an in-memory copy of the JavaScript module; it does not alter the approved source. Fixtures include a source hash, random/contour samples, masks, intermediate fields, shaded pixels and complete images. Tests inject the recorded mask to distinguish CPU numerical parity from platform rasterization. Complete reference renders exercise the Compose mask rasterizer and pixel upload separately.

Shader tests compile the actual native program, validate its numeric texture inputs and compare its body output against the reference. `LiveMaterialBodyTest` writes reference/live images and metrics into `sea-glass/build/reports/sea-glass-live/`. The [verification workflow](../.github/workflows/ci.yml) runs JVM and iOS simulator logic tests, playground interaction checks, and the Android `demoRelease` build, including R8 optimization and vital lint. iOS targets and a sample view controller are included; native iOS rendering and device screenshot comparisons remain unverified. An Android API 36 emulator smoke check confirms live wallpaper recoloring, rotation persistence, and light-canvas system-bar legibility in system dark mode. JVM image checks do not establish Android/iOS pixel identity, and emulator smoke checks do not establish physical-device performance.

Pinned version references: [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html), [KMP Android library plugin](https://developer.android.com/kotlin/multiplatform/plugin), and [Compose graphics](https://developer.android.com/develop/ui/compose/graphics/draw/overview).

## Native Compose screenshots

These images capture the actual Compose hierarchy with the desktop Skia renderer at 2× density, including native text and icons. They are not HTML recreations or Android emulator captures. The narrow capture shows the same responsive shared playground at a phone-like width. The tablet and four-app-interface images below have been regenerated with the final live shader; the remaining galleries retain their earlier CPU captures until regenerated. The capture's settling wait is retained for layout and interaction state, though the live material itself draws completely on its first draw.

![Native Compose playground](screenshots/playground-desktop.png)

[Narrow playground layout](screenshots/playground-narrow.png)

![Tablet playground with live preview and independent controls](screenshots/playground-tablet.png)

The shared playground switches to two panes at 600 dp of available width. The preview stays visible while scrolling the controls; settings survive resizing and Android activity recreation. These captures exercise [700 dp unfolded windows](screenshots/playground-foldable.png), [the 600 dp split-window boundary](screenshots/playground-split.png), and [a compact 420 dp window](screenshots/playground-compact.png).

![Composable specimens](screenshots/composable-specimens.png)

![Tumble and bulge comparison](screenshots/tumble-bulge.png)

![Material recipes and moving reflections](screenshots/material-recipes.png)

![Theme inheritance and reusable component styles](screenshots/theme-styles.png)

![Sea glass in music, phone, email, and AI chat interfaces](screenshots/app-interfaces.png)

These interactive mock interfaces use ordinary Compose layouts with sea glass reserved for playback controls, call controls, the inbox toolbar and compose action, and chat suggestions/send. Select them in the playground's scene dropdown; the active material recipe applies to their accents. `SeaGlassShowcase(scene)` also renders any example without playground chrome for native galleries and screenshots.

Recreate all eleven images with JDK 17:

```sh
./gradlew -PseaGlass.android=false :desktopApp:captureScreenshots
```

The capture entry point and galleries are in `desktopApp/src/main/kotlin/dev/mttmcknn/seaglass/desktop/`. Output is written to `docs/screenshots/`. The public recipe/component APIs used by these galleries are the same APIs used by the app.

`./gradlew -PseaGlass.android=false :desktopApp:verifyPlayground` checks the actual Compose hierarchy: controls scroll independently, Tumble redraws the material, and resizing through 600 → 599 → 700 dp preserves the recipe. Material You recolors glass pixels and restores the previous palette. Keyboard tests enter/exit preview and verify focus restoration, button activation and disabled behavior. Components/Shape study retain their selected pigment, Fieldnotes retains saved state and actions, and palette selection exposes selection semantics. This is headless Compose verification, not an Android device performance measurement.


### Bounded live geometry reuse

The drawing owner and geometry worker each retain at most one cloud field per surface, capped at 262,144 floats apiece. Separate ownership prevents resize/disposal from mutating an active worker's cache. Tumble and shape changes reuse those unchanged seeded samples; logical size, effective sampling scale, or seed invalidates that entry. Rounded fields exceeding the sample budget bypass retention.

A shared LRU keeps up to 32 immutable geometry fields within an 8 MiB estimated CPU-storage budget, including both float textures, gradients and conservative contour overhead. Matching sizes, shapes, tumble values, seeds and sampling scales reuse fields across example visits. Cache contention bypasses retention without blocking the drawing thread. Density stays in each surface's native path/uniform key, because geometry is expressed in dp. This budget excludes transient builders and GPU allocations.

Android F16 packing reuses at most 2 MiB of shader-owned short-buffer scratch and releases it with the shader. Each upload still creates its own bitmap; published bitmap inputs are never overwritten or recycled while previous drawing commands may reference them. Sampling, half-float conversion and shader equations remain unchanged.
