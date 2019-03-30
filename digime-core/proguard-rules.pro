-keepattributes *Annotation*
-dontwarn java.nio.file.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontnote retrofit2.Platform
-dontnote retrofit2.Platform$IOS$MainThreadExecutor
-dontwarn retrofit2.Platform$Java8
-keepattributes Signature
-keepattributes Exceptions
-keepattributes EnclosingMethod
-keepattributes InnerClassess
-keepclasseswithmembers class * {
  @retrofit2.http.* <methods>;
}

#Gson specific configuration
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }

-keep class me.digi.sdk.core.entities.CAContent.** { *; }
-keep class me.digi.sdk.core.entities.CAFileResponse.** { *; }

#SpongyCastle related keep
#TODO: optimize to include only providers we use
-keep class org.spongycastle.jcajce.provider.**
-keep class org.spongycastle.jce.provider.PKIXCertPathValidatorSpi { *; }

#Gson issue - https://github.com/google/gson/issues/1174
#"Warning: library class com.google.gson.Gson$6 extends or implements program class com.google.gson.TypeAdapter"
-dontwarn com.google.gson.Gson$6