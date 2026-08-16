# Android instantiates these components by class name from the manifest/XML metadata.
-keep class com.morningsearch.guard.SearchGuardAccessibilityService { *; }
-keep class com.morningsearch.guard.GuardianDeviceAdminReceiver { *; }
-keep class com.morningsearch.guard.EnforcementService { *; }
-keep class com.morningsearch.guard.*Receiver { *; }

# Room ships consumer rules; preserve entity annotations for schema diagnostics.
-keepattributes *Annotation*
