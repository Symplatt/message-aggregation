# JavaMail loads providers and activation handlers by class name from META-INF resources.
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.mail.** { *; }
-keep class com.sun.activation.** { *; }
