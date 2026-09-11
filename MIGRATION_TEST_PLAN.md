# Room Migration Test Plan (Improvement Pack P3)

Checklist item: **"Test Room migrations from oldest supported version to 32."**

## What's now in place

1. **`Database.kt`**: `exportSchema` changed `false -> true`. **`app/build.gradle.kts`**:
   added the matching `room.schemaLocation` kapt arg, pointing at `app/schemas/`.
   From the next build onward, Room writes a schema JSON for every version — this is
   the thing that was silently missing before and is the real long-term fix here.
2. **`app/build.gradle.kts`**: added `androidx.test.ext:junit`, `androidx.test:runner`,
   and `androidx.room:room-testing` under `androidTestImplementation` (no instrumented
   test dependencies existed at all before this).
3. **`app/src/androidTest/java/com/grocerypos/v11/MigrationTest.kt`**: a real
   `MigrationTestHelper`-based test that runs `MIGRATION_13_14` through
   `MIGRATION_31_32` — the entire chain — against a real on-device SQLite engine, plus
   two data-integrity checks (unit-ladder stock conversion, saleUid backfill).

## The one thing this could NOT fix by itself

`exportSchema` was `false` from the start, so Room never captured what the database
actually looked like at version 13 (or 14, 15, ... 31). `MigrationTest.kt`'s
`createV13Database()` hand-builds that starting schema by reading every migration's
`ALTER TABLE` / `CREATE TABLE` statement in `Database.kt` and working backward — e.g.
"`MIGRATION_16_17` adds `products.tertiaryUnit`" means that column must be absent at
v13. This is a careful best-effort reconstruction, **not a captured historical
schema**, and it has not been run (no Android SDK in this working environment — see
`TESTS-README.md`'s note on the same limitation for the unit tests).

**Before trusting this as a real release gate, do ONE of:**

- **Best: find an old backup.** If a `.db` file (or a full app backup) from any
  install running an old version of this app still exists anywhere — a developer's
  old test device, an old `adb backup`, a support ticket attachment — pull it and run
  `sqlite3 old.db .schema`. Compare that output table-by-table against
  `createV13Database()` in `MigrationTest.kt` and fix any mismatch.
- **Fallback: install an old APK.** If the actual old APK (whichever build first
  shipped with Room schema version 13) is still archived anywhere, install it on a
  spare device/emulator, create a handful of products/customers/sales/purchases with
  it, then install the CURRENT build over it (upgrade, not reinstall/clear-data) and
  run through the manual checklist below.
- **If neither exists:** the app has likely already been upgraded past v13 on every
  real device in the field, in which case this reconstruction only matters for
  brand-new installs restoring a very old backup — lower risk, but still worth a note
  in `RELEASE_CHECKLIST.md` if that scenario is possible for this shop.

From v32 onward this exact problem cannot recur — every future migration will have a
real exported schema to build its "before" state from instead of hand-written SQL.

## How to run the automated test

```
./gradlew connectedDebugAndroidTest --tests "com.grocerypos.v11.MigrationTest"
```
(needs a connected device or running emulator — same requirement as any other
instrumented test; this cannot run as part of `./gradlew test`.)

Or in Android Studio: open `MigrationTest.kt`, click the green run arrow next to the
class name.

## Manual upgrade checklist (do this once, on a real or old-backup device, per the
"Fallback: install an old APK" step above)

For each area, create data on the OLD build, then upgrade to the CURRENT build and
confirm:

| Area | What to create on old build | What to verify after upgrade |
|---|---|---|
| Products (3-tier units) | A product with Box -> Pcs (2-tier) and one with Carton -> Box -> Pcs (3-tier), some stock | Stock/reorderLevel/openingStock show the correct smallest-unit quantity (see `unitLadderMigration_convertsStockToSmallestUnitsCorrectly` test for the exact math); no crash opening Items screen |
| Sales | A cash sale and a credit sale, at least one with a fractional qty if the old build allowed it | Sale History shows both; `saleUid` is populated (Settings > Sync History or a raw `SELECT saleUid FROM sales` if adb is available) — should never be blank |
| Purchases | A purchase against a supplier, partly paid | Purchase History shows it; stock updated correctly; `purchaseUid` populated |
| Customers/Suppliers | A customer and supplier with a non-zero opening balance and some transactions | Balances match what they were before upgrade; Party screen opens without crash |
| Users | The default/admin user, plus one more if the old build supported multi-user | Login still works with the same password; `phone` column present but blank is fine |
| App doesn't crash on first launch post-upgrade | — | This is the single most important check — a Room schema-validation mismatch (the exact class of bug `MIGRATION_27_28`'s comment describes) crashes on EVERY launch, not just the first |

## Why `MigrationTest.kt` is the primary deliverable, not just this document

A written plan alone doesn't catch a real migration bug — someone still has to run it.
The instrumented test above exercises the actual `Migration` objects from `Database.kt`
against real SQLite, so a future edit to any `MIGRATION_X_Y` (or a newly added
`MIGRATION_32_33`) that breaks the chain fails a CI/local test run immediately, instead
of surfacing as a white-screen crash on a shopkeeper's device after an update — which is
exactly the failure mode `MIGRATION_27_28`'s own comment describes already happening
once in this app's history.
