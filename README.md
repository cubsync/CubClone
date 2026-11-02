# CubClone

**CubClone** (分身空间) is a multi-instance app cloner for Android. It creates real, system-level clone profiles and runs independent copies of any app inside them — the same mechanism Android OEMs use for "App Twin / 应用分身", rebuilt as a single open-source app.

One profile, one sandbox: each clone has its own app data, its own login sessions, its own notifications. An app can be cloned into as many profiles as the device allows, so one copy of WeChat can become five fully independent accounts.

## How it works

CubClone does not virtualize apps. It drives Android's native **multi-user** support:

1. Create a `profile.CLONE` user via `pm create-user` (the device-side helper runs through `app_process`).
2. Install an app into that profile with `pm install-existing --user <uid>`.
3. Launch it with cross-user intents, and pin a launcher shortcut for one-tap access.
4. Keep a per-profile package registry, so unused system apps that the system pre-installs into a new profile can be cleaned up automatically.

Elevation is obtained through **root (Magisk/Su)** or **Shizuku** — whichever is available.

- Profiles created by CubClone are tagged at creation time (a `WB_`-prefixed user name plus an in-profile marker), so ownership survives uid reuse.
- Profiles created by other apps (e.g. an OEM's own twin feature) are shown **read-only**: visible, launchable, never modified.

## Features

- Launcher-style **grid view** and list view, switchable; adjustable columns/rows
- "Recently opened" strip on top of both views (toggleable, adjustable columns/rows)
- Multi-select batch cloning: pick N apps × M profiles
- Per-clone rename, disable/enable, shortcut pinning, per-profile cleanup
- Theme: system / light / dark / pure-black (AMOLED), English & Chinese UI

## Build

No Gradle — plain Android build tools (aapt2 / javac / d8 / apksigner).

```bash
# 1) device-side helper jar (assets payload)
(cd proto && ./build.sh)

# 2) the app itself; signing credentials are read from
#    $CUBCLONE_SIGNING (default: D:/Dev/!Key/cubclone-signing.properties, not in repo)
(cd app && ./build.sh)
```

Requirements: Android SDK build-tools 35.0.0 + platform android-36, JDK 21.
Shizuku API classes are vendored under `app/libs/` (from the official Shizuku releases; AIDL sources in `app/libs/aidl`).

## Signing

The release keystore lives **outside the repository** (never commit it — clone access equals key leak). `app/build.sh` reads a local properties file:

```
storeFile=<absolute path to .jks>
storePassword=...
keyAlias=cubclone
keyPassword=...
```

## Status

Actively developed; feature-complete for daily use on the author's devices. Tested on stock-ish Android 13–15 devices (LineageOS, ColorOS, MYUI).

## License

Apache-2.0 — see [LICENSE](LICENSE). Brand assets (logo) are not covered by the license.
