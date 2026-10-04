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

