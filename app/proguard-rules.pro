# R8/ProGuard rules — Improvement Pack P12.
#
# STATUS: prepared, NOT yet active. `app/build.gradle.kts`'s release buildType now
# points at this file via `proguardFiles(...)`, but `isMinifyEnabled` is still left
# unset (defaults to false) — see RELEASE_CHECKLIST.md's "Build" section, which
# flagged turning R8 on as "an open question, not yet decided" rather than an
# accidental default. Wiring this file in now means that decision, whenever it's
# made, is a one-line `isMinifyEnabled = true` flip instead of starting from a blank
# file with zero keep rules under release-build time pressure.
#
# Before flipping isMinifyEnabled = true for a real release:
#   1. Build once: ./gradlew assembleRelease
#   2. Install the resulting release APK on a real device and manually walk through
#      every screen once (Sale, Purchase, Party, Reports, Settings > Cloud Sync
#      Setup + OTP login, Backup/Restore, Printer, Cash Register) — reflection-based
#      libraries (Room, Gson, Firestore, WorkManager) are exactly the kind of thing
#      that can compile fine and then only fail at runtime in a minified build.
#   3. Keep app/build/outputs/mapping/release/mapping.txt from EVERY shipped release
#      (rename it per versionCode, e.g. mapping-v12.txt, and store it outside the
#      repo) — without it, a crash log from a minified build is unreadable
#      (obfuscated class/method names), and CrashHandler's whole point is defeated.
#
# This app deliberately avoids reflection-heavy (de)serialization in its own code —
# SyncQueueHelper.kt's sync payloads are all Map<String,Any?> via Gson, and SyncApi.kt
# never calls Firestore's DocumentSnapshot.toObject(SomeClass::class.java) — so the
# usual "why did my release build silently break" causes (Gson/Firestore mapping a
# custom data class by reflection) mostly don't apply here. The rules below are still
# needed for: Gson's own internal generic-type handling, Firestore/gRPC's TLS
# provider detection, WorkManager's reflective Worker instantiation, and one
# app-specific Class.forName() call site.

# ---------- Gson ----------
# Gson uses generic type information stored in the class file (e.g. to tell
# Map<String, Object> apart from a plain Map at runtime) — R8 strips that by default
# unless told to keep it. Without this, SyncQueueHelper.kt's
# `gson.fromJson(json, Map::class.java)` / `gson.toJson(map)` calls (every sync
# payload builder + SyncApi.kt's pull-side parsing) can silently misbehave in a
# minified build even though nothing throws.
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
# Defensive only — this app has no @SerializedName-annotated data classes today
# (payloads are plain Maps), but keeping this costs nothing and protects whoever
# adds a typed Gson model later without knowing to update this file.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---------- Firebase / Firestore / gRPC ----------
# Firebase's own AARs ship consumer ProGuard rules for the common cases, but
# Firestore's realtime-listener transport (gRPC over okhttp) has a well-documented
# history of R8 build failures from missing optional TLS-provider classes
# (Conscrypt/BouncyCastle/OpenJSSE) that gRPC only references via reflection/
# best-effort classpath probing — these are -dontwarn, not -keep, because the
# classes genuinely aren't shipped and don't need to be: gRPC's own code already
# handles ClassNotFoundException for them at runtime.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
# Broad but standard: Firebase/Play Services internals are not this app's code and
# are safe to leave fully unshrunk — the size cost is small relative to the risk of
# guessing wrong about which internal classes are reflection-reached.
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ---------- Room ----------
# androidx.room:room-runtime bundles its own consumer-rules.pro (keeps @Entity/@Dao-
# generated *_Impl classes and their no-arg constructors), so this section is
# defense-in-depth, not filling an actual gap — kept explicit so the reasoning is on
# record rather than relying silently on a transitive dependency's own rules file.
-keep class com.grocerypos.v11.PosDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# ---------- WorkManager ----------
# UNLIKE Room, WorkManager does NOT reliably auto-keep custom Worker subclasses —
# it instantiates them by fully-qualified class name stored in the persisted
# WorkSpec (WorkManagerImpl's WorkerFactory does `Class.forName(...)` internally),
# which is the same fragile-under-minification pattern as SettingsActivity's
# Class.forName() below. Covers SyncWorker.kt (periodic + one-off sync) and
# BackupScheduler.kt's BackupCheckpointWorker — and any future Worker without
# needing this file touched again.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------- App-specific: SettingsActivity.tryOpenActivity()'s Class.forName() ----------
# SettingsActivity.kt deliberately does `Class.forName("com.grocerypos.v11.ui.ExpenseActivity")`
# and treats ClassNotFoundException as "this feature doesn't exist yet, show Coming
# Soon" (see tryOpenActivity()'s doc comment). Since ExpenseActivity IS declared in
# AndroidManifest.xml, AGP's own default rules already keep its name (the OS has to
# find manifest-declared components by exact class name too, minified or not) — this
# line is redundant with that in the current code, but is cheap, explicit, and
# removes any dependency on that manifest-component behavior continuing to cover this
# specific reflective lookup if this pattern is ever reused for a class that ISN'T
# manifest-declared. Without it, a future case of this pattern would silently show a
# real, working feature as "Coming Soon" only in release builds — a class of bug that
# is invisible in every debug-build test pass.
-keep class com.grocerypos.v11.ui.ExpenseActivity { <init>(...); }

# ---------- Kotlin coroutines ----------
# Standard, widely-recommended defensive rule (Kotlin coroutines' own issue tracker):
# without it, some R8 versions can strip the volatile fields backing suspend-function
# state machines in a way that only surfaces as a rare release-only crash.
-keepclassmembers class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
    <fields>;
}
-dontwarn kotlinx.coroutines.**
