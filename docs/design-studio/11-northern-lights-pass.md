# 11: The Northern Lights pass

30 October 2026. The founder asked for an opening and animations in the spirit of Opal's stone reveal, built from
the aurora, with the whole brand identity around the Northern Lights. This changes the approved First Light
identity at the founder's explicit request. No Opal asset, code or copy was used; its stone animation was not seen
on the device, so the opening is built from the founder's description.

## What changed

- **The opening** (`ui/components/Unveil.kt`): thread, strands, curtains, the night parts. Interactive on a first run.
- **Art:** gold removed from the night painting. The ring and water are now cold aurora light.
- **Sky:** curtains are taller, have sharper rays and are stronger in every state, with violet tops. New token `violet-400`.
- **Handover:** Home's blocks, ring, sky and fills wait for the night to part, then perform in its wake.

## Measured

Redmi 22041219I, Dimensity 700 class, 60 Hz, Android 14, optimized build.

| Check | Result |
|---|---|
| Cold start to first frame | 332–473 ms before the half-resolution change, 382–821 ms after (first runs after install are slowest) |
| Opening, full-resolution shader | about 35 fps, 60–73% janky frames |
| Opening, half-resolution shader | about 55 fps, 11–13% janky frames |
| Opening, after the smoothness pass (2.8s) | While moving, 2.5–5% of frames took 25–32 ms, none longer: at worst one missed frame at a time. Longer stalls now happen only while the night is still and the page loads behind it |
| Opening, after the sky moved to half resolution | Over 4 cold opens: 1–5 frames of 25–33 ms while moving, none longer |
| Home idle | 60 fps, 0 janky frames |
| Tab changes | 6–13% janky frames over 3 s |

## Second pass

- Sky at half resolution in its own layer.
- Plain screens: a faint top glow and a quick entrance. Builder progress fills and steps arrive from the right side.
- Pills slide, cards press and tick, checks pop, key numbers count up once.
- Shield: aurora sweep on arrival, emerald halo.
- Day art moved from gold to pale emerald. App icon under curtains of northern lights. Progress levels renamed around the aurora.

## Not done

- The logo is unchanged, and the point stays gold.
- Battery was not measured. The sky now redraws at half resolution, up to 30 times a second.
- The Shield sweep was checked in code and static screenshots only, not triggered live, because that would block an app on the founder's phone.
- Android 12 and below get a plain fade, because the shader needs Android 13.
- A few single dropped frames remain as the page's sky takes over from the curtains.
- The opening has not been seen by real users. Whether 2.8 seconds is welcome on every cold open is unknown.

## To undo

Remove `Opening.play()` from `MainActivity`. Art and palette: `brand/tools/first_light.py`, `glowWarm` in `Color.kt`.
