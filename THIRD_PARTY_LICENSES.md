# Android runtime third-party licences

Audit snapshot: 2026-07-21  
Application ID: `com.kit.wallet`  
Version name: `0.2.0`  
Version code: `12`  
Distribution clearance: `true`  
Distribution review disposition: `CLEARED`  
Distribution review record type: `INTERNAL_DISTRIBUTION_CLEARANCE_APPROVAL`  
Distribution review recorded date: `2026-07-21`  
Distribution review date: `2026-07-21`  
Distribution review reference: `KIT-PAY-AGPL-GOOGLE-FCM-CLEARANCE-2026-0721-002`  
Distribution review issue IDs: `GOOGLE_ANDROID_SDK_AGPL_COMPATIBILITY`  
Distribution reviewer name: `Namisi Arnold Paul`  
Distribution reviewer organization: `Kit Pos Uganda Limited`  
Distribution reviewer qualification: `Chief Executive Officer (CEO) and Product Owner`  
Distribution reviewer jurisdiction: `Uganda`  
Applicable terms title: `Android Software Development Kit License Agreement`  
Applicable terms version: `Effective 2026-04-28`  
Applicable terms effective date: `2026-04-28`  
Applicable terms retrieval date: `2026-07-20`  
Applicable terms URL: `https://developer.android.com/studio/terms`  
Applicable terms SHA-256: `8bd88dc1144a7d12818687d680d6a9f9e8a2f1ee62c43a8e21f5c6a75f6977cd`  
Decision document: `docs/google-fcm-agpl-clearance-decision-code12.md`  
Decision document SHA-256: `699c8cc8671235bf2de907c8b0d2f679ffd25c1c9a0d51fa970ddaec80963b18`  
Reviewed runtime inventory SHA-256: `4f63a48022e82f037a9c3e8de80d847f2a54b5948ce6f51be8a8863bd83ca330`  
Reviewed runtime graph manifest SHA-256: `1602aeac63688c2dd85062b6b951aa58e7df1715b7da125b1f487efcf385a5a9`  
Distribution conditions recorded: `6`  
Re-review triggers recorded: `6`  
Unresolved issue IDs: `none`

This is the release-runtime licence inventory, not legal advice or a publication approval. It records dependency metadata and verified upstream evidence for the exact resolved build. A licence label in this table does not cure incompatible terms or missing redistribution permission.

## Scope and method

Gradle's `releaseRuntimeClasspath` identifies 188 Maven components: 32 direct and 156 transitive. Those components resolve to 151 AAR/JAR artifact archives. The release AAB must reproduce this inventory in `BUNDLE-METADATA/com.android.tools.build.libraries/dependencies.pb` before clearance can change. `com.android.tools:desugar_jdk_libs:2.1.5` is reviewed separately because Android's build tools rewrite its code into the application DEX from the `coreLibraryDesugaring` configuration.

Licence evidence was checked from exact resolved POMs, artifact contents and, where POM/archive evidence was incomplete, version-pinned upstream sources. The normalized 188-component result is:

| Evidence category | Components |
| --- | ---: |
| Apache-2.0 | 175 |
| Android SDK License | 6 |
| BSD-3-Clause | 3 |
| AGPL-3.0-only | 2 |
| MIT | 2 |

The separate desugaring input is GPL-2.0 with the Classpath/assembly exceptions.

## Distribution clearance and continuing conditions

The internal distribution clearance decision
`KIT-PAY-AGPL-GOOGLE-FCM-CLEARANCE-2026-0721-002` records `CLEARED` for the
exact reviewed runtime. It is signed by Namisi Arnold Paul for Kit Pos Uganda Limited
in the stated capacity of Chief Executive Officer (CEO) and Product Owner. The record
is classified as an internal distribution clearance approval; it is not described as
an external-counsel opinion.

Based on the reviewed architecture and dependency usage, the distribution is approved. The Google client libraries are treated as independent third-party components distributed under their applicable Google terms, while Kit Pay source obligations remain governed by the applicable AGPL requirements. No additional Signal permission is required for the reviewed libsignal usage.

The recorded distribution conditions are:

1. The exact reviewed dependency versions must remain unchanged.
2. Any Google SDK or Firebase dependency change requires re-review.
3. Any libsignal version or licence change requires re-review.
4. Required third-party notices must remain included.
5. The final release AAB dependency inventory must match the reviewed inventory.
6. Any application-architecture change affecting third-party linking boundaries requires review.

The recorded re-review triggers are:

1. Dependency version changes.
2. Changes to Google licensing terms affecting reviewed components.
3. Migration away from the reviewed FCM architecture.
4. Changes to libsignal licensing.
5. Changes to the distribution model.
6. Introduction of additional proprietary SDKs.

The release gates compare the resolved six Google/Firebase AARs and POMs byte-for-byte
with the reviewed hashes, bind the complete 188-component runtime inventory, preserve
the required notices, and verify the attested Gradle, CycloneDX and final-AAB dependency
graphs before distribution. The graph-manifest digest is derived release evidence, not
additional reviewer identity or a replacement legal opinion. A trigger
invalidates this clearance until a replacement decision is recorded.

### Resolved JAIN-free LiveKit evidence

Kit Pay consumes only the checked-in `africa.kit.livekit:livekit-android:2.27.0-kitpay.1`
publication. The fork commit is `0387bfe237332e049dfdeba55f44c905628c5e3e`,
based on upstream LiveKit Android 2.27.0 commit
`5011da6fc302fefcdc869faecae2e07055f1c8c5`. It removes
`javax.sip:android-jain-sip-ri` and replaces the used SDP surface with an internal,
lossless line model. The AAR SHA-256 is
`875ca7e0a1b9768f414440d2889504d5192800840a385fbe94cd407096724a9b`.

The Maven publication, exact fork patch, licences/notices, protocol submodule and
deterministic full source archive are retained under `third_party/livekit/`.
`PROVENANCE.json` pins all primary bytes and source commits. The checked-in verifier
rejects altered hashes, unsafe archives, an old/remote coordinate, `mavenLocal()`, or
JAIN-SIP metadata/classes. The fork source archive SHA-256 is
`9f497ee396db5d1d18c1753155bc055beb35b3818e13b3b36c9829084c0e96e6`.

### Resolved WebRTC evidence

`io.github.webrtc-sdk:android-prefixed:144.7559.09` is a layered distribution, not a BSD/MIT contradiction. The `webrtc-sdk/android` repository's MIT licence governs its packaging and publication wrapper. Its compiled payload is the BSD-3-Clause WebRTC baseline with Apache-2.0 Shiguredo/LiveKit fork modifications and generated third-party terms. The Maven POM's BSD-3-Clause label describes the primary WebRTC payload but does not replace the other retained terms.

The exact GitHub Actions artifact, build package, release AAR and Maven AAR were verified by SHA-256. All four Maven native libraries are byte-identical to the release inputs after the upstream filename change, and all 426 Java classes map exactly through the upstream `org/...` to `livekit/org/...` relocation. The exact generated M144 notice, fork licences/notices, wrapper licence, build `VERSIONS` file and provenance manifest are bundled. The reviewed component evidence is: `Composite: BSD-3-Clause (WebRTC baseline) + Apache-2.0 (fork modifications) + generated third-party terms; MIT packaging wrapper`.

The application's `design/`, `brand/` and design-font files were not inputs to the resolved Android runtime. Their ownership and publication permissions require a separate asset review before they are placed in a public source release.

## Notices retained in the binary

The build retains dependency-supplied `META-INF` licence/notice resources and generates `assets/legal/GOOGLE_BUNDLED_THIRD_PARTY_NOTICES.txt` from the Google AARs. The generated file contains all 20 unique embedded notice blocks (27 project labels), byte-for-byte, with source-archive labels and SHA-256 hashes. Duplicate blocks are stored once.

Version-pinned source texts are bundled at:

