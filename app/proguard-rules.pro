# sshj / BouncyCastle use reflection for algorithm lookup
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.**
# termlib JNI callbacks
-keep class org.connectbot.terminal.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class dev.pocketlab.**$$serializer { *; }
-keepclassmembers class dev.pocketlab.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
