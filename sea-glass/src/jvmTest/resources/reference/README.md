These fixtures come directly from the approved `web/dist/sea-glass.mjs`, using
`tools/generate-reference.mjs` and the pinned `@napi-rs/canvas` Skia implementation.
Run `npm ci --prefix tools && npm run --prefix tools generate-reference` to refresh.
`manifest.properties` records the exact renderer SHA-256 and array ordering.

Each case covers a different outline, scale, unsigned seed, palette, or material
extreme. The `.f32le` arrays retain the JavaScript renderer's Float32 boundaries.
The `.u8` masks and `.rgba` body images use one byte per channel. `mask.u8` includes
the one-pixel border requested by the distance transform; `alpha.u8` does not.

JVM parity tests inject the oracle mask into the Kotlin renderer to test all
material mathematics independently of the platform path rasterizer. Three light
angles validate moving reflections and stationary transmission. Full PNG tests
include the ground layers with a small tolerance for different blur kernels.
Additional JVM tests exercise the actual Compose/Skia path rasterizer, pixel
upload adapter, and complete material drawing pipeline. The latter writes a
labeled Web/Compose comparison to `build/reports/sea-glass/compose-comparison.png`. These are not Android or iOS screenshot tests; device rendering
still needs visual validation. `web-reference.png` provides larger samples for that comparison.

`blur.properties` records alpha profiles across the same device-space rectangle
under transforms of 1, 2, and 2.5. The `blur(4px)` filter widens with the transform
in this Canvas implementation, so the native Gaussian uses sigma = blurDp * scale.
