# Keep Apache MINA
-keep class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }