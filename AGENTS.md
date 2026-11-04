# For coding agents

Two Android apps, one Gradle project each, one APK each. Plain Java, no Kotlin, no Compose, no NDK.

| Folder | App | Package | APK it produces |
|---|---|---|---|
| `launcher/` | Lean TV, the TV Home screen | `com.gathin.tvhome` | `launcher/app/build/outputs/apk/release/app-release.apk` |
| `phone/` | Lean TV Remote, the phone app | `com.gathin.leantv.remote` | `phone/app/build/outputs/apk/release/app-release.apk` |

## Build both

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk   # needs platform 34 + build-tools 34.0.0
bash build.sh
```

`build.sh` uses `sh ./gradlew --no-daemon assembleRelease` in each project. There is only one Gradle
version in the repository (Gradle 8.9, AGP 8.5.2); `phone/` carries its own copy of the wrapper so it can
be built alone. Always pass `--no-daemon` in CI and sandboxes.

## Self-check after a build

```bash
cd launcher && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
cd ../phone  && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

Expected on a clean tree:

| | launcher | phone |
|---|---|---|
| lint | 0 errors | 0 errors |
| unit tests | 328 pass | 10 pass |
| APK size | 617,850 B | 24,632 B |
| label | `Lean TV` (`versionName` 4.0) | `Lean TV Remote` (`versionName` 1.0) |

A few bytes of size difference between machines is the debug signature block, not a different app.
Confirm identity with `aapt2 dump badging <apk> | head -3` rather than by byte count alone.

## Rules

- **Do not commit build output.** `build/`, `.gradle/`, `local.properties`, keystores and `.jks` files are
  gitignored. Keep it that way.
- **Never commit a keystore, a password or an SDK path.** Release builds are signed with the debug key on
  purpose so the APKs sideload; a store build needs a real keystore supplied from outside the repo.
- **The launcher's four additions are feature flags.** `folders` and `pin_lock` in
  `launcher/app/src/main/res/values/config.xml` are `true` in this repository and `false` upstream. Keep
  them `true` unless the user asks otherwise, and check they are still `true` after any merge from upstream.
- **This is a fork.** Upstream is [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) (MIT).
  Prefer re-applying upstream changes over rewriting the launcher's own code, so the four additions stay
  small and reviewable.
- **No network permission in the launcher.** It must stay a purely local Home screen. The phone app holds
  the network permission, because it speaks to the TV over its debug port 5555.
- **CI builds both apps** (`.github/workflows/build-apks.yml`) and hands them out as the `lean-tv-apks`
  artifact; its per-app steps skip themselves when that app's source is not present, so a partial checkout
  stays green instead of failing.

## If `launcher/` is missing

The launcher is Gathin23's cleanyourtv plus the Lean TV addition set. Restore it from the full source zip
attached to the `v1.0` release, or from upstream plus the Lean TV changes, then set the two flags above to
`true`. `phone/` is self-contained and needs nothing else.
