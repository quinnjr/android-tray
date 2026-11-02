# Edge Drawer

A swipeable edge handle that slides out a drawer of your favourite apps, on any Android 10+ phone.

- **Swipe** the handle inward to open the drawer; tap an app to launch it.
- **Long-press and drag** the handle to move it up/down, or across to the other edge.
- Close the drawer by tapping outside, swiping it back toward the edge, or going back.
- The handle hides while an app is full-screen (videos, games).

## Build

Requires JDK 21 and the Android SDK (`ANDROID_HOME`, platform 37).

```sh
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Or let GitHub build it: the **Build APK** workflow runs on pushes to `main`/`develop`, on pull
requests, and by hand (*Actions → Build APK → Run workflow*, any branch). The debug APK is
attached to the run as an artifact (`edge-drawer-<version>-<commit>-debug`), next to the test
and lint reports.

### Production build

The release build is shrunk with R8 and signed with your release key, read from the environment
so it never enters the repository. In CI it comes from four repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | the keystore file, base64-encoded (`base64 -w0 release.jks`) |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
| `RELEASE_KEY_ALIAS` | the key's alias |
| `RELEASE_KEY_PASSWORD` | the key's password |

Create a key once (keep the file and passwords safe: updates must be signed with the same key):

```sh
keytool -genkeypair -keystore release.jks -alias edge-drawer -keyalg RSA -keysize 4096 -validity 10000
```

The workflow's release job then builds the signed APK and an AAB (for Google Play) on pushes to
`main`, on manual runs and on version tags; pushing a tag like `v0.1.0` also publishes the APK as a
GitHub release. Locally, set `RELEASE_KEYSTORE_PATH` (to the `.jks` file) and the three other
variables, then run `./gradlew assembleRelease bundleRelease`.

## Install

Enable *Developer options → Wireless debugging* on the phone, then:

```sh
adb pair <ip>:<pairing-port>       # enter the 6-digit code
adb connect <ip>:<port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Edge Drawer**, allow *Display over other apps* and notifications, pick your apps, and turn on the handle.
