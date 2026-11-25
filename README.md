# Immersive

An Android app that forces every app full screen — status bar, navigation bar, or both — and can force rotation,
including in apps that lock themselves to portrait. Look and switches follow Grabber's design (Ink palette,
Google Sans Flex), with smaller toggles.

**Download:** [latest build](https://github.com/Hwezah/immersive/releases/tag/immersive-latest) (`Immersive.apk`).
The app checks that page itself, downloads new builds in the background and asks before installing.

## How it hides the bars

| Layer | Needs | Works on |
| --- | --- | --- |
| Overlay window asking for immersive (Light) | Accessibility service *or* "display over other apps" | Android 8–10 fully |
| Same window holding input focus (Force): Back and volume passed on, steps aside for the keyboard | Accessibility service | Android 11+ |
| `policy_control` global immersive policy | Secure settings | Android ≤10, some ROMs |
| OEM switches that remove the nav bar / pill (Xiaomi, Samsung) | Secure settings | those phones |
| Mark "swipe to exit" hint as seen | Secure settings | all |
| Re-enable its own accessibility service if Android drops it | Secure settings | all |

Swiping from an edge shows the bars for a moment; they hide again after the delay you pick (1–8 s).
Home screens and keyboards are left alone unless an app rule says otherwise. The lock screen is never covered.

Grant secure settings once from a computer:

```
adb shell pm grant com.hwezah.immersive android.permission.WRITE_SECURE_SETTINGS
```

…or tap **Root** in the app on a rooted phone. Every setting Immersive changes is saved first and restored when
you turn the trick off.

## Rotation

Auto reads the accelerometer itself and asks for the exact direction — 0°, 90°, 180° (upside down) or 270° —
through two routes at once: a 1px overlay carrying that orientation (overrides apps locked to portrait) and,
once "Modify system settings" is allowed, Android's own rotation setting (works where a ROM ignores overlays;
put back when rotation is turned off). With root it also clears the window manager's "ignore orientation
requests" switches. Fixed choices: Portrait, Landscape, landscape-by-sensor, Upside down. Per-app overrides and
Quick Settings tiles for both features.

## Caveats

- Force mode holds input focus. Some games pause when they lose focus — set those apps to Light in Apps.
- After tapping a text field you may need to tap it once more for the keyboard to appear.
- Android 13+ blocks the accessibility switch for sideloaded apps until you allow it: App info › ⋮ › Allow restricted settings.
