# Kit Wallet application-specific R8 rules. AndroidX, Hilt, Room, Firebase and
# LiveKit contribute their consumer rules; these entries preserve reflection DTOs.

# DTOs use Moshi reflection; preserve their Kotlin metadata and serialized fields.
-keep class com.kit.wallet.data.remote.** { *; }
-keep class com.kit.wallet.data.session.SessionDiskPayload { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Retrofit service method annotations are inspected at runtime.
-keep,allowoptimization,allowshrinking interface com.kit.wallet.data.remote.KitWalletApi
-keep,allowoptimization,allowshrinking interface com.kit.wallet.data.remote.SecureMessagingWireApi
-keep,allowoptimization,allowshrinking interface com.kit.wallet.data.remote.SessionRefreshApi

# Firebase Messaging treats its excluded analytics connector as an optional integration.
-dontwarn com.google.firebase.analytics.connector.AnalyticsConnector
