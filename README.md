<p align="center"><img src="docs/logo.png" width="132" alt="CubClone"></p>

<h1 align="center">CubClone</h1>

<p align="center"><b>English</b> · <a href="README.zh-CN.md">简体中文</a></p>

CubClone is a multi-instance app cloner for Android. It creates real, system-level
clone profiles and runs independent copies of any app inside them — the mechanism
Android vendors ship as "App Twin", rebuilt as a single open-source app.

One profile, one sandbox: each clone has its own app data, login sessions and
notifications, so one installed app can become as many independent accounts as the
device allows.

## How it works

CubClone does not virtualize apps. It drives Android's native **multi-user** support:

1. Create a `profile.CLONE` user via `pm create-user` — the device-side helper runs
   through `app_process`.
2. Install an app into that profile with `pm install-existing --user <uid>`.
3. Launch it with cross-user intents and pin a launcher shortcut for one-tap access.
4. Keep a per-profile package registry so system apps pre-installed into a new
   profile can be cleaned up automatically.

Elevation comes from **Shizuku** or **root**, whichever the device has.

- Profiles created by CubClone are tagged at creation time, so ownership survives
  uid reuse.
- Profiles created by other apps are shown **read-only**: visible and launchable,
  never modified.

## Features

- Grid and list views, switchable, with adjustable columns and rows
- "Recently opened" strip on both views
- Multi-select batch cloning: pick N apps × M profiles
- Per-clone rename, enable/disable, hide, shortcut pinning, per-profile cleanup
- Input-method picker per new profile, with automatic repair of the profile's IME
  settings
- Built-in check for the kernel `sdcardfs` conflict that leaves clone storage
  unmountable on some ROMs; the app detects it and generates a one-tap fix module
  for the device's root manager
- Theme: system / light / dark / pure black (AMOLED); English and Chinese UI

## Build

No Gradle — plain Android build tools (aapt2 / javac / d8 / apksigner).

```bash
# 1) device-side helper jar (shipped as an asset)
(cd proto && ./build.sh)

# 2) the app; signing credentials are read from $CUBCLONE_SIGNING
#    (default: ./signing.properties, which is git-ignored)
(cd app && ./build.sh)
```

Requirements: Android SDK build-tools 35.0.0 + platform android-36, JDK 21.

Shizuku API classes are vendored under `app/libs/` from the official Shizuku
releases; the matching AIDL sources are in `app/libs/aidl`.

## Signing

The release keystore lives **outside the repository** — never commit it, since
repository access would then equal key access. `app/build.sh` reads a local
properties file:

```
storeFile=<absolute path to .jks>
storePassword=...
keyAlias=...
keyPassword=...
```

## Status

Actively developed and used daily by the author. Verified on Android 13–16 devices,
both near-AOSP and vendor ROMs.

## License

Apache-2.0 — see [LICENSE](LICENSE). Brand assets (logo) are not covered by the
license.
