# NLS HandRing - ProGuard Rules
-keepattributes Signature
-keepattributes *Annotation*

# usb-serial-for-android
-keep class com.hoho.android.usbserial.** { *; }
-dontwarn com.hoho.android.usbserial.**

# Keep our app classes
-keep class com.nls.handring.** { *; }
