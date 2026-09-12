# Production Improvement Pass — 2026-09-09

Applied fixes from the full assessment:

1. **Runtime branch isolation**
   - Removed compile-time `BRANCH_ID` from Gradle.
   - Branch code is configured at runtime.
   - Branch code is validated (`A-Z`, `a-z`, `0-9`, `_`, `-`, 2–50 chars).
   - Disconnect clears both Firebase project and branch configuration.
   - Sync refuses cloud work until a valid branch is configured.

2. **Sync coverage + delete propagation**
   - Deletes now use timestamped Firestore tombstones, so other devices can observe deletions during pull.
   - Sale/purchase item rows are removed with their deleted parent.
   - Payment/cash transaction deletes use stable `serverId` instead of deleting every row with the same reference.
   - Expense create/delete queue entries verified.
   - Zakat-generated expense now enters the same sync queue.
   - Party Dashboard product-rate sync verified.
   - User active/inactive changes sync; password hashes remain device-local intentionally.

3. **Password security**
   - OTP first-phone linking now requires the sole user's password after OTP verification.
   - Password reset remains local-only so password hashes are never uploaded to Firestore; reset events are locally audited.

4. **Backup password**
   - Local backup password is now encrypted with an Android Keystore AES-256-GCM key instead of plain SharedPreferences.
   - Backup files continue to require the owner's password for cross-device restore.

5. **Conflict safety**
   - Pulled server timestamps are preserved locally instead of replacing them with `System.currentTimeMillis()`.
   - Stock/balance delta operations continue to use Firestore atomic increments.
   - Full-record conflicts remain timestamp based and are written to the local sync audit history.

6. **Zakat / expense consistency**
   - Zakat payments create a normal Expense and now queue that expense for cloud sync.

7. **CI**
   - GitHub Actions now verifies the Firebase config is present before lint/test/build.

8. **Android OS backup policy (Improvement Pack P5)**
   - `android:allowBackup` changed from `true` to `false` in AndroidManifest.xml.
   - Rationale: this app already has its own explicit, owner-password-protected
     backup/restore flow (BackupHelper / BackupExportActivity / BackupCrypto)
     built specifically for the POS's business data. Implicit OS-level Auto
     Backup (Google Drive/cloud), adb backup, and the Android device-to-device
     migration wizard would instead silently copy the whole app sandbox,
     including several values that are only safe on the device that created
     them:
       - `device_prefs` (`DeviceTag`) — the per-installation tag that keeps two
         devices' invoice numbers/sync IDs from colliding (Improvement Pack P2).
         Restoring it onto a second device hands that device the same tag as
         the original and reintroduces the exact bug P2 fixed.
       - `backup_prefs` (`BackupPasswordStore`) — the local backup password is
         encrypted with an Android Keystore key that is hardware-bound to the
         original device; restored elsewhere it cannot be decrypted.
       - `session` / `sync_prefs` — per-device login session and sync cursor
         state that would leave a restored device logged in as the wrong user
         or resuming sync from a stale position.
   - Net effect: the Room database (sales, purchases, customers, suppliers,
     balances) and every SharedPreferences file are now excluded from OS-level
     backup/restore/device-transfer. The app's own in-app backup/export screen
     remains the only supported way to archive or move this data, and it
     already requires the owner's backup password to restore.

9. **Duplicate invoice/bill rejection (Improvement Pack P6)**
   - Sale side (`RoomSaleRepository.saveSale` / `saveQuickSale`) generated its
     invoice number (timestamp + DeviceTag) but never checked whether that
     invoice already existed before inserting. A retried/double-tapped Save
     would hit the sales table's plain `@Insert` and surface a raw
     `SQLiteConstraintException` straight to the cashier. Added a check
     (`findSale(invoice) != null`) before insert on both paths, throwing a new
     `DuplicateInvoiceException` with a friendly message; wired through
     `SaveSaleResult.DuplicateInvoice` / `QuickSaleResult.DuplicateInvoice`,
     `SaleEvent.DuplicateInvoice` / `SaleEvent.QuickSaleDuplicateInvoice`, and a
     Toast in `SaleActivity.handleSaleEvent`.
   - Purchase side (`PurchaseRepository.genBillNo`) was already safe: it
     actively loops against the existing bill-number set until it finds an
     unused candidate before ever inserting, so no change was needed there.

