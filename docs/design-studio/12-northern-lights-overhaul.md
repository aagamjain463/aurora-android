# 12: The Northern Lights overhaul

30 October 2026. The founder said the app looked good but did not look like an aurora, and asked for a complete
overhaul, with a backup of the previous design.

## The previous design is backed up

- Git tag `backup/before-northern-lights-overhaul`: every file of the previous version, including uncommitted work.
- Installable app: `~/Desktop/aurora-backups/aurora-before-northern-lights-overhaul.apk`.

To try the old version on the phone, without changing any code:

    adb install -r ~/Desktop/aurora-backups/aurora-before-northern-lights-overhaul.apk

To go back to the old code, keeping the overhaul in a stash in case you want it again:

    git stash -u
    git switch -c previous-design backup/before-northern-lights-overhaul

## What changed

- **Art:** a new painting of a polar night and still lake, with no ring, sun or gold (`brand/tools/aurora_art.py`).
- **Sky:** a new live shader with folded curtains, tall rays, teal and violet, a pink fringe, and a reflection on the lake.
- **Timers:** the sun-like ring is replaced by the opening's thread on the horizon, with the sky lighting behind it.
- **Palette:** blue-black polar night, electric green, teal, violet and rose. Gold and cream removed everywhere.
- **Logo:** the Corona, three converging rays reading as an A. New icon, splash, status icon and masters.
- **One theme:** always night. The Theme setting and the light theme are gone.
- **Opening:** brighter curtains, one thread with faint strands.

## Measured

Redmi 22041219I, Dimensity 700 class, 60 Hz, Android 14, optimized build.

| Check | Result |
|---|---|
| Opening, 4 cold opens, while moving | Up to 7 frames over 25 ms per open, worst 38 ms |
| Before the light moves (page loading) | Stalls up to 172 ms, while the night is still |
| Home idle | 60 fps, 0 janky frames |
| Home scroll | 0 janky frames |
| Tab change, over 3 s | 12% janky frames |

Battery was not measured. The block screen sweep and the Android 12 still were not seen on a device.

## Calmer pass

The founder found the first version flashy and asked for calmer, cleaner and smoother, with a better logo. The sky lost its
fine rays, pink fringe and hard hem, and now has lower contrast, slower drift and a faint reflection. The logo became the
Arcs. The vivid version is tag `backup/vivid-northern-lights`, and `~/Desktop/aurora-backups/aurora-vivid-northern-lights.apk`.

Measured on the same phone after this pass, over 3 cold opens: worst moving frame 44 ms, usually 25 to 36 ms; Home idle 0 janky frames. The logo has not had a trademark search.

## Glow and palette pass

The founder asked for a little more glow and a palette drawn from aurora light. The sky gained a softer, wider hem glow and a
band of light along the horizon. Surfaces and text are tinted teal, the primary action is the logo's sweep, and card edges catch
the light. The previous version is tag `backup/calm-northern-lights`, and `~/Desktop/aurora-backups/aurora-calm-northern-lights.apk`.

Measured after this pass, over 3 cold opens: worst moving frame 44 ms, usually 25 to 33 ms; Home idle 0 janky frames.
