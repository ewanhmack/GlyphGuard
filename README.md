# Glyph Guard

A minimal Android app for the Nothing Phone (4a) Pro that gives you three
things the system's own Always-on Glyph Toy doesn't:

1. **A real master on/off switch** — when disabled, the matrix is forced
   off regardless of what the system thinks the AOD toy is doing.
2. **A timeout after pickup** — pick the phone up / wake the screen, and
   the display clears itself automatically after however many seconds you
   set (0 = instantly).
3. **Brightness control** — 0–255, applied live.

It ships with two built-in patterns (a filled dot, a ring) and lets you
import any PNG (e.g. a Glyph Museum export) which gets downsampled to
13×13 greyscale automatically.

## How the timeout actually works

The 4a Pro's system AOD toy has no built-in pickup detection — this is a
confirmed gap, not a missing setting. This app works around it the way the
community has for other Glyph Toys: the service registers its own
`ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` receiver rather than relying on
the toy framework's own (unreliable) `onUnbind()` lifecycle callback.
Screen-on starts the countdown; screen-off cancels it and re-arms the
frame for the next AOD cycle.

## Requirements

- Android Studio (Ladybug or newer)
- A Nothing Phone (4a) Pro (this targets `Glyph.DEVICE_25111p` specifically
  — it will not do anything useful on a Phone 3 or any other device)
- JDK 17+

## What's already included

The Nothing Glyph Matrix SDK (`glyph-matrix-sdk-2.0.aar`, official V1.1
release) is already bundled in `app/libs/` — you don't need to download it
yourself. It came straight from
[Nothing-Developer-Programme/GlyphMatrix-Developer-Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit).

## Build & install

```
cd GlyphGuard
```

1. Open the folder in Android Studio (`File → Open`)
2. Let Gradle sync
3. Connect your 4a Pro over USB (USB debugging on) and hit Run — or build
   a release APK with:

```
./gradlew assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-release-unsigned.apk`
(sideload it, or sign it with `apksigner` first if you want a signed
build).

## First-time setup on the phone

1. Open Glyph Guard, pick a pattern (or import an image), and set your
   brightness/timeout
2. Tap **"Open system Glyph Toy manager"** in the app (or manually:
   Settings → Glyph Interface → Flip to Glyph → Always-on Glyph Toy)
3. Select **Glyph Guard** as the toy
4. From then on, every change you make in the app takes effect live —
   toggling "Always-on enabled" off, for instance, blanks the matrix
   immediately without needing to re-select anything in system settings

## Wiring up the Essential Key

Nothing doesn't expose the Essential Key to third-party apps — there's no
SDK hook for it, confirmed by their own community threads. Every working
remap goes through the same community workaround, and that's what this
needs too. There's no granular "disable just the AI part" system toggle
either — freeing the key at all means freeing all of it, native AI/Notes
behavior included, which is exactly what step 1 does.

1. **Free the key from Essential Space** (one-time, from a PC with ADB):

   ```
   adb shell pm disable-user --user 0 com.nothing.ntessentialspace
   adb shell pm disable-user --user 0 com.nothing.ntessentialrecorder
   ```

2. **Install [Key Mapper](https://github.com/keymapperorg/KeyMapper)**
   (it's on both the Play Store and F-Droid, published by sds100/keymapperorg
   — it needs an Accessibility Service to read raw hardware key events, so
   grant that plus unrestricted battery).

3. In the app's **Key** tab, set what each press type should do (toggle
   Always-on, toggle flashlight, media play/pause/next/previous, or open a
   specific app) — this only configures what happens when a trigger fires,
   not the trigger itself.

4. Create **one Key Mapper rule per press type you configured**:
   - Trigger: **Essential Key**, mode = **Single press** / **Double press** /
     **Long press** (matching what you set in the app)
   - Action: **Intent** → select **"Broadcast receiver"**, then fill in the
     matching Action string:
     - Single press: `com.ewan.glyphguard.ACTION_KEY_SINGLE`
     - Double press: `com.ewan.glyphguard.ACTION_KEY_DOUBLE`
     - Long press: `com.ewan.glyphguard.ACTION_KEY_LONG`
     - (Key Mapper's own docs describe this exact "select Broadcast
       receiver, fill in Action" flow for triggering other apps —
       it's a first-class action type, not a workaround.)

5. Test each receiver on its own first — before touching Key Mapper — from
   a PC with ADB:

   ```
   adb shell am broadcast -a com.ewan.glyphguard.ACTION_KEY_SINGLE -p com.ewan.glyphguard
   adb shell am broadcast -a com.ewan.glyphguard.ACTION_KEY_DOUBLE -p com.ewan.glyphguard
   adb shell am broadcast -a com.ewan.glyphguard.ACTION_KEY_LONG -p com.ewan.glyphguard
   ```

   Run whichever one matches what you configured in the Key tab — if the
   action happens (flashlight toggles, music pauses, etc.), the receiver
   side is confirmed working and any remaining issue is in the Key Mapper
   configuration, not the app. The original `ACTION_TOGGLE_ALWAYS_ON`
   broadcast (a single, fixed toggle) still works too, unchanged, if that's
   all you need.

This wasn't build-tested against a physical Essential Key press in this
environment for the same reason as everything else here — but the
broadcast receivers themselves are plain Android and the ADB commands are
a direct, deterministic way to test them independent of Key Mapper.

## Known limitations

- Not build-tested against real hardware in this environment — the
  GlyphMatrixManager/GlyphToy API calls are copied directly from Nothing's
  official developer kit README and the working GlyphType reference
  project, but you'll be the first real test on your specific phone/OS
  build.
- Static frames only — no animation support (this was scoped to control,
  not art tools, since you're already using Glyph Museum for the design
  itself).
- The `NothingKey` meta-data is set to `"test"`, which is what Nothing's
  own official example project ships with — no real API key needed for
  local/sideloaded use.

## Project structure

```
app/src/main/java/com/ewan/glyphguard/
├── MainActivity.kt              # Compose entry point
├── glyph/
│   ├── GlyphController.kt       # App-mode SDK wrapper (live preview only)
│   ├── GuardPrefs.kt            # SharedPreferences: enabled/timeout/brightness/frame
│   └── GuardToyService.kt       # The actual AOD Glyph Toy + pickup timeout logic
├── engine/
│   ├── DefaultFrames.kt         # Built-in dot/ring patterns
│   ├── ImageToFrame.kt          # PNG → 13x13 greyscale converter
│   └── MatrixSize.kt
├── ui/
│   ├── MainScreen.kt            # Settings screen
│   └── GlyphSimulatorView.kt    # On-screen 13x13 preview
└── viewmodel/
    └── MainViewModel.kt
```
