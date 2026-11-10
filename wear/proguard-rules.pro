# Guava references annotation/optional classes that aren't on Android.
-dontwarn javax.annotation.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn afu.org.checkerframework.**
-dontwarn java.lang.reflect.AnnotatedType

# Keep readable stack traces in logcat for bug reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
