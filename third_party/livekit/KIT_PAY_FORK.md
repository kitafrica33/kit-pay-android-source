# Kit Pay LiveKit Android fork

This fork is based exactly on LiveKit Android SDK tag `v2.27.0`, commit
`5011da6fc302fefcdc869faecae2e07055f1c8c5`, with the `protocol` submodule
pinned by that commit to `8381f2180c45ab926b3ebf19df0608f1dadcac1e`.

The fork removes `javax.sip:android-jain-sip-ri` and replaces the small SDP
surface used by `PeerConnectionTransport` with a lossless internal line model.
The model preserves original lines, ordering, duplicates, unknown fields,
media-section boundaries, line-ending style, and trailing-newline state. It
only mutates the targeted `fmtp` value or inserts the required dependency
descriptor `extmap` attribute.

## Verify the focused behavior

```bash
git submodule update --init --recursive
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  ./gradlew --no-daemon --no-parallel \
  :livekit-android-test:testDebugUnitTest \
  --tests io.livekit.android.webrtc.SdpUtilsTest \
  --tests io.livekit.android.room.SdpMungingTest
```

## Publish the reviewed local repository

Publish into this checkout's deterministic `build/kit-pay-maven` repository without
invoking the upstream signing or remote repository configuration:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  ./gradlew --no-daemon --no-parallel \
  -PRELEASE_SIGNING_ENABLED=false \
  :livekit-android-sdk:publishReleasePublicationToKitPayLocalRepository
```

The pinned fork coordinate is:

```text
africa.kit.livekit:livekit-android:2.27.0-kitpay.1
```

Kit Pay vendors the exact output, hashes it, and declares that repository before Maven
Central. Consumer builds must use the exact version (never a dynamic version):

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("third_party/livekit/maven") }
        google()
        mavenCentral()
    }
}

dependencies {
    implementation("africa.kit.livekit:livekit-android:2.27.0-kitpay.1")
}
```

The SDK remains Apache-2.0. `SdpUtils.kt` retains both the upstream Apache-2.0
notice and the MIT notice for the portions derived from `ggarber/sdpparser`.