- `app/src/main/assets/legal/AGPL-3.0-only.txt`
- `app/src/main/assets/legal/APACHE-2.0.txt`
- `app/src/main/assets/legal/LIVEKIT_NOTICE.txt`
- `app/src/main/assets/legal/AUTH0_JWTDECODE_LICENSE.txt`
- `app/src/main/assets/legal/SEMVER4J_LICENSE.txt`
- `app/src/main/assets/legal/PROTOBUF_BSD-3-Clause.txt`
- `app/src/main/assets/legal/WEBRTC_ANDROID_WRAPPER_MIT_LICENSE.txt`
- `app/src/main/assets/legal/WEBRTC_FORK_BSD-3-Clause.txt`
- `app/src/main/assets/legal/WEBRTC_FORK_APACHE_NOTICE.txt`
- `app/src/main/assets/legal/WEBRTC_NATIVE_THIRD_PARTY_NOTICES.md`
- `app/src/main/assets/legal/WEBRTC_BUILD_VERSIONS.txt`
- `app/src/main/assets/legal/WEBRTC_PROVENANCE.json`
- `app/src/main/assets/legal/MPL-2.0.txt`
- `app/src/main/assets/legal/DESUGAR_JDK_LIBS_GPL-2.0.txt`
- `app/src/main/assets/legal/DESUGAR_JDK_LIBS_ASSEMBLY_EXCEPTION.txt`
- `app/src/main/assets/legal/DESUGAR_JDK_LIBS_ADDITIONAL_LICENSE_INFO.txt`

Important exact-source references:

- Kit Pay LiveKit fork 2.27.0-kitpay.1: commit `0387bfe237332e049dfdeba55f44c905628c5e3e`, based on upstream 2.27.0 commit `5011da6fc302fefcdc869faecae2e07055f1c8c5`.
- AudioSwitch: commit `039a35aefab7747c557242fa216c9ea11743b604`, Apache-2.0, Copyright 2020 Twilio Inc.
- Auth0 JWTDecode 2.0.2: MIT, Copyright 2016 Auth0.
- semver4j 3.1.0: commit `88912638db3f6112a2b345f1638ced33a0a606e1`, MIT, Copyright 2015-present Vincent Durmont.
- protobuf-javalite 3.22.0 and AndroidX's repackaged external protobuf: BSD-3-Clause, Copyright 2008 Google Inc.
- WebRTC Android wrapper tag `v144.7559.09`: commit `a46e9a7f63ce2b531252313f4e81754998e78f9a`.
- WebRTC build tag `m144.7559.09`: commit `6097d61fb396e7c990cf12094ac3147853377d2d`; exact fork source commit `b1800a61db8320af5c14456c13622d8b85b1ed39`.
- GitHub Actions artifact `7555285078`: SHA-256 `579ca3c4ea28272388103a2db059193dd914ef80f9d2154e76ddcf9150bb3106`; release AAR SHA-256 `33ac1fa52b8d3582d557d4ed57bd9617a91edbfa52abdb87eb6886183befc262`.
- Maven AAR SHA-256 `d2542864ce012f188d0b2d5da21f5cc48bacc6d46523d25f7515809d424780c6`; exact native notice SHA-256 `bb8a8a3c8c9898f79fa537d71273a10d700ba6f1205d81d3c9c525dfcd3ec93e`.
- OkHttp's embedded Public Suffix List notice identifies Mozilla Public License 2.0.
- `desugar_jdk_libs:2.1.5`: GPL-2.0 with Classpath/assembly exceptions.

## Native payload