10. **Qty>0 / rate>=0 validation (Improvement Pack P6, continued)**
    - Sale side: `SaveSaleUseCase`/`SaveQuickSaleUseCase` now reject a line
      with `qty <= 0` or a negative `unitPrice`/`price` before it ever reaches
      the subtotal/stock-decrement math — previously neither the repository
      nor the on-screen cart checked this at all. Wired through a new
      `SaveSaleResult.InvalidLine` → `SaleEvent.InvalidLine` → Toast (main
      flow); Quick Sale reuses the existing `QuickSaleResult.InvalidQty` path.
    - Purchase side: `SavePurchaseUseCase` now rejects an empty bill and any
      line with `qty <= 0` or a negative `rate` before calling
      `PurchaseRepository.savePurchase` — this had no such guard at all.
      Reuses the existing `SavePurchaseResult.Error` case, already wired
      through `PurchaseViewModel`/`PurchaseActivity`, so no new event
      plumbing was needed.
    - Confirmed already safe and left untouched: `DiscountCalculator.compute`
      already clamps discount to `[0, subtotal]` and paid to `[0, total]` for
      the Sale flow, and `PurchaseActivity` already clamps `amountPaid` to
      `[0, grandTotal]` for the Purchase flow.

11. **Unit test coverage for the P6 validation (Improvement Pack P9, started)**
    - `FakeSaleRepository` gained `saveSaleThrowsDuplicate` /
      `saveQuickSaleThrowsDuplicate` so tests can simulate
      `DuplicateInvoiceException` without a real database.
    - `SaveSaleUseCaseTest`: zero qty, negative qty, negative rate (each
      asserts `InvalidLine` AND that the repository was never called), a
      mixed-lines case where one bad line blocks the whole sale, and a
      duplicate-invoice-from-repository case.
    - `SaveQuickSaleUseCaseTest`: zero qty, negative qty, negative price (all
      asserting `InvalidQty` without a repository call), and a
      duplicate-invoice-from-repository case.
12. **Unit test coverage for the Purchase-side P6 validation (Improvement Pack P9, continued)**
    - `PurchaseRepository` (`data/PurchaseRepository.kt`) was a concrete class
      tied directly to `PosDatabase`/`Context`, so `SavePurchaseUseCase` could
      not be unit tested without a real Room database — unlike the Sale side,
      which already had `SaleRepository` as an interface. Split it the same
      way: `PurchaseRepository` is now an interface (data classes `PurchaseLine`
      / `PurchaseEditData` / `SavePurchaseResult` unchanged), and the old
      implementation moved unchanged into a new `RoomPurchaseRepository`
      class. `PurchaseViewModelFactory`, `HistoryActivity`, and
      `PurchaseHistoryActivity` (the three places that used to construct
      `PurchaseRepository(db, context)` directly) now construct
      `RoomPurchaseRepository(db, context)` instead; everywhere else in the
      app already referred to the type as `PurchaseRepository`, so nothing
      else changed.
    - Added `FakePurchaseRepository`, mirroring `FakeSaleRepository`.
    - `SavePurchaseUseCaseTest`: empty bill, zero qty, negative qty, negative
      rate (each asserts `SavePurchaseResult.Error` AND that the repository
      was never called), a mixed-lines case where one bad line blocks the
      whole bill, a valid-bill-is-forwarded-and-Success-returned case, and a
      repository-level `Error` (e.g. a costing refusal) passed through
      unchanged.
    - Still open: sync tests (see Remaining operational checks / P9 in the
      checklist).

