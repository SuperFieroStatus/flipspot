# FlipSpot — Keypad Spotify front end for the TCL Flip 2

A D-pad-driven Android app that controls the installed Spotify app on the TCL Flip 2 (4058G, Android 11, 240×320 / forced 321×428, 1 GB RAM, no touchscreen). Spotify does the audio, auth, and offline downloads; FlipSpot is the UI.

Paste this file into the repo as `PLAN.md` and point Claude Code at it.

---

## 0. Hard constraints (read first)

- **No touch.** Every screen must be fully operable with D-pad, OK, Back, soft keys, and 0–9/*/#. If a feature can't be driven that way, it doesn't ship.
- **Tiny screen.** Design for 240×320 at mdpi; verify at the forced 321×428. One list per screen, ~5–6 rows visible, 16–18sp text, no side-by-side layouts.
- **1 GB RAM, MT6739.** Plain Android Views (not Compose). No Hilt/Dagger, no Retrofit+Moshi+OkHttp-logging stack; use OkHttp + Kotlin serialization only. No images in lists (album art only on Now Playing, 64px, cached). Target APK < 3 MB.
- **minSdk 30, targetSdk 30**, `armeabi-v7a` + `arm64-v8a` ABIs. Use `getprop ro.product.cpu.abi` on device to confirm which to ship; build both initially.
- **No Google Play Services on device.** Nothing in the app may depend on GMS.
- **Spotify must stay installed and logged in** (`com.spotify.music`). FlipSpot never replaces it.

## 1. Architecture

```
FlipSpot
├── SpotifyRemote      – wraps Spotify App Remote SDK (play/pause/skip/seek/playUri/subscribe PlayerState)
├── SpotifyWebApi      – OkHttp client for Web API (library, playlists, audiobooks, search)
├── TokenStore         – EncryptedSharedPreferences; access + refresh token, PKCE refresh
├── LibraryCache       – Room DB mirroring playlists/saved shows/audiobooks for offline browsing
├── ui/
│   ├── ListScreen     – generic focusable RecyclerView-free list (ScrollView + LinearLayout rows; cheaper)
│   ├── NowPlayingActivity
│   ├── LibraryActivity, PlaylistActivity, AudiobooksActivity, SearchActivity
│   └── KeyRouter      – one place that maps keycodes to actions per screen
└── BootReceiver       – optional: start Spotify in background on boot so App Remote connects fast
```

Two Spotify integration layers, deliberately:

| Need | Use | Why |
|---|---|---|
| Play/pause/skip/seek, play a URI, current track state | **App Remote SDK** (`spotify-app-remote-release-*.aar`) | Works offline with downloaded content; talks to the local Spotify app over IPC; no network needed once connected. |
| Browse playlists, saved audiobooks, chapters, search | **Web API** | App Remote has no library browsing. Requires network; results cached locally. |

## 2. Auth (one-time, then keypad-only)

Use **Authorization Code with PKCE** via the Spotify Android auth library (`spotify-auth-release-*.aar`). When the Spotify app is installed, it shows a native consent dialog inside Spotify — use FlipMouse once to tap Agree. Store the refresh token; refresh silently from then on.

Scopes: `user-read-playback-state user-modify-playback-state user-read-currently-playing playlist-read-private playlist-read-collaborative user-library-read app-remote-control streaming`

Setup (you, on the PC, 5 minutes):
1. developer.spotify.com → Dashboard → Create app.
2. Redirect URI: `flipspot://callback`
3. Android package: `dev.mike.flipspot`, plus the app's SHA-1 signing fingerprint (`keytool -list -v -keystore ~/.android/debug.keystore`; later the release keystore).
4. Put Client ID in `local.properties` as `SPOTIFY_CLIENT_ID=...` (never commit).

Fallback if the in-app consent is unworkable: run the PKCE flow in a desktop browser, capture the code, and push the token JSON with `adb push token.json /sdcard/Android/data/dev.mike.flipspot/files/`. The app imports it on first launch. Build this fallback in Milestone 1 — it also makes test resets painless.

## 3. Key map

Global (every screen):
- **D-pad Up/Down**: move selection. **Left/Right**: page up/down in lists; previous/next track on Now Playing.
- **OK**: activate. **Back**: up one level. **End**: home (system).
- **Left soft key**: context menu for selected row (Play, Shuffle, Pin to key, Add to queue).
- **Right soft key**: jump to Now Playing from anywhere.
- **#**: play/pause from anywhere.
- **1–9**: pinned shortcuts (user-assigned playlists/audiobooks). **0**: Search.
- **Volume keys**: system volume (pass-through).

Now Playing:
- **OK**: play/pause. **4 / 6**: seek −15 s / +30 s (audiobook-friendly; configurable). **7 / 9**: seek −5 min / +5 min. **5**: toggle shuffle. **8**: repeat. **\***: show queue.

Lists use a persistent highlight bar; the selected row is always fully visible (auto-scroll).

## 4. Screens

1. **Home** — Now Playing summary strip (title/author, play state) + menu: Playlists · Audiobooks · Liked Songs · Albums · Search · Settings.
2. **Playlists** — cached list; OK opens tracks; left-soft → Play / Shuffle / Pin.
3. **Audiobooks** — saved audiobooks (`GET /me/audiobooks`); OK → chapters with resume position; OK on chapter → play.
4. **Now Playing** — title, artist/author, elapsed/total, progress bar, shuffle/repeat flags. Subscribes to `PlayerState` via App Remote.
5. **Search** — T9-style text entry on the number pad (3-tap multi-press; the stock Kika IME may be usable, test it). Results grouped: Tracks / Albums / Playlists / Audiobooks.
6. **Settings** — re-auth, refresh library cache, seek step sizes, pin assignments, clear cache.

## 5. Offline behaviour

- Library cache (Room) is refreshed manually from Settings and opportunistically on launch when network is present.
- With no network: browsing works from cache; playing a cached playlist/audiobook that Spotify has downloaded works via App Remote `playUri`. Non-downloaded content fails with a clear "Not downloaded" toast rather than a spinner.
- Never block the UI on a network call; every Web API call runs on a background thread with a 5 s timeout.

## 6. Milestones

**M0 — Scaffold (1 session)**
- Gradle project, Kotlin, Views, minSdk 30. Debug build installs via `adb install -r`. Launches to a placeholder Home list that the D-pad can navigate. Set `keyshortcut_rightkey` to FlipSpot so Right opens it from the home screen.

**M1 — Auth + Now Playing (1–2 sessions)**
- PKCE auth (in-app + token-import fallback). App Remote connects to `com.spotify.music`. Now Playing shows live state; OK/Left/Right/4/6 work. **This proves the whole approach.** Spike: confirm `playUri("spotify:audiobook:…")` and chapter URIs behave on App Remote; if not, fall back to Web API `PUT /me/player/play` targeting the phone's own device ID.

**M2 — Playlists + Liked Songs (1 session)**
- Web API fetch → Room cache → list → play/shuffle. Pin to number keys.

**M3 — Audiobooks (1 session)**
- Saved audiobooks, chapters, resume position (`resume_point` from `GET /me/audiobooks`/chapters), seek steps.

**M4 — Search + polish (1–2 sessions)**
- T9 entry, results, queue view, settings, error handling, release keystore, `minifyEnabled true` with R8 to shrink.

**M5 — Launcher integration (later, separate project)**
- Replace Launcher4 with your own minimal launcher; FlipSpot becomes a key shortcut and Now Playing strip source.

## 7. Dev loop

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.mike.flipspot/.HomeActivity
adb logcat -s FlipSpot:D SpotifyAppRemote:D
```

Keep a `scripts/deploy.ps1` that does build + install + launch + logcat in one go so Claude Code can iterate without you touching the phone.

## 8. Known risks / spikes to run early

1. **App Remote + audiobooks.** The SDK is music/podcast-first; audiobook URIs may need the Web API path. Test in M1.
2. **Auth consent dialog on a non-touch device.** FlipMouse once should do it; the token-import fallback covers the rest.
3. **Spotify app memory pressure.** If Android kills Spotify in the background, App Remote disconnects. Mitigation: FlipSpot runs a foreground service with a persistent notification while playing; and `adb shell cmd appops set com.spotify.music RUN_IN_BACKGROUND allow`.
4. **Old Spotify build vs SDK version.** Use App Remote SDK 0.8.x (matches 2024-era Spotify builds). If connection fails with "CouldNotFindSpotifyApp"/version error, try one Spotify version newer.
5. **Keycodes.** Soft keys and End/Call on the Flip 2 may report non-standard keycodes; dump them with `adb shell getevent -l` in M0 and hard-code the map in `KeyRouter`.

### Spike results (2026-11-04, TCL Flip 2, Spotify 8.9.76, App Remote 0.8.0)

- **Keycodes (M0):** all standard Android codes; table in `ui/KeyRouter.kt`. End never reaches apps (system Home).
- **Auth (risk 2):** in-app consent fails. WebView 83 renders Spotify's consent page blank. Consenting once
  via `scripts/get-token.ps1` in the desktop browser authorises both the Web API token *and* App Remote.
  Dashboard must have **Web API** ticked as well as Android, or the native flow returns
  `AUTHENTICATION_SERVICE_UNAVAILABLE`.
- **Audiobooks (risk 1): App Remote works, no Web API fallback needed.**
  - `/me/audiobooks` returns audiobook URIs as `spotify:show:<id>`; chapters as `spotify:episode:<id>`.
  - `playUri(spotify:show:…)` plays on the phone, resuming in the book.
  - `playUri(spotify:episode:<chapter>)` plays that chapter, then continues to the next chapter, which
    resumes at its saved position.
  - PlayerState reports chapters with `isEpisode`/`isPodcast`; artist/author is empty (use the book's
    authors from the Web API for display).
  - `resume_point` on chapters needs scope **`user-read-playback-position`** (not yet requested): add for M3.
- **Spotify Connect:** App Remote steers whichever device is active (e.g. the PC). Now Playing shows the
  active device (`GET /me/player`) and the left soft key "Play here" calls `connectSwitchToLocalDevice()`.

### M2 findings (2026-11-04)

- **Web API changes:** playlist contents are `GET /playlists/{id}/items` (entries under `item`); the old
  `/tracks` returns 403. Playlist size is `items.total` (was `tracks.total`).
- **Development-mode restriction:** `/items` is 403 for playlists owned by other users. FlipSpot shows those
  as play/shuffle only (skips the request); App Remote `play(playlistUri)` still works for them.
- **Play from a song:** App Remote `skipToIndex(context, i)` works for playlists and albums, but **ignores the
  index for Liked Songs** (`spotify:user:<id>:collection` always starts at song 1). Liked Songs uses Web API
  `PUT /me/player/play {context_uri, offset:{uri}}` instead, which works and keeps the collection context.
- **Cache:** JSON files in `filesDir/library` instead of Room (no extra dependency). First Liked Songs fetch
  (1,675 songs, 34 pages of 50) took ~14 s; playlists 0.5 s; albums 0.6 s. Shown from cache after that,
  refreshed in the background when older than 6 h.
- **Made for you (Discover Weekly etc.):** Spotify-made playlists aren't in `/me/playlists` and the Web API
  won't read them for a Development-mode app. App Remote's Content API (`getRecommendedContentItems` +
  `getChildrenOfItem`, the browse tree car/watch integrations use) lists them with URIs. FlipSpot keeps
  personal mixes (`37i9dQZEVX…` Discover Weekly/Release Radar, `37i9dQZF1E…` Daily Mixes/DJ/radios), skips
  editorial `37i9dQZF1DX…`, caches the list, and plays/pins them by URI. Discover Weekly's URI is stable per
  account (contents change every Monday), so a pin keeps working. Debug dump:
  `adb shell am start -n dev.mike.flipspot/.HomeActivity --ez browse_dump true`.
- **Pins:** 1–9, each stores URI + name + optional shuffle (in order / shuffled). Work on Home and in lists;
  on Now Playing only 1–3 (4–9 are transport keys there).
- **Lists:** `ListScreen` is virtualised (only on-screen rows exist) so long lists stay cheap.
- **Launcher4:** resets a home-screen key shortcut it doesn't know about (it keeps its own list) and crashed
  once trying to write `Settings.System` without `android.permission.WRITE_SETTINGS`, which its manifest
  never requests, so it can't be granted. `deploy.ps1` restores FlipSpot shortcuts after installs.

## 9. First Claude Code prompt

> Read PLAN.md. Create the M0 scaffold: Kotlin, Android Views, minSdk/targetSdk 30, package dev.mike.flipspot, no Compose, no DI frameworks, ABIs armeabi-v7a+arm64-v8a. Implement HomeActivity as a focusable vertical list with a highlight bar, D-pad navigation, and a KeyRouter that logs every keycode received. Add scripts/deploy.ps1 (build, install, launch, logcat). Stop after M0 and report the keycodes you expect me to verify on-device.
