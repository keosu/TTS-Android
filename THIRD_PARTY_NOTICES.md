# Edge TTS module

`edgeapp/src/main/java/com/mosstts/edge/protocol/EdgeGec.kt` is a Kotlin adaptation
of [`edge-tts`'s `drm.py`](https://github.com/rany2/edge-tts/blob/b2ffbdd9f43f28f03cc939b5de0f3964aa62d60e/src/edge_tts/drm.py).
Copyright (c) rany <rany@riseup.net> and edge-tts contributors. This file is
licensed under **LGPL-3.0-only**. Changes replace the Python implementation with
Kotlin/JVM integer arithmetic while preserving its Sec-MS-GEC v2 algorithm.
The remaining application code retains this repository's Apache-2.0 license.

LGPLv3, GPLv3, the application's Apache-2.0 license, and attribution are included
in `edgeapp/src/main/assets/licenses/` and accessible through the application's
“开源许可与源代码” button. OkHttp, Okio, and the Kotlin standard library use Apache-2.0.

The complete corresponding source, including the modified library and application
build files, is available in this repository alongside each build's commit.
To use a modified library, edit `EdgeGec.kt`, then run:

```sh
./gradlew :edgeapp:assembleDebug
adb install -r edgeapp/build/outputs/apk/debug/edgeapp-debug.apk
```

Install Android SDK 34 and JDK 17 or 21 first. If an installed APK uses a different
key, uninstall that Edge TTS app first or provide your own signing key in
`app/release.keystore`. Debug builds use a locally generated debug key and require
no repository secrets. MOSS and Edge use separate package names, so reinstalling
Edge does not require reinstalling MOSS.