13. **SaleActivity/ViewModel separation verified (Improvement Pack P8)**
    - Checklist had P8 marked TODO ("Refactor SaleActivity into ViewModel/use
      cases"), but on inspection this was already done in an earlier pass and
      the checklist simply hadn't been updated to match.
    - `SaleActivity.kt` (1353 lines) has zero direct `PosDatabase`/
      `SaleRepository` access — every business operation goes through
      `SaleViewModel` (`viewModel.saveSale()`, `.deleteSale()`, `.loadForEdit()`,
      `.addCustomer()`, `.holdBill()`, `.deleteHeldBill()`), which in turn only
      calls into `SaveSaleUseCase`/`SaveQuickSaleUseCase`/`DeleteSaleUseCase`/
      etc. — it holds no business logic itself, just translates UseCase
      results into `SaleEvent`s.
    - Validation (empty items, qty>0/rate>=0, duplicate invoice, customer-
      required-for-due) all lives in the UseCase layer, not the Activity.
    - The remaining lines in `SaleActivity.kt` are UI construction only:
      themed view builders (`premiumCard`/`innerField`/`pillChip`/
      `circleIcon`), dialogs, the date picker, the overflow menu, text
      watchers, and draft save/restore (SharedPreferences — UI state, not
      business data). No further extraction needed here.
    - Status updated to DONE in the checklist.

14. **Room transaction atomicity verified across the app (Improvement Pack P7)**
    - Checked every screen/repository with 2+ sequential DAO writes for a
      missing `db.withTransaction` around a composite business operation
      (money + stock + a linked record).
    - Confirmed already correct: `RoomSaleRepository` (saveSale/deleteSale/
      saveQuickSale), `RoomPurchaseRepository` (savePurchase/deletePurchase),
      `HistoryActivity`, `PurchaseHistoryActivity`, and `SaleHistoryActivity`
      (returns/edits/deletes) — all wrap their stock + balance + record
      writes in one transaction.
    - **Found and fixed a real gap:** `PartyTransactionActivity.savePayment()`
      (the manual "Receive Payment" / "Make Payment" dialog) ran the payment
      insert, the customer/supplier balance adjustment, and the matching cash
      transaction insert as three unguarded sequential writes. A crash or
      app-kill between them could leave a payment recorded with no balance
      change, or a balance changed with no cash-transaction/payment record.
      Now wrapped in `db.withTransaction { ... }`, matching the pattern
      already used in the sale/purchase repositories.
    - Lower-priority, non-money gaps noted but **not** changed (cosmetic data
      only, low crash-window risk): category/unit "rename" in `ItemsActivity`
      and `BulkTranslateActivity` does an insert-new + delete-old instead of
      a single update, outside any transaction.
    - **Could not fully verify:** `SyncQueueHelper.kt` (referenced by every
      repository for `adjustCustomerBalance`/`adjustSupplierBalance`/
      `enqueue*`) is not included in this zip, so its internals weren't
      reviewed. Since every other write above is already inside
      `db.withTransaction`, Room will fold SyncQueueHelper's writes into the
      same transaction as long as it just uses the `db` passed in — but this
      should be double-checked once that file is available.

15. **Sync tests started — SyncQueueHelperTest.kt (Improvement Pack P9)**
    - `SyncQueueHelper.kt` and the previously-written `FakeSaleRepository.kt`/
      `FakePurchaseRepository.kt` were missing from this working copy and have
      been restored under `app/src/test/java/com/grocerypos/v11/` (unchanged,
      as supplied) so the test source set is complete again.
    - New `SyncQueueHelperTest.kt` (21 tests): covers every entity-id function
      (`customerEntityId`/`supplierEntityId`/`productEntityId`/`saleEntityId`/
      `purchaseEntityId`/`paymentEntityId`/`expenseEntityId`/
      `cashTransactionEntityId`/`userEntityId`) and every JSON payload builder
      (`customerJson`/`supplierJson`/`productJson`/`paymentJson`/
      `expenseJson`/`cashTransactionJson`/`userJson`).
    - Three tests are deliberately regression guards for the conflict-safety
      invariants documented in `SyncQueueHelper.kt`'s comments: `balance` must
      never appear in `customerJson()`/`supplierJson()`, `stock`/
      `openingStock` must never appear in `productJson()` (both are
      increment-only fields — a snapshot leak would let one offline device's
      routine edit silently overwrite another device's balance/stock change),
      and `passwordHash` must never appear in `userJson()`.
    - **Scope limit, on purpose:** only the PURE functions are covered (no
      `suspend`, no `PosDatabase`/`Context` dependency) — these run as plain
      JVM tests like the rest of this module. `enqueue()`/`trigger()`, every
      `enqueueX()` wrapper, `adjustCustomerBalance()`/`adjustSupplierBalance()`,
      the stock-delta functions, `updateProductCost()`, and `saleJson()`/
      `purchaseJson()` (which query the DAO for line items) all touch a real
      `PosDatabase` and need a Room in-memory database — either Robolectric or
      an on-device instrumented test, not achievable in this no-Android-SDK
      sandbox. That's the next chunk of P9 once a real Gradle/Android
      environment is available.

16. **Room migration testing set up — MigrationTest.kt (Improvement Pack P3)**
    - `Database.kt`: `exportSchema` flipped `false -> true`; `app/build.gradle.kts`:
      added the matching `room.schemaLocation` kapt arg (`app/schemas/`) and the
      `androidx.test.ext:junit` / `androidx.test:runner` / `androidx.room:room-testing`
      `androidTestImplementation` dependencies — none of this existed before, so no
      migration could be tested at all.
    - New `app/src/androidTest/java/com/grocerypos/v11/MigrationTest.kt`: runs the
      real `MIGRATION_13_14` ... `MIGRATION_31_32` chain (all 19 migrations) against
      a real on-device SQLite engine via Room's `MigrationTestHelper`, plus per-step
      validation and two data-integrity checks — the unit-ladder stock conversion
      (`MIGRATION_18_19`/`21_22`) and the `saleUid` UUID backfill (`MIGRATION_31_32`).
    - New `MIGRATION_TEST_PLAN.md`: full write-up, including a manual upgrade
      checklist to run once on a real/old-backup device.
    - **Important, flagged caveat:** `exportSchema` was off from the start, so there's
      no captured historical schema for versions 13-31. `MigrationTest.kt`'s v13
      starting schema is a best-effort reconstruction (worked out column-by-column
      from each migration's `ALTER TABLE`/`CREATE TABLE` diff), not a verified
      historical snapshot — see the caveat block at the top of that file and the
      "one thing this could NOT fix" section of `MIGRATION_TEST_PLAN.md` for exactly
      what to double-check (an old backup `.db` file or an archived old APK) before
      treating this as a release gate. Every migration from v32 onward is safe from
      this same gap, since schemas now export automatically.
    - Could not actually run this test in this environment — no Android SDK/device,
      same limitation noted in `TESTS-README.md` for the unit tests.

17. **Two-device offline sync stress test plan — SYNC_STRESS_TEST_PLAN.md (Improvement Pack P4)**
    - Cannot be executed here (needs 2 real/emulator devices, a live Firebase
      project, and real offline wall-clock time). Wrote a 10-scenario plan that
      expands on `SYNC-CONFLICT-TESTS.md`'s existing 9-item list with genuine
      *stress* cases: high-volume offline batches (~90 records) and how long the
      queue takes to drain, 3-way stock/balance contention (not just a single
      conflicting pair), a delete-vs-edit race, clock skew between devices,
      app-force-killed mid-sync, the `retryCount>=10` stuck-row ceiling
      (`SyncQueueDao.resetAllStuck()`), and a genuine 24h+ offline window (the
      scenario most likely to hit a WorkManager Doze-mode issue a short test
      wouldn't catch).
    - **Flagged gap:** `SyncWorker.kt`, `SyncApi.kt`, and `SyncRepository.kt` are
      still missing from this working copy (same as `SyncQueueHelper.kt` was
      before it got restored under P9) — several scenario details (retry/backoff
      timing, which timestamp drives last-write-wins, whether `resetAllStuck()`
      is actually called anywhere) are marked `[ASSUMED]` in the plan pending
      those files.

    - **Update:** `SyncWorker.kt`, `SyncApi.kt`, and `SyncRepository.kt` (the three
      files flagged as missing above) were supplied and restored under
      `app/src/main/java/com/grocerypos/v11/sync/`. Every previously-`[ASSUMED]`
      mechanic in `SYNC_STRESS_TEST_PLAN.md` is now confirmed from the real code and
      the plan updated accordingly — most notably: sync batches cap at 200 rows/cycle
      (`pending(limit=200)`), there is no exponential backoff for a single failed row
      (it just waits for the next cycle), the `retryCount>=10` ceiling is genuinely
      enforced by `pending()`'s own SQL (not merely intended to be), and — the
      important one — last-write-wins conflict resolution compares each device's own
      local clock (`System.currentTimeMillis()` at payload-build time), NOT a
      Firestore server timestamp, so the clock-skew scenario in the plan is a
      confirmed real risk, not a hypothetical to rule out.

## Remaining operational checks

- Run `gradle lintDebug`, `gradle testDebugUnitTest`, and `gradle assembleDebug` in a network-enabled Android/Gradle environment.
- Test a real 58mm Bluetooth/USB ESC/POS printer, especially Urdu/Nastaliq rendering.
- Test two devices offline simultaneously for sales, purchases, stock, balances, payments, and rate edits.
- Keep release signing credentials outside the repository.
- Databases older than Room version 13 still require the original historical migration chain; this pass deliberately does **not** use destructive upgrade fallback because silently deleting a grocery shop database is unsafe.
