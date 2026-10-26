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

## Install

Enable *Developer options → Wireless debugging* on the phone, then:

```sh
adb pair <ip>:<pairing-port>       # enter the 6-digit code
adb connect <ip>:<port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Edge Drawer**, allow *Display over other apps* and notifications, pick your apps, and turn on the handle.
