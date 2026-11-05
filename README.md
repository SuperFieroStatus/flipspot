# FlipSpot

Keypad Spotify front end for the TCL Flip 2. See [PLAN.md](PLAN.md).

## Build & run

```powershell
.\scripts\deploy.ps1              # build, install, launch, tail logcat (tag FlipSpot)
.\scripts\deploy.ps1 -DeviceInfo  # ABI, screen size/density, key-shortcut settings
.\scripts\deploy.ps1 -GetEvent    # raw `getevent -l` key dump
```

Needs a JDK 17+ (the script falls back to Android Studio's bundled JBR) and the Android SDK
(`local.properties` -> `sdk.dir`, not committed).

Toolchain: Gradle 9.8.2 (wrapper, checksum-pinned), AGP 9.5.0 with built-in Kotlin,
compileSdk 37, min/targetSdk 30. No library dependencies in M0.

## Layout

- `HomeActivity` - Now Playing strip (placeholder) + menu list + soft-key labels.
  Debug builds show the last key event on screen under the strip.
- `ui/KeyRouter` - logs every key event and maps keycodes to `KeyAction`s.
- `ui/ListScreen` - ScrollView + LinearLayout list with a highlight bar.
