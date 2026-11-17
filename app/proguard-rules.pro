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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# The Anthropic SDK (tidying dictated notes) reads and writes JSON by reflection, through Jackson and
# kotlin-reflect, so its classes keep their names and members
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
# Desktop Java classes the SDK's libraries mention but never use here (structured outputs, Java beans)
-dontwarn com.google.auto.value.AutoValue
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType