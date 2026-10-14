# Demo GIFs

Five 720 × 900, 20 fps loops for the [main README](../../README.md#demos). The assets total 7,581,416 bytes (7.58 MB). These are composed promotional graphics: the glass surfaces and UI states are captured from Compose, while the card motion, cuts, tap cues, code typing, highlights, and comparison wipes are editorial animation.

| Asset | Duration | Size | What it shows |
| --- | ---: | ---: | --- |
| [Introducing Sea Glass](01-introducing-sea-glass.gif) | 7.2 s | 3,967,170 bytes | Components and local music, email, and chat examples |
| [Give it a Tumble](02-give-it-a-tumble.gif) | 7.2 s | 1,577,392 bytes | Matching UI at 0.0% and 6.5% Tumble |
| [Material You music example](03-material-you.gif) | 7.8 s | 136,619 bytes | Three tracks with curated, app-provided Material 3 schemes |
| [Compose integration](04-compose-integration.gif) | 6.6 s | 1,559,034 bytes | A minimal `SeaGlassButton` callback and content example |
| [Add glass to a Material button](06-material-button-modifier.gif) | 9.0 s | 341,201 bytes | Code is added to an existing Material 3 Button, then its appearance changes to glass |

## Capture context

The introduction, Tumble comparison, and integration piece use UI captured from the working Compose sample. The music, email, and chat interfaces contain local sample content. The Tumble comparison keeps layout, colors, frost, content, and seed fixed. The integration snippet is an excerpt inside a composable with an app-provided `onCompose` callback; its pictured button additionally supplies an icon and styling.

The Material You music example uses a standalone Compose/Skia demo. Its working Next action advances the track, artwork, and app-provided Material 3 color scheme. `SeaGlassMaterialTheme` derives glass colors from that scheme alongside the player's background, text, and progress bar. The app supplies curated album-inspired colors; Sea Glass does not extract colors from the artwork. This demo is separate from the sample playground's Material You switch and Android wallpaper color support. See [Material colors](../../docs/usage.md#material-you-and-material-3-colors).

The modifier piece starts with an actual Material 3 `Button` and its code. The animation types in `Modifier.seaGlass(glass)`, `containerColor = Color.Transparent`, and `contentColor = glass.palette.ink` line by line, then changes the button to its captured glass appearance. The callback, content, and native button semantics remain in place. The before and after button states are real Compose/Skia captures; the typing is an editorial illustration of the code change.

This shows enabled-state styling. Material retains its own ripple outline; clipping ancestors can crop the external glow, and disabled use needs explicit disabled colors and glass dimming. `SeaGlassComponentStyle` is this library's reusable appearance value. See [existing controls](../../docs/usage.md#containers-and-existing-controls) and [reusable styles](../../docs/usage.md#reusable-component-styles).
