# JNI looks these up by name.
-keep class com.freeaudiobypasser.usbaudio.NativeBridge { native <methods>; }
-keep class com.freeaudiobypasser.usbaudio.UsbAudioException { <init>(java.lang.String); }
