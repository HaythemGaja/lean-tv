# Lean TV

Two apps that go together, in one repository.

| App | Installs on | Shows as | Package |
|---|---|---|---|
| **Lean TV** launcher | the TV | Lean TV | `com.gathin.tvhome` |
| **Lean TV Remote** | the phone | Lean TV Remote | `com.gathin.leantv.remote` |

The launcher is a clean Home screen for an Android TV: a row of tiles, no recommendations, no ads. The remote
drives it from a phone over the TV's own debug bridge, so nothing extra has to be installed on the TV.

## Get the two APKs

[`BUILD.md`](BUILD.md) is the full guide. The short version:

- **No tools installed?** Push the source and let GitHub build them: **Actions → Build the two APKs → Run
  workflow**, then download the `lean-tv-apks` artifact. It holds one `app-release.apk` for the TV and one for
  the phone.
- **One command locally:** `bash build.sh` (or `build.ps1` on Windows). Both APKs end up under
  `launcher/app/build/outputs/apk/release/` and `phone/app/build/outputs/apk/release/`.

Expected result of a fresh build:

| APK | Comes from | Size | Label |
|---|---|---|---|
| `lean-tv-launcher.apk` | `launcher/` | 617,850 B | Lean TV |
| `lean-tv-remote.apk` | `phone/` | 24,632 B | Lean TV Remote |

## Install

```bash
adb connect <tv ip>:5555
adb install -r lean-tv-launcher.apk     # TV
adb install -r lean-tv-remote.apk       # phone
```

After the TV install, press Home and choose **Lean TV**.

## Pair the phone with the TV

1. On the TV: Settings - About - tap **Build** seven times - Developer options - turn on **USB debugging**
   (or Network debugging).
2. Phone and TV on the same Wi-Fi. Open the remote, tap **Scan**, pick the TV, tap **Connect**.
3. The TV asks **Allow debugging?** - tick *always allow* and accept.

After that the D-pad, OK, Back, Home, volume and power work, **Apps** launches any installed app, **Health**
shows uptime, free memory and free storage, and **Screen** shows a picture of the TV.

When the TV is set up, turn debugging off again: it is an open door on the same network.

## What the launcher adds

The launcher is Gathin23's cleanyourtv launcher, unchanged in look, with four additions. They are off in the
upstream source and **on in this repository's build**:

- **Folders** - hold OK on a tile to make one; folders can hold folders.
- **Arrow-pattern lock** - an invisible Up/Down/Left/Right pattern guards apps, folders and Settings. The last
  arrow of the stored pattern opens the tile, so nothing on screen shows how long it is. No wait after a wrong
  try.
- **Hidden apps** - hide an app without uninstalling it; bring it back from the launcher's settings.
- **Guest mode** - one pattern entry takes every locked tile off Home; the same row brings them back.

To turn them off, set `folders` and `pin_lock` to `false` in
`launcher/app/src/main/res/values/config.xml` and rebuild.

## Honest notes

- The remote is a remote: it sends key events and reads status. It does not debloat the TV.
- Unicode text (Hebrew, Arabic, emoji) cannot be typed through `input text`; those characters are dropped.
- Nothing here uninstalls an app, and nothing needs root.
- The APKs here are signed with a debug key. Fine for sideloading, not for publishing.

## Credit

The launcher is a fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) (MIT). The four
additions and the phone app are ours, also MIT.
