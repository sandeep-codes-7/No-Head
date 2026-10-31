-keep class com.nohead.mesh.domain.model.** { *; }
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn org.bouncycastle.**

# Google Tink / ErrorProne compile-only annotations
-dontwarn com.google.errorprone.annotations.**

