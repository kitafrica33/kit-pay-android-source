# Building and installing Kit Pay Android

## Requirements

- a 64-bit development host;
- JDK 21;
- Android SDK platform 36 and the matching Android Build Tools selected by AGP;
- network access to Google Maven, Maven Central, Gradle Plugin Portal, JitPack and
  Signal's group-filtered build-artifact repository; and
- an Android 8.0 (API 26) or later device or emulator.

The Gradle wrapper and dependency catalogue are part of this source release. The
build pins official `org.signal` libsignal 0.97.4 artifacts and verifies their exact
SHA-256 hashes before compiling. The JAIN-free LiveKit fork is included as a
hash-pinned Maven repository under `third_party/livekit`; no unpublished repository
or `mavenLocal()` is required.

## Verify the export

From the repository root:

```bash
sha256sum --check SHA256SUMS
```

`SOURCE_PROVENANCE.json` records the public release tag, internal reviewed commit,
application identity and hash of the public Firebase Android client configuration.

Every build embeds `assets/provenance/KIT_PAY_RELEASE_PROVENANCE.json`. An official
release built in the internal Git worktree records that Git commit and whether the
worktree was dirty. A build from this source archive validates `SOURCE_PROVENANCE.json`
but is deliberately marked `source_origin=public-corresponding-source` and
`source_dirty=true`, because an archive has no Git index that can prove it was not
modified after extraction. An arbitrary source copy without either Git metadata or
`SOURCE_PROVENANCE.json` remains buildable and is marked `unversioned-source`; it cannot
pass Kit's official signed-release provenance gate.

## Configure and build

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

Expected release outputs are:

- `app/build/outputs/apk/release/app-release-unsigned.apk`; and
- `app/build/outputs/bundle/release/app-release.aab`.

Build the AAB first without `KIT_PAY_SIDELOAD_ABI`: it retains arm64-v8a,
armeabi-v7a, x86 and x86_64 for device-specific Play delivery. Build the direct APK
in the separate command shown; Gradle accepts only the exact arm64-v8a value and only
for an explicit `assembleRelease`. Packaged provenance distinguishes the sideload APK
from the Play AAB. Omitting the property can still produce a universal local APK, but
that artifact is not eligible for Kit's official direct-download endpoints.

The production API origin defaults to `https://pay.kit.africa/`. A test build can
use another HTTPS origin:

```bash
./gradlew assembleDebug \
  -PKIT_WALLET_BASE_URL=https://approved-test-origin.example/
```

The included Firebase JSON is an Android client configuration, not a server key.
It is supplied explicitly so the released source contains the same non-secret build
input as the official binary. A modified deployment should create and restrict its
own Firebase client.

## Signing and installation information

The official upload/signing private key is not part of Corresponding Source. It is
not needed to build or modify the program. Sign a modified APK with a key you control,
then install it through Android's normal sideloading or development tools. Android
will not install a differently signed APK as an update over the official package;
uninstall the official package first or use a different application ID for a parallel
development build.

For an AAB, use Google's open-source Bundletool to validate it and generate device
splits. Never use the Android debug key for a production distribution.
