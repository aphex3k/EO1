# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# The WebKit bridge resolves the WebViewClient/WebChromeClient hooks it
# calls by name on the subclass, so an override R8 renames simply never
# fires: no crash, just a kiosk view that cannot tell a page that loaded
# from a page that hung. Keeps the names of the kiosk clients' overrides.
-keep class * extends android.webkit.WebViewClient { *; }
-keep class * extends android.webkit.WebChromeClient { *; }

# Amlogic TsPlayer JNI (RegisterNatives looks up this exact class/methods)
-keep class com.example.tsplayer.TsPlayerNative { *; }

# Retrofit reads each service method's generic return type (Call<T>) via
# reflection at runtime. When R8 renames retrofit2.Call, it erases the
# type argument from the DEX Signature of every method returning it
# (mapping.txt records the erased "residualsignature"), so
# Method.getGenericReturnType() returns a raw Call and
# DefaultCallAdapterFactory throws "Unable to create call adapter".
# Keeping the class under its original name makes R8 write the full
# Call<T> signature.
-keep class retrofit2.Call { *; }

# Paho's LoggerFactory instantiates the class passed to MqttManager's
# LoggerFactory.setLogger() via Class.forName(name).newInstance(); without
# this, R8 strips NoOpLogger's implicit public constructor and the release
# build fails with MissingResourceException("Error locating the logging class").
-keep class com.aphex3k.eo1.mqtt.MqttManager$NoOpLogger { public <init>(); }

# Nothing here protects the app's Gson DTOs, and that absence is deliberate: they are
# protected by @Keep on the class declaration, which androidx.annotation's own consumer
# rule expands into `-keep @androidx.annotation.Keep class * { *; }` — the class and every
# member, name and all. Forty types in app/src/main/java carry it, and every Gson family in
# this app depends on it: the immichApi DTOs (through Retrofit's converter), UpdateManifest,
# UpdateState, Configuration, and since this file grew a comment the five in
# app/src/main/java/com/aphex3k/eo1/mqtt/ as well.
#
# Those five did not, and that is what broke release frames up to versionCode 19. Nothing in
# Java constructs them — Gson does, reflectively, which R8 cannot see — so they met R8's
# "no live constructor" test and its horizontal class merging folded all five into one
# class. Read out of that build's APK with build-tools' dexdump:
#
#   Lq0/b;                    <- com.aphex3k.eo1.mqtt.Payload
#     Access flags      : 0x0401 (PUBLIC ABSTRACT)
#     Instance fields   : none
#     Direct + Virtual methods : 101, all of them other people's static utilities
#
# and mapping.txt carries no class entry at all for TTrack, EnqueuedMetadata, Volume or
# Mute. Gson asked for the state document, found an abstract class with no fields to fill,
# and every `sonos/+` message on every release frame failed with "Abstract classes can't be
# instantiated! … Class name: q0.b". Debug builds were fine (minifyEnabled=false) and the
# JVM suite was fine (the test task never runs R8), so it survived on every release shipped
# with that package in it.
#
# Which leaves the rule for the next person: when a Gson type misbehaves on a release frame
# and not on a debug one, the question is "does its class say @Keep", not "which rule is
# missing from this file". ./publish-update.sh now asks the built APK that question directly
# (its reflection gate), so a missing @Keep costs a release rather than a wall.

