# Lean TV

Two apps that go together, in one repository.

| App | Installs on | Shows as | Package |
|---|---|---|---|
| **Lean TV** launcher | the TV | Lean TV | `com.gathin.tvhome` |
| **Lean TV Remote** | the phone | Lean TV Remote | `com.gathin.leantv.remote` |

The launcher is a clean Home screen for an Android TV: a row of tiles, no recommendations, no ads. The remote
drives it from a phone over the TV's own debug bridge, so nothing extra has to be installed on the TV.

## Download

Both APKs are in **[Releases](../../releases)** on this repository:

- `lean-tv-launcher.apk` - the TV app, with folders, the arrow-pattern lock, hidden apps and guest mode on.
- `lean-tv-remote.apk` - the phone remote.
- `lean-tv-source.zip` - the complete source of both apps.

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

When you are done setting the TV up, turn debugging off again: it is an open door on the same network.

## What the launcher adds on top of clean-your-tv

The launcher is Gathin23's cleanyourtv launcher, unchanged in look, with four additions that are off in the
upstream source and **on in the APK here**:

- **Folders** - hold OK on a tile to make one; folders can hold folders.
- **Arrow-pattern lock** - an invisible Up/Down/Left/Right pattern guards apps, folders and Settings. The last
  arrow of the stored pattern opens the tile, so nothing on screen shows how long it is. No wait after a wrong try.
- **Hidden apps** - hide an app without uninstalling it; it comes back from the launcher's settings.
- **Guest mode** - one pattern entry takes every locked tile off Home, the same row puts them back.

To turn them off, set `folders` and `pin_lock` to `false` in
`launcher/app/src/main/res/values/config.xml` and rebuild.

## Build from source

JDK 17, Android SDK platform 34 and build-tools 34.0.0. Unzip `lean-tv-source.zip`; it holds `launcher/` and
`phone/` as two Gradle projects.

```bash
cd launcher && ./gradlew --no-daemon assembleRelease
cd ../phone && ../launcher/gradlew --no-daemon assembleRelease
```

Both build with the launcher's Gradle wrapper (Gradle 8.9, AGP 8.5.2), so there is one Gradle version in the
repository and no wrapper jar in `phone/`.

## Honest notes

- The remote is a remote: it sends key events and reads status. It does not debloat the TV.
- Unicode text (Hebrew, Arabic, emoji) cannot be typed through `input text`; those characters are dropped.
- Nothing here uninstalls an app, and nothing needs root.

## Credit

The launcher is a fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) (MIT). The additions
and the phone app are ours, also MIT.
