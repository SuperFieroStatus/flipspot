# FlipSpot

Keypad Spotify front end for the TCL Flip 2. See [PLAN.md](PLAN.md).

## Build & run

```powershell
.\scripts\deploy.ps1              # build, install, launch, tail logcat (tag FlipSpot)
.\scripts\deploy.ps1 -DeviceInfo  # ABI, screen size/density, key-shortcut settings
.\scripts\deploy.ps1 -GetEvent    # raw `getevent -l` key dump
```

## Sign-in (one-time, from the PC)

The Flip 2's WebView (83, 2020) renders Spotify's consent page blank, so sign in from the PC:

```powershell
.\scripts\get-token.ps1   # browser consent -> token.json pushed to the phone, imported, deleted
```

Dashboard needs both redirect URIs (`flipspot://callback`, `http://127.0.0.1:8888/callback`),
Android package `dev.mike.flipspot` + debug SHA-1, and both **Web API** and **Android** ticked.
Consenting on the PC also authorises App Remote for the account. Re-run to reset auth.

## Needs

A JDK 17+ (the script falls back to Android Studio's bundled JBR) and the Android SDK
(`local.properties` -> `sdk.dir`, not committed).

Toolchain: Gradle 9.8.2 (wrapper, checksum-pinned), AGP 9.5.0 with built-in Kotlin,
compileSdk 37, min/targetSdk 30. Libraries: Spotify App Remote 0.8.0 (`app/libs`), Gson,
Spotify auth 5.0.0.

## Layout

- `HomeActivity` - Now Playing strip (placeholder) + menu list + soft-key labels.
  Debug builds show the last key event on screen under the strip.
- `ui/KeyRouter` - logs every key event and maps keycodes to `KeyAction`s.
- `ui/ListScreen` - ScrollView + LinearLayout list with a highlight bar.
- `NowPlayingActivity` - live player state; OK/# play-pause, Left/Right skip, 4/6/7/9 seek,
  5 shuffle, 8 repeat; left soft key "Play here" when another Connect device is active.
- `remote/SpotifyRemote` - App Remote connection (connect on foreground, auto-retry).
- `auth/` - Keystore-encrypted token store, token import, PKCE refresh.
- `api/SpotifyWebApi` - active Connect device lookup.

Debug-only audiobook spike: `adb shell am start -n dev.mike.flipspot/.HomeActivity --es play_uri <spotify:uri>`
