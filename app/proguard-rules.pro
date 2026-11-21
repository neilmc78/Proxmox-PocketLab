# Crypto: no blanket keeps. The full BouncyCastle JCA provider is never registered (see
# SlimCryptoProvider), so nothing is looked up by class name except this one presence check
# sshj does before decrypting legacy passphrase-protected PEM keys:
-keep interface org.bouncycastle.openssl.PEMDecryptor

-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.**

# termlib: native code calls back into these through JNI. The library's own consumer rules keep its
# public API; keep member names of everything it declares too, as cheap insurance for JNI lookups.
-keepclassmembers class org.connectbot.terminal.** { *; }

# kotlinx.serialization (the library ships its own rules; these cover our @Serializable classes)
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class dev.pocketlab.**$$serializer { *; }
-keepclassmembers class dev.pocketlab.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
