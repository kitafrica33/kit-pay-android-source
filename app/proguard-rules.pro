# Kit Wallet application-specific R8 rules. AndroidX, Hilt, Room, Firebase and
# LiveKit contribute their consumer rules; these entries preserve reflection DTOs.

# DTOs use Moshi reflection; preserve their Kotlin metadata and serialized fields.
-keep class com.kit.wallet.data.remote.** { *; }
-keep class com.kit.wallet.data.session.SessionDiskPayload { *; }
-keep class com.kit.wallet.data.session.SecureMessagingResetProofFence { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Retrofit service method annotations are inspected at runtime.
-keep,allowoptimization,allowshrinking interface com.kit.wallet.data.remote.KitWalletApi
-keep,allowoptimization,allowshrinking interface com.kit.wallet.data.remote.SecureMessagingWireApi
-keep,allowoptimization,allowshrinking interface com.kit.wallet.data.remote.SessionRefreshApi

# Firebase Messaging treats its excluded analytics connector as an optional integration.
-dontwarn com.google.firebase.analytics.connector.AnalyticsConnector

# libsignal's native code calls back into Java by resolving method names and JNI
# descriptors at runtime. R8 must not rename, remove or repackage any libsignal
# type or member, otherwise a minified release build fails on the first store
# callback with e.g. "method not found: loadSession (J)L...NativeHandleGuard$Owner;".
# Keep the whole library (its consumer rules are not sufficient for these internal
# JNI bridges) plus the application's protocol-store implementation, whose
# overridden store methods native code also invokes directly by name.
-keep class org.signal.libsignal.** { *; }
-keepclassmembers class org.signal.libsignal.** { *; }
-keep class com.kit.wallet.data.messaging.LibSignalProtocolStore { *; }
-keepclassmembers class com.kit.wallet.data.messaging.LibSignalProtocolStore { *; }
-keepattributes InnerClasses,EnclosingMethod,Signature
