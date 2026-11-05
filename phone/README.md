# Lean TV (phone)

The phone end of Lean TV: a D-pad remote, cleaning for the TV, and the Lean TV settings a phone can carry.
It is its own app and its own Gradle project, never built into the launcher.

## How it talks to the TV

Over the TV's own debug bridge (Adb, port 5555) on the home network. That choice is deliberate: the launcher has
no `INTERNET` permission and no listening socket by rule, so it cannot accept commands from a phone. Adb is
already on the TV, the phone is the client, and every press, switch and setting arrives as an ordinary shell call.

| The phone needs | The TV needs |
| --- | --- |
| The TV's address on the home network | Network (or USB) debugging on, in Developer options |
| One tap on the TV's prompt, once | Nothing installed for this: `adb` ships with the TV |

The first connection shows the TV's **Allow debugging?** prompt naming this phone. Accept it and the key is
remembered, so the prompt does not come back.

## Build and install

JDK 17, Android SDK platform 34 and build-tools 34.0.0, the same as the launcher.

```bash
cd phone && ./gradlew --no-daemon assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

`testReleaseUnitTest` checks the Adb framing, the public key struct, the text escaping, the health lines and the
cleaning rules on a plain JVM.

## What it does

### The remote

- A D-pad, OK, Back, Home, volume and power, sent as key events. A long press on Back sends three.
- **Scan** finds TVs announcing Adb on the home network - `_adb._tcp` for network debugging on 5555,
  `_adb-tls-connect._tcp` for Android 11's Wireless debugging and its own port - so an address is never typed.
- **Saved TVs** keeps every set it has connected to, newest first, and switches with one tap.
- Reconnects by itself: three tries on connect, and a press after the TV slept reopens the connection.
- **Apps** lists the TV's own third-party apps and launches one directly, without walking Home's grid.
- **Health** shows uptime, free memory and free storage, which is the answer to "why is it slow".
- **Screen** shows a frame of what the TV has on it, refreshed while it is open.

### Cleaning the TV

**Clean the TV** reads what the TV has and offers three things:

- **Switch off apps**: the installed apps, with the ones Android needs left out. Ticking a row and confirming
  switches that app off for the TV's main user with `pm disable-user --user 0`; nothing is uninstalled, so the
  app's data and its place in the list are untouched.
- **Put an app back**: every app the TV reports as switched off, one or many.
- **Put back what this phone switched off**: the restore point, kept on the phone, so an app switched off last
  week comes back without remembering its name.

What is never offered: Android's own packages (`com.android.*`, `com.google.android.*`), the permission
controller, the package installer, the settings, the system UI, the Play Store, Lean TV itself, and anything whose
name says home screen, setup wizard, live TV, tuner, TV input, on-screen keyboard or Lean TV's own app. The rule
lives in `Clean.java` and is unit tested: a name this app cannot read is protected rather than offered.

### Customising Lean TV

**Customize Lean TV** changes the launcher's own settings from the phone: tiles per row, rows under the dock,
dock opacity, folders on Home, the invisible lock, and the greeting. It is written to the launcher's
`config.json` overlay through the launcher's content provider (the way the adb shell reaches `Android/data` on
Android 11 and newer), then Home is restarted so the change shows. Only the rows that moved are written, so a
theme pushed from a computer keeps everything else it set; **TV default** removes the key and the value built
into the launcher applies again.

The screen also opens **Lean TV settings on the TV** and restarts Home, for the parts a phone row cannot carry:
wallpaper, tiles, hidden apps, modules and the rest.

## What it cannot do

- Text that `input text` cannot send is dropped: Hebrew, Arabic and emoji need an input method on the TV that
  accepts them, and this app does not install one.
- It is a remote, not a second launcher: apps open on the TV, not on the phone.
- A locked tile still asks for its arrow pattern on the TV. The phone sends arrows, so it can draw the pattern
  like any remote, and nothing here stores it.
- Cleaning switches apps off; it never uninstalls one, and it never touches a protected package.
- Debugging has to be on, which is also an open door: `addons/remote-access` exists to switch it back off once
  the TV is set up.
