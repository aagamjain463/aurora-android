# Aurora final logo

`aurora-final-logo.png` is the founder-approved master and the source of truth for Aurora's visual identity. Never edit, re-export or regenerate it.

| File | What it is | Fidelity |
|---|---|---|
| `aurora-final-logo.png` | Official complete logo, 1254 px | The master |
| `aurora-symbol-on-midnight.png` | Symbol on its own background | Exact crop of the master |
| `aurora-logo-full-transparent.png` | Symbol and wordmark | Master pixels, background lifted off |
| `aurora-symbol-transparent.png` | Symbol | Master pixels, background lifted off |
| `aurora-wordmark-transparent.png` | Wordmark | Master pixels, background lifted off |
| `aurora-app-icon-512.png` | Store icon, no wordmark | Master symbol on its fitted background |
| `aurora-symbol.svg` | Editable vector: ribbons, gradients, mask, glow | **Approximation.** Geometry is traced from the master; texture and glow differ |
| `aurora-symbol-small.svg` | Small-size variant, solid silhouette | **Secondary derivative**, for about 20dp and under |
| `aurora-symbol-mono.svg` | One-colour silhouette | **Secondary derivative**, for status bar and themed icons |

There is no vector wordmark: the master's lettering is not a font we hold, so it stays raster.

The transparent files hold light, not paint: use them on night or midnight. On white they wash out.

## Regenerating

    python3 brand/tools/final_logo.py          # raster cuts, launcher foregrounds, splash, store icon (numpy, scipy, Pillow, cwebp)
    python3 brand/tools/final_logo_vector.py   # SVGs, ic_launcher_monochrome, ic_stat_aurora, ic_mark

Android files: `res/drawable-nodpi/logo_*.webp`, `splash_symbol.webp`, `res/mipmap-*/ic_launcher_fg.webp`.
Fidelity evidence: `docs/design-studio/evidence/final-logo/`.
