# Release Checklist (Improvement Pack P10)

Every unchecked box below is either a genuine open item or something that must be
verified by a human on a real device/Firebase project — nothing here can be
rubber-stamped from source code alone. Where earlier Improvement Pack work already
did the groundwork, this checklist points at exactly what it produced instead of
duplicating it.

## Build

- [ ] `./gradlew lintDebug` passes
- [ ] `./gradlew testDebugUnitTest` passes — covers `DiscountCalculatorTest`,
      `PasswordHasherTest`, `ProductUnitConversionTest`, `SaveSaleUseCaseTest`,
      `SavePurchaseUseCaseTest`, and `SyncQueueHelperTest` (21 tests, added under
      P9 — entity-id + JSON payload builders, incl. the balance/stock/passwordHash
      leak regression guards)
- [ ] `./gradlew connectedDebugAndroidTest` passes on a real device/emulator —
      covers `MigrationTest` (added under P3; see the caveat below before trusting
      it as a hard gate)
- [ ] `./gradlew assembleRelease` succeeds and produces a signed APK/AAB
- [ ] Release keystore credentials are supplied via Gradle properties / CI secrets
      (see `app/build.gradle.kts`'s release `signingConfigs` block for the exact
      property names expected) — **never** committed to Git
- [ ] **Open question, not yet decided:** `buildTypes { release { } }` has no
      `isMinifyEnabled`/`proguardFiles` — R8/ProGuard is currently OFF for release
      builds. Decide deliberately (smaller APK + some obfuscation vs. one more
      variable to debug if something misbehaves only in a minified build) rather
      than leaving it as an accidental default; if enabling it, budget time to test
      every screen afterward — Room/Gson reflection-based code is a common source of
      minify-only crashes.
- [ ] `versionCode` / `versionName` bumped in `app/build.gradle.kts` for this release
      (currently `10` / `"10.0"` — confirm this is actually the next release, not
      left over from the last one)

## Database migrations (Improvement Pack P3)

- [ ] `MigrationTest.kt` run and passing on a real device (see above)
- [ ] **Before trusting it as a real gate:** `MigrationTest.kt`'s version-13 starting
      schema is a best-effort reconstruction from reading the migration diffs
      (`exportSchema` was off historically, so no real v13 schema was ever
      captured) — see `MIGRATION_TEST_PLAN.md`'s "one thing this could NOT fix"
      section for the two ways to actually verify it (an old backup `.db` file, or
      installing an archived old APK and upgrading it for real). Do at least one of
      those before a release that a large number of existing installs will upgrade
      into.
- [ ] `app/schemas/` now gets a JSON file per version going forward (`exportSchema`
      was flipped on under P3) — commit these to Git; they're what makes every
      *future* migration test trustworthy without needing this same
      reconstruction exercise again

## Sync (Improvement Pack P4, P7, P9)

- [ ] Firebase project configured (per-branch, via Settings > Cloud Sync Setup —
      not baked into the APK, see `app/build.gradle.kts`'s comment on this)
- [ ] Branch Code configured and validated on every device that will go live
- [ ] Firestore Security Rules deployed and require `request.auth != null` (see
      `SyncApi.kt`'s comment on why Anonymous Auth exists) — **no `firestore.rules`
      file exists in this repo currently**; if rules are only configured by hand in
      the Firebase console, get them into version control before release so a
      console mistake can't silently reopen the database, and so the next
      developer knows what's expected
- [ ] `branch_members/{uid}` documents exist for every approved device (see
      `SyncApi.currentUid()`'s doc comment for how an admin gets a device's UID)
- [ ] Run `SYNC_STRESS_TEST_PLAN.md`'s 10 scenarios end-to-end on two real devices —
      this is the actual execution of Improvement Pack P4; the plan alone doesn't
      satisfy this checklist item, only a completed run of it does. Pay particular
      attention to:
  - [ ] Scenario 7 (clock skew) — **confirmed real risk**: last-write-wins compares
        each device's own local clock, not a Firestore server timestamp. Decide
        before release whether this is an accepted limitation (document it
        somewhere a support person can find) or worth fixing
        (`FieldValue.serverTimestamp()`) first.
  - [ ] Scenario 9 (retry ceiling) — confirm Settings > Sync History actually
        surfaces `SyncQueueDao.stuck()` rows with a working manual "Retry Now" —
        this is where a shopkeeper's permanently-failed record becomes visible and
        recoverable instead of silently lost
  - [ ] Scenario 1 (batch cap) — confirm a backlog over 200 queued rows genuinely
        drains across multiple cycles rather than stalling
- [ ] `PartyTransactionActivity.savePayment()`'s transaction fix (Improvement Pack
      P7) — spot-check once on a real device: force-kill the app immediately after
      tapping "Save" on a manual payment, reopen, confirm the payment/balance/cash
      transaction are either all present or all absent, never a partial state
- [ ] Password reset is understood as device-local by design (not synced) —
      confirm this is still communicated to shop owners/support, since it's an easy
      support ticket otherwise
- [ ] Sync History shows no unresolved conflicts/failures on all release-candidate
      test devices at the moment of release

## Security

- [ ] Anonymous Auth enabled in the Firebase project (required — see `SyncApi.kt`)
- [ ] `google-services.json` present (it is, at `app/google-services.json`) —
      confirm this is the intended production project's file, not a leftover dev/
      test project
- [ ] OTP first-phone-link flow still requires the local password (per existing
      checklist item — re-verify manually, no automated test covers this flow)
- [ ] Backup password is stored only in Keystore-encrypted form (per existing
      checklist item)
- [ ] No real passwords/API secrets committed to Git — this repo's `SyncQueueHelper.kt`
      deliberately excludes `passwordHash` from `userJson()`
      (`SyncQueueHelperTest.kt`'s `userJson carries profile fields but never the
      password hash` test guards this specific regression) — spot-check the actual
      Firestore `users` collection on a test project to confirm no password hash
      has ever leaked there from before this was in place

## Backup / Restore

- [ ] Manual backup created and inspected
- [ ] Restore tested on a second phone with the owner password
- [ ] Automatic backup schedule tested (`BackupScheduler` — per `PosApplication.kt`,
      runs at ~12 PM, ~9 PM, and on app close/background)

## Printer

- [ ] 58mm Bluetooth printer tested
- [ ] USB printer tested if used
- [ ] English receipt tested
- [ ] Urdu receipt tested for shaping/RTL

## Crash reporting / stability

- [ ] `CrashHandler` (installed first in `PosApplication.onCreate()`, per its own
      comment) is actually capturing and the shop owner/support has a way to see or
      export a crash log if something goes wrong post-release
- [ ] Each of `PosApplication`'s five startup steps (`SyncWorker.schedulePeriodic`,
      `NetworkMonitor.register`, `AppLock.register`, `BackupScheduler.register`,
      `BackupScheduler.scheduleDailyCheckpoints`) is individually try/caught — spot
      check the log output once on a fresh install to confirm none of them are
      silently failing every launch

## Sign-off

- [ ] Every unchecked box above has either been checked off with evidence (a
      screenshot, a test run, a log excerpt) or explicitly deferred with a written
      reason — "looked fine" is not a sign-off
- [ ] `IMPROVEMENT_CHECKLIST.csv` reviewed one more time immediately before tagging
      the release, in case anything shifted since this checklist was last updated