Each family below is packaged for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`:

| Component | Packaged library | Evidence |
| --- | --- | --- |
| `androidx.datastore:datastore-core-android:1.1.3` | `libdatastore_shared_counter.so` | Apache-2.0 |
| `androidx.graphics:graphics-path:1.0.1` | `libandroidx.graphics.path.so` | Apache-2.0 |
| `io.github.webrtc-sdk:android-prefixed:144.7559.09` | `liblkjingle_peerconnection_so.so` | Composite terms verified; exact four-ABI byte match and M144 notices bundled |
| `org.signal:libsignal-android:0.97.4` | `libsignal_jni.so` | AGPL-3.0-only; testing JNI libraries excluded |

## Exact Maven inventory

The “declared evidence” column reports metadata/evidence, not a compatibility conclusion.

| Coordinate | Relationship | Declared evidence |
| --- | --- | --- |
| `africa.kit.livekit:livekit-android:2.27.0-kitpay.1` | direct | `Apache-2.0` |
| `androidx.activity:activity-compose:1.9.3` | direct | `Apache-2.0` |
| `androidx.activity:activity-ktx:1.9.3` | transitive | `Apache-2.0` |
| `androidx.activity:activity:1.9.3` | transitive | `Apache-2.0` |
| `androidx.annotation:annotation-experimental:1.4.1` | transitive | `Apache-2.0` |
| `androidx.annotation:annotation-jvm:1.9.1` | transitive | `Apache-2.0` |
| `androidx.annotation:annotation:1.9.1` | transitive | `Apache-2.0` |
| `androidx.arch.core:core-common:2.2.0` | transitive | `Apache-2.0` |
| `androidx.arch.core:core-runtime:2.2.0` | transitive | `Apache-2.0` |
| `androidx.autofill:autofill:1.0.0` | transitive | `Apache-2.0` |
| `androidx.collection:collection-jvm:1.4.4` | transitive | `Apache-2.0` |
| `androidx.collection:collection-ktx:1.4.4` | transitive | `Apache-2.0` |
| `androidx.collection:collection:1.4.4` | transitive | `Apache-2.0` |
| `androidx.compose.animation:animation-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.animation:animation-core-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.animation:animation-core:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.animation:animation:1.7.6` | direct | `Apache-2.0` |
| `androidx.compose.foundation:foundation-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.foundation:foundation-layout-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.foundation:foundation-layout:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.foundation:foundation:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.material3:material3-android:1.3.1` | transitive | `Apache-2.0` |
| `androidx.compose.material3:material3:1.3.1` | direct | `Apache-2.0` |
| `androidx.compose.material:material-icons-core-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.material:material-icons-core:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.material:material-icons-extended-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.material:material-icons-extended:1.7.6` | direct | `Apache-2.0` |
| `androidx.compose.material:material-ripple-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.material:material-ripple:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.runtime:runtime-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.runtime:runtime-saveable-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.runtime:runtime-saveable:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.runtime:runtime:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-geometry-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-geometry:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-graphics-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-graphics:1.7.6` | direct | `Apache-2.0` |
| `androidx.compose.ui:ui-text-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-text:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-tooling-preview-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-tooling-preview:1.7.6` | direct | `Apache-2.0` |
| `androidx.compose.ui:ui-unit-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-unit:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-util-android:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui-util:1.7.6` | transitive | `Apache-2.0` |
| `androidx.compose.ui:ui:1.7.6` | direct | `Apache-2.0` |
| `androidx.compose:compose-bom:2024.12.01` | direct | `Apache-2.0` |
| `androidx.concurrent:concurrent-futures-ktx:1.1.0` | transitive | `Apache-2.0` |
| `androidx.concurrent:concurrent-futures:1.1.0` | transitive | `Apache-2.0` |
| `androidx.core:core-ktx:1.15.0` | direct | `Apache-2.0` |
| `androidx.core:core-splashscreen:1.0.1` | direct | `Apache-2.0` |
| `androidx.core:core:1.15.0` | transitive | `Apache-2.0` |
| `androidx.customview:customview-poolingcontainer:1.0.0` | transitive | `Apache-2.0` |
| `androidx.customview:customview:1.0.0` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore-android:1.1.3` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore-core-android:1.1.3` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore-core-okio-jvm:1.1.3` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore-core-okio:1.1.3` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore-core:1.1.3` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore-preferences-android:1.1.3` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore-preferences-core-jvm:1.1.3` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore-preferences-core:1.1.3` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore-preferences-external-protobuf:1.1.3` | transitive | `BSD-3-Clause` |
| `androidx.datastore:datastore-preferences-proto:1.1.3` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore-preferences:1.1.3` | transitive | `Apache-2.0` |
| `androidx.datastore:datastore:1.1.3` | transitive | `Apache-2.0` |
| `androidx.documentfile:documentfile:1.0.0` | transitive | `Apache-2.0` |
| `androidx.emoji2:emoji2:1.3.0` | transitive | `Apache-2.0` |
| `androidx.fragment:fragment:1.5.1` | transitive | `Apache-2.0` |
| `androidx.graphics:graphics-path:1.0.1` | transitive | `Apache-2.0` |
| `androidx.hilt:hilt-common:1.2.0` | transitive | `Apache-2.0` |
| `androidx.hilt:hilt-navigation-compose:1.2.0` | direct | `Apache-2.0` |
| `androidx.hilt:hilt-navigation:1.2.0` | transitive | `Apache-2.0` |
| `androidx.hilt:hilt-work:1.2.0` | direct | `Apache-2.0` |
| `androidx.interpolator:interpolator:1.0.0` | transitive | `Apache-2.0` |
| `androidx.legacy:legacy-support-core-utils:1.0.0` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-common-java8:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-common-jvm:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-common:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-livedata-core:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-livedata:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-process:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-runtime-android:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-runtime-compose-android:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-runtime-compose:2.8.7` | direct | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-runtime-ktx-android:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` | direct | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-runtime:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-service:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-viewmodel-android:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-viewmodel-compose-android:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7` | direct | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7` | transitive | `Apache-2.0` |
| `androidx.lifecycle:lifecycle-viewmodel:2.8.7` | transitive | `Apache-2.0` |
| `androidx.loader:loader:1.0.0` | transitive | `Apache-2.0` |
| `androidx.localbroadcastmanager:localbroadcastmanager:1.0.0` | transitive | `Apache-2.0` |
| `androidx.navigation:navigation-common-ktx:2.8.5` | transitive | `Apache-2.0` |
| `androidx.navigation:navigation-common:2.8.5` | transitive | `Apache-2.0` |
| `androidx.navigation:navigation-compose:2.8.5` | direct | `Apache-2.0` |
| `androidx.navigation:navigation-runtime-ktx:2.8.5` | transitive | `Apache-2.0` |
| `androidx.navigation:navigation-runtime:2.8.5` | transitive | `Apache-2.0` |
| `androidx.print:print:1.0.0` | transitive | `Apache-2.0` |
| `androidx.profileinstaller:profileinstaller:1.3.1` | transitive | `Apache-2.0` |
| `androidx.room:room-common-jvm:2.7.2` | transitive | `Apache-2.0` |
| `androidx.room:room-common:2.7.2` | transitive | `Apache-2.0` |
| `androidx.room:room-ktx:2.7.2` | direct | `Apache-2.0` |
| `androidx.room:room-runtime-android:2.7.2` | transitive | `Apache-2.0` |
| `androidx.room:room-runtime:2.7.2` | direct | `Apache-2.0` |
| `androidx.savedstate:savedstate-ktx:1.2.1` | transitive | `Apache-2.0` |
| `androidx.savedstate:savedstate:1.2.1` | transitive | `Apache-2.0` |
| `androidx.sqlite:sqlite-android:2.5.1` | transitive | `Apache-2.0` |
| `androidx.sqlite:sqlite-framework-android:2.5.1` | transitive | `Apache-2.0` |
| `androidx.sqlite:sqlite-framework:2.5.1` | transitive | `Apache-2.0` |
| `androidx.sqlite:sqlite:2.5.1` | transitive | `Apache-2.0` |
| `androidx.startup:startup-runtime:1.1.1` | transitive | `Apache-2.0` |
| `androidx.tracing:tracing-ktx:1.2.0` | transitive | `Apache-2.0` |
| `androidx.tracing:tracing:1.2.0` | transitive | `Apache-2.0` |
| `androidx.versionedparcelable:versionedparcelable:1.1.1` | transitive | `Apache-2.0` |
| `androidx.viewpager:viewpager:1.0.0` | transitive | `Apache-2.0` |
| `androidx.work:work-runtime-ktx:2.10.0` | direct | `Apache-2.0` |
| `androidx.work:work-runtime:2.10.0` | transitive | `Apache-2.0` |
| `com.auth0.android:jwtdecode:2.0.2` | transitive | `MIT` |
| `com.github.davidliu:audioswitch:039a35aefab7747c557242fa216c9ea11743b604` | transitive | `Apache-2.0` |
| `com.google.android.datatransport:transport-api:3.1.0` | transitive | `Apache-2.0` |
| `com.google.android.datatransport:transport-backend-cct:3.1.9` | transitive | `Apache-2.0` |
| `com.google.android.datatransport:transport-runtime:3.1.9` | transitive | `Apache-2.0` |
| `com.google.android.gms:play-services-base:18.1.0` | transitive | `LicenseRef-Android-SDK-License` |
| `com.google.android.gms:play-services-basement:18.3.0` | transitive | `LicenseRef-Android-SDK-License` |
| `com.google.android.gms:play-services-cloud-messaging:17.2.0` | transitive | `LicenseRef-Android-SDK-License` |
| `com.google.android.gms:play-services-stats:17.0.2` | transitive | `LicenseRef-Android-SDK-License` |
| `com.google.android.gms:play-services-tasks:18.1.0` | transitive | `LicenseRef-Android-SDK-License` |
| `com.google.code.findbugs:jsr305:3.0.2` | transitive | `Apache-2.0` |
| `com.google.code.gson:gson:2.8.9` | transitive | `Apache-2.0` |
| `com.google.dagger:dagger-lint-aar:2.58` | transitive | `Apache-2.0` |
| `com.google.dagger:dagger:2.58` | transitive | `Apache-2.0` |
| `com.google.dagger:hilt-android:2.58` | direct | `Apache-2.0` |
| `com.google.dagger:hilt-core:2.58` | transitive | `Apache-2.0` |
| `com.google.errorprone:error_prone_annotations:2.26.0` | transitive | `Apache-2.0` |
| `com.google.firebase:firebase-annotations:17.0.0` | transitive | `Apache-2.0` |
| `com.google.firebase:firebase-bom:34.1.0` | direct | `Apache-2.0` |
| `com.google.firebase:firebase-common:22.0.0` | transitive | `Apache-2.0` |
| `com.google.firebase:firebase-components:19.0.0` | transitive | `Apache-2.0` |
| `com.google.firebase:firebase-datatransport:18.2.0` | transitive | `Apache-2.0` |
| `com.google.firebase:firebase-encoders-json:18.0.0` | transitive | `Apache-2.0` |
| `com.google.firebase:firebase-encoders-proto:16.0.0` | transitive | `Apache-2.0` |
| `com.google.firebase:firebase-encoders:17.0.0` | transitive | `Apache-2.0` |
| `com.google.firebase:firebase-iid-interop:17.1.0` | transitive | `LicenseRef-Android-SDK-License` |
| `com.google.firebase:firebase-installations-interop:17.1.1` | transitive | `Apache-2.0` |
| `com.google.firebase:firebase-installations:19.0.0` | transitive | `Apache-2.0` |
| `com.google.firebase:firebase-messaging:25.0.0` | direct | `Apache-2.0` |
| `com.google.guava:listenablefuture:1.0` | transitive | `Apache-2.0` |
| `com.google.protobuf:protobuf-javalite:3.22.0` | transitive | `BSD-3-Clause` |
| `com.squareup.moshi:moshi-kotlin:1.15.2` | direct | `Apache-2.0` |
| `com.squareup.moshi:moshi:1.15.2` | direct | `Apache-2.0` |
| `com.squareup.okhttp3:logging-interceptor:4.12.0` | direct | `Apache-2.0` |
| `com.squareup.okhttp3:okhttp:4.12.0` | direct | `Apache-2.0` |
| `com.squareup.okio:okio-jvm:3.7.0` | transitive | `Apache-2.0` |
| `com.squareup.okio:okio:3.7.0` | transitive | `Apache-2.0` |
| `com.squareup.retrofit2:converter-moshi:2.11.0` | direct | `Apache-2.0` |
| `com.squareup.retrofit2:retrofit:2.11.0` | direct | `Apache-2.0` |
| `com.vdurmont:semver4j:3.1.0` | transitive | `MIT` |
| `io.github.webrtc-sdk:android-prefixed:144.7559.09` | transitive | `Composite: BSD-3-Clause (WebRTC baseline) + Apache-2.0 (fork modifications) + generated third-party terms; MIT packaging wrapper` |
| `jakarta.inject:jakarta.inject-api:2.0.1` | transitive | `Apache-2.0` |
| `javax.inject:javax.inject:1` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlin:kotlin-android-extensions-runtime:1.9.22` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlin:kotlin-parcelize-runtime:1.9.22` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlin:kotlin-reflect:1.8.21` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlin:kotlin-stdlib-common:2.2.20` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.25` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.21` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlin:kotlin-stdlib:2.2.20` | direct | `Apache-2.0` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom:1.9.0` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.9.0` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0` | transitive | `Apache-2.0` |
| `org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0` | transitive | `Apache-2.0` |
| `org.jetbrains:annotations:23.0.0` | transitive | `Apache-2.0` |
| `org.jspecify:jspecify:1.0.0` | transitive | `Apache-2.0` |
| `org.signal:libsignal-android:0.97.4` | direct | `AGPL-3.0-only` |
| `org.signal:libsignal-client:0.97.4` | direct | `AGPL-3.0-only` |
| `com.android.tools:desugar_jdk_libs:2.1.5` | coreLibraryDesugaring | `GPL-2.0-with-Classpath-Exception` |
