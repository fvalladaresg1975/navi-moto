# R8 rules for the release build. Activities, services and the notification listener are
# kept automatically through the manifest; JSON parsing uses org.json (part of Android),
# so there is no reflection-based serialisation to protect.

# Keep line numbers so crash stack traces from the release build stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
