# Building the two APKs

This repository holds two Android apps as two Gradle projects. Each produces one APK.

| Folder | App | APK it produces | Package |
|---|---|---|---|
| `launcher/` | Lean TV, the TV launcher | `launcher/app/build/outputs/apk/release/app-release.apk` | `com.gathin.tvhome` |
| `phone/` | Lean TV Remote, the phone app | `phone/app/build/outputs/apk/release/app-release.apk` | `com.gathin.leantv.remote` |

Build either one alone or both. They share nothing at build time except the Gradle wrapper in `launcher/`
(Gradle 8.9), and `phone/` carries its own copy so it can be built on its own.

## What you need

- **JDK 17** (`JAVA_HOME` set to it)
- **Android SDK platform 34** and **build-tools 34.0.0**, with `ANDROID_HOME` pointing at the SDK
- Nothing else: no Android Studio, no extra dependencies, no NDK

`minSdk` is 26 on both apps, `targetSdk` 34. Neither app needs a network service to build.

The SDK path can also go in a `local.properties` file in either project instead of `ANDROID_HOME`:

```
sdk.dir=/path/to/Android/Sdk
```

That file is gitignored and must never be committed.

## Option 1: let GitHub build them (nothing installed)

The workflow [`.github/workflows/build-apks.yml`](.github/workflows/build-apks.yml) builds both apps on every
push and can be started by hand: **Actions → Build the two APKs → Run workflow**.

When it finishes, open the run and download the artifact **`lean-tv-apks`**. It contains one
`app-release.apk` per app, in the folders `launcher/` and `phone/`. GitHub keeps artifacts for 90 days.

The workflow installs JDK 17 and the SDK itself, so the only thing it needs is the source in this repository.

## Option 2: one command here

```bash
bash build.sh        # Linux, macOS, Git Bash on Windows
```

```powershell
.\build.ps1          # PowerShell on Windows
```

It builds both apps and prints the two APKs with their sizes.

## Option 3: the two commands by hand

```bash
# the launcher (TV)
cd launcher
./gradlew --no-daemon assembleRelease
# -> launcher/app/build/outputs/apk/release/app-release.apk

# the phone remote
cd ../phone
./gradlew --no-daemon assembleRelease
# -> phone/app/build/outputs/apk/release/app-release.apk
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## The checks that were run before this was published

```bash
cd launcher && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
cd ../phone  && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

Expected: launcher `BUILD SUCCESSFUL`, **0 lint errors**, **328 tests**, APK **617,850 B**; remote
`BUILD SUCCESSFUL`, **0 lint errors**, **10 tests**, APK **24,632 B**. If your build is a few bytes different in
size, that is the debug signature block, not a different app.

Check what came out:

```bash
$ANDROID_HOME/build-tools/34.0.0/aapt2 dump badging launcher/app/build/outputs/apk/release/app-release.apk | head -3
# package: name='com.gathin.tvhome' versionName='4.0'
# application-label:'Lean TV'
```

## Signing

Both release builds are signed with the **debug** key, which is what makes them installable by sideload without
any setup. That is fine for a TV in your house and wrong for publishing: use your own keystore before you put an
APK in a store, and never commit a keystore or its passwords.

## Troubleshooting

| Message | What it means |
|---|---|
| `SDK location not found` | Set `ANDROID_HOME`, or add `sdk.dir=` to `local.properties` in that project |
| `Unsupported class file major version` | You are on the wrong JDK. Both apps need **JDK 17** |
| `Failed to install build-tools;34.0.0` | Install it: `sdkmanager "platforms;android-34" "build-tools;34.0.0"` |
| `./gradlew: Permission denied` | `chmod +x launcher/gradlew phone/gradlew` |
| `Could not find com.android.application:8.5.2` | No network, or a proxy blocking `google()` / `mavenCentral()` |
| Lint is not 0 errors | The build still produced an APK; findings are in `app/build/reports/lint-results-release.txt` |

## Turning the launcher's four additions off

They are on in this repository's `launcher/app/src/main/res/values/config.xml`:

```xml
<bool name="folders">true</bool>
<bool name="pin_lock">true</bool>
```

Set either to `false` and rebuild for the plain cleanyourtv behaviour.
