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
    - Not yet covered: `SavePurchaseUseCase`'s equivalent P6 validation has no
      test yet — there's no `FakePurchaseRepository` in the test suite yet to
      build it on top of (see Remaining operational checks / P9 in the
      checklist).

## Remaining operational checks

- Run `gradle lintDebug`, `gradle testDebugUnitTest`, and `gradle assembleDebug` in a network-enabled Android/Gradle environment.
- Test a real 58mm Bluetooth/USB ESC/POS printer, especially Urdu/Nastaliq rendering.
- Test two devices offline simultaneously for sales, purchases, stock, balances, payments, and rate edits.
- Keep release signing credentials outside the repository.
- Databases older than Room version 13 still require the original historical migration chain; this pass deliberately does **not** use destructive upgrade fallback because silently deleting a grocery shop database is unsafe.
