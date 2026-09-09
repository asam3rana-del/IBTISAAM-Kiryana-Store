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

## Remaining operational checks

- Run `gradle lintDebug`, `gradle testDebugUnitTest`, and `gradle assembleDebug` in a network-enabled Android/Gradle environment.
- Test a real 58mm Bluetooth/USB ESC/POS printer, especially Urdu/Nastaliq rendering.
- Test two devices offline simultaneously for sales, purchases, stock, balances, payments, and rate edits.
- Keep release signing credentials outside the repository.
- Databases older than Room version 13 still require the original historical migration chain; this pass deliberately does **not** use destructive upgrade fallback because silently deleting a grocery shop database is unsafe.
