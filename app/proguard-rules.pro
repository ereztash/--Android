# R8 rules.
#
# The central risk here: an InputMethodService is instantiated BY THE SYSTEM, by name, from the
# manifest. Nothing inside the app ever references it, so from R8's point of view it is
# unreachable code. Without these rules the release build strips the keyboard out of the
# keyboard, and the failure is a ClassNotFoundException the first time a user selects the IME --
# long after the build went green.

# The IME service and everything the system instantiates by name from the manifest.
-keep class com.hebrewime.ime.HebrewImeService { *; }
-keep class com.hebrewime.settings.OnboardingActivity { *; }
-keep class com.hebrewime.settings.SettingsActivity { *; }

# Views inflated from XML or constructed reflectively keep their (Context) constructors.
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ExploreByTouchHelper is reached through the accessibility framework.
-keep class com.hebrewime.ime.view.KeyboardAccessibilityHelper { *; }

# Keystore-backed crypto: the provider is selected by string name at runtime.
-keep class com.hebrewime.dictionary.KeystoreKeyProvider { *; }

# Keep the trace section names intact. R8 does not rename string constants, but inlining and
# dead-code elimination can remove the tracing calls entirely if it decides they have no
# effect -- which would make the M7 latency harness measure nothing while reporting success.
-keep class androidx.tracing.Trace { *; }
-keepclassmembers class com.hebrewime.ime.correction.CorrectionController {
    public static final java.lang.String TRACE_*;
}

# Kotlin metadata, so reflection-based library code behaves.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature

# Line numbers in stack traces. There is no remote crash reporting in this app -- a user
# reading a stack trace off their own screen is the only reporting channel there is, so it
# should at least be legible.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
