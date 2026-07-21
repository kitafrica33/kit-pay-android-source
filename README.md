# Kit Pay for Android — corresponding source

This repository contains the corresponding source for released Kit Pay Android
binaries. Each public release is identified by an annotated
`v<versionName>-code<versionCode>` tag and is licensed under GNU AGPL-3.0-only,
except for third-party components that retain their own licences.

The source tree is exported from the reviewed private release commit. It omits
marketing artwork, product mock-ups, internal operational records, signing keys,
server credentials and unrelated Git history because none of those items is needed
to build or modify the Android application. `SOURCE_PROVENANCE.json` maps the public
tree to the reviewed internal commit, and `SHA256SUMS` authenticates every exported
file.

## Build

See [BUILDING.md](BUILDING.md). In summary, install JDK 21 and Android SDK platform
36, then run:

```bash
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
export KIT_PAY_FIREBASE_CONFIG="$PWD/config/firebase/google-services.json"
python3 -B third_party/livekit/verify.py --root "$PWD"
./gradlew verifySecureMessagingDependencyGate verifyLocalSmsAuthPolicy verifyVendoredLiveKit
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug
./gradlew assembleDebug
./gradlew bundleRelease
./gradlew assembleRelease -PKIT_PAY_SIDELOAD_ABI=arm64-v8a
```

The bundled Firebase file contains public Android client identifiers only. It does
not contain a service-account key or any backend credential. Release APKs and AABs
produced by Gradle are unsigned; use your own signing key for modified builds. The AAB
retains all supported ABIs, while the separately built official-layout sideload APK
contains only arm64-v8a.

## Licence and notices

Kit Pay for Android is free software under AGPL-3.0-only and is provided without
any warranty. Read [LICENSE](LICENSE), [NOTICE](NOTICE),
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md), and
[TRADEMARKS.md](TRADEMARKS.md) before redistributing a build.

The official project is not affiliated with or endorsed by Signal. Signal does not
support Kit Pay's use of libsignal.
