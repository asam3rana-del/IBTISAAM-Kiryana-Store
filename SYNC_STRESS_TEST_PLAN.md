# Two-Device Offline Sync Stress Test Plan (Improvement Pack P4)

Checklist item: **"Run two-device offline sync stress tests."**

## Cannot be executed in this environment

This needs two real (or emulator) Android devices signed into the same Branch Code,
a live Firebase project, and real wall-clock time offline (hours, not seconds) — none
of which exist in this sandbox. This document is the test plan a human tester runs;
it replaces/expands the 9-item list already in `SYNC-CONFLICT-TESTS.md` (kept as-is —
this plan is the "stress" layer on top of it: higher volume, longer offline windows,
and failure-injection scenarios that file didn't cover).

## Important gap: `SyncWorker.kt` / `SyncApi.kt` / `SyncRepository.kt` — now restored

These three files were missing from this working copy (same as `SyncQueueHelper.kt`
was before it got restored under P9). They've now been added back under
`app/src/main/java/com/grocerypos/v11/sync/`. Everything below that was previously
marked `[ASSUMED]` is now confirmed from the actual code — the confirmed facts have
replaced the assumptions, and are called out where they change a scenario's
expectation.

**Confirmed mechanics (worth knowing before running any scenario below):**

- **Periodic sync interval: 15 minutes** (WorkManager's own minimum for periodic
  work), requires `NetworkType.CONNECTED`. `ExistingPeriodicWorkPolicy.KEEP` means
  re-calling `schedulePeriodic()` (e.g. on every app start) never duplicates it.
- **Immediate sync exists on two triggers**, not just the in-app "Sync Now" button:
  `SyncWorker.triggerNow()` is also called from `NetworkMonitor.kt` (a file still not
  in this working copy) the moment connectivity returns — so scenario 8/10's "does it
  resume automatically" concern is largely covered for the *reconnect* case. It is
  **not** covered for the *while still connected but app force-killed* case — nothing
  re-triggers a sync just because the app restarts; the next 15-minute periodic tick
  (or the user tapping Sync Now) is what picks it back up.
- **No exponential backoff.** `SyncWorker.doWork()` returns `Result.retry()` only if
  the *entire* sync cycle throws (e.g. a pull failure) — WorkManager will back off and
  retry that per its default policy. But an individual row's push failure inside
  `SyncRepository.syncNow()` does NOT throw — it's caught, marked failed, and the
  cycle continues normally and reports success. So a single flaky row is retried
  simply "whenever the next sync cycle runs" (next periodic tick, or next explicit
  trigger), not via WorkManager backoff at all.
- **Batch size cap: 200 rows per cycle** (`queueDao.pending(limit = 200)`). Scenario 1
  below is updated to specifically test crossing this line — with >200 queued rows, a
  single sync cycle will NOT drain everything; it takes multiple 15-minute cycles (or
  multiple manual Sync Now taps) to fully clear a backlog that big.
- **Retry ceiling confirmed exactly as scenario 9 described:** `pending()`'s query is
  `WHERE syncedAt IS NULL AND retryCount < 10` — a row hitting 10 failures is
  automatically excluded from all future auto-retries (not just "should stop", it
  provably does, per the SQL). `stuck()` surfaces these rows and `resetRetry()` /
  `resetAllStuck()` clear them for a manual "Retry Now" in Settings > Sync History —
  confirming the visibility scenario 9 was worried might not exist.
- **Last-write-wins uses each device's OWN LOCAL CLOCK, not a Firestore server
  timestamp.** `SyncApi.push()`'s upsert path compares `incomingUpdatedAt` (read out
  of the payload JSON, which `SyncQueueHelper` built using `System.currentTimeMillis()`
  on the pushing device) against whatever `updatedAt` is already stored on the
  Firestore document (which was itself written by whichever device pushed last, using
  ITS OWN local clock at the time). **This confirms scenario 7 (clock skew) as a real,
  not merely theoretical, risk** — a device with a fast/wrong clock can make its edits
  always "win" regardless of true chronological order, and the same applies to the
  delete tombstone comparison in scenario 6 (`deleteAt >= serverUpdatedAt`, same
  local-clock-based logic).
- **Conflict visibility:** every rejected push (local was older) and every pull-time
  overwrite of a locally-dirty, unsynced customer/supplier/product edit is logged to
  the `audit` table (`sync_conflict` / `sync_push_failed` actions) — Settings' Audit
  Log screen (if surfaced there) is where a tester should look to confirm a detected
  conflict was actually recorded, not just infer it from the visible data settling
  correctly.
- **Stock-on-pull regression to watch for:** `SyncApi.applyServerChanges()`'s product
  loop has a comment marking a real historical bug fix — a plain product re-save
  (e.g. editing just the price) on one device used to silently reset stock on other
  devices back to a stale value, because the apply loop wasn't reading the `stock`
  field out of the pulled snapshot at all. It's fixed now (reads `stock` and layers
  any locally-pending `increment_stock` delta on top), but this is exactly the kind of
  fix that regresses silently in a future edit — scenario 4 below has been extended to
  specifically re-check this combination (a stock-changing operation on one device
  overlapping with a plain non-stock product edit on the other).

## Setup (once)

- Device A and Device B: same Firebase project, same valid Branch Code (per
  `SYNC-CONFLICT-TESTS.md` item 1).
- Put both devices' clocks on automatic network time — a manual clock-skew test is
  scenario 7 below, but every OTHER scenario should start from a synced clock so a
  clock bug doesn't mask a sync bug.
- Have `adb logcat` running and filtered to this app's package on both devices
  throughout, so a silent failure (a swallowed exception, a WorkManager job that never
  fires) is visible even if the UI shows nothing wrong.
- Know how to inspect `sync_queue` directly (`adb shell` + `run-as` + `sqlite3`, or a
  debug-only in-app screen if one exists) — several checks below need to see
  `retryCount`/`lastError`/`syncedAt`, not just the end-visible data.

## Volume scenarios (stress = quantity, not just presence)

1. **Large offline batch, single device — including a backlog above the 200-row batch
   cap.** Device A offline. Create 50 sales, 20 purchases, 10 expenses, 5 new
   customers, 5 new products in the normal UI flow (not a script — this doubles as a
   UI-performance check). That's under 200 total sync_queue rows, so first verify this
   smaller batch drains in a single sync cycle. Then repeat with enough volume to
   exceed 200 queued rows (e.g. 150+ sales) and confirm it correctly takes **multiple**
   15-minute cycles (or repeated manual Sync Now taps) to fully drain — a single cycle
   only pulls up to 200 via `pending(limit = 200)`, so seeing it NOT fully drain in one
   pass is the expected/correct behavior here, not a bug; the bug to watch for is the
   backlog failing to shrink further on the next cycle. Note the time to fully drain
   as a baseline for scenario 3.
2. **Large offline batch, both devices, no overlap.** Both offline. 30 sales on A
   (different products/customers than B), 30 purchases on B. Reconnect both at the
   same time. Verify all 60 records appear on both devices, `sync_queue` fully drains
   on both, and no record is duplicated (this exercises the `serverId` de-dup logic in
   `enqueueX()`'s "self-duplication on pull" fix — see `SyncQueueHelper.kt`'s comment
   above `enqueueCustomer()`).
3. **Repeat scenario 1 but reconnect on a slow/flaky connection** (airplane mode
   toggled on/off every ~10 seconds, or a throttled network profile in the emulator).
   Verify the queue still fully drains eventually and `retryCount` climbs on the rows
   that hit a mid-push failure but resets to 0 once that row succeeds — a row should
   never get permanently stuck below the 10-retry ceiling from ordinary flakiness.

## Conflict/overlap scenarios (beyond `SYNC-CONFLICT-TESTS.md`'s single-pair cases)

4. **Same product, three-way stock contention — plus a concurrent non-stock edit.**
   Both devices offline. On A: sell the same product 4 separate times (different
   invoices). On B: sell it twice and buy it back in once (net -2, -1×2, +1 = net
   movement across 4 operations) AND separately edit that same product's category or
   sale price (a non-stock field, via the normal product-edit screen — this is the
   scenario that regressed once already, see the "stock-on-pull regression" note
   above). Reconnect. Verify final stock = starting stock + sum of every individual
   delta, in any order, AND that the price/category edit from B is visible on A
   without having reset A's already-merged stock number back to a stale value.
5. **Same customer, balance contention across every write path that touches it.**
   Both offline. On A: sell to the customer on credit (balance up) and record a manual
   payment from them (balance down — exercises the `PartyTransactionActivity.savePayment()`
   path fixed under P7). On B: sell to them again and edit/delete one of A's — wait, B
   can't see A's sale yet (still offline) — so on B: sell to them and separately adjust
   their balance via the customer-edit dialog if that path exists. Reconnect. Verify
   the final balance equals starting balance plus every individual delta from both
   devices, and that Party History on both devices lists every transaction (not just a
   correct final number with a mismatched history — a wrong-but-compensating pair of
   errors on the number alone would pass a shallow check).
6. **Delete-while-editing race.** Both offline. On A: edit a sale that exists on both
   (change qty/price). On B: delete that same sale. Reconnect. There's no "correct"
   answer that avoids a judgment call here — record whichever way the app resolves it
   (edit wins, delete wins, or a crash/corrupt state) and treat "crash or corrupt
   state" as a bug regardless of which way it should have gone; "edit silently
   un-deletes" or "delete silently discards the edit" are both acceptable outcomes to
   document, not necessarily bugs.
7. **Clock skew — confirmed real risk, not just theoretical.** Set Device B's clock 2
   hours behind before going offline. Both offline, edit the same product's price on
   both (scenario 7 from `SYNC-CONFLICT-TESTS.md`). Reconnect. Since last-write-wins is
   confirmed to compare each device's own local `updatedAt` (not a Firestore server
   timestamp — see the confirmed-mechanics note above), expect B's skewed-clock edit to
   incorrectly lose even when it was made chronologically last in the real world. This
   is expected-given-the-current-design, not a surprise to "discover" — the point of
   running it is to have a concrete, reproducible example on hand (screenshot the
   before/after) for a product decision on whether this is worth fixing (e.g. switching
   to `FieldValue.serverTimestamp()` for the comparison) versus accepted as a known
   limitation for now.

## Failure-injection scenarios

8. **App killed mid-sync.** Device A: queue up ~20 offline operations, reconnect, then
   force-stop the app (Settings > Apps > Force Stop, not just swipe-away) within a
   couple seconds of reconnecting — while `sync_queue` still has un-synced rows.
   Reopen the app. Verify: no row is left half-applied (either fully pushed with
   `syncedAt` set, or not pushed with `syncedAt` still null — never a state where the
   local data changed but the sync row still shows unsynced, or vice versa). Note:
   confirmed there is no restart-triggered resync — recovery here depends on either
   `NetworkMonitor`'s reconnect trigger (not relevant if network was never lost, only
   the app was killed) or the next scheduled 15-minute periodic tick, or the user
   tapping Sync Now manually; if the reopened app sits idle for under 15 minutes with
   no manual tap, an unsynced remainder is expected to just sit there, not a bug.
9. **Retry ceiling.** Force a small number of pushes to fail repeatedly (e.g. toggle a
   Firestore security-rule change temporarily, or block the app's network access at
   the OS level while leaving it "online" per Android so WorkManager keeps trying)
   until `retryCount` hits 10 on at least one row. Confirmed from `SyncQueueDao`: the
   row then stops appearing in `pending()`'s query (`retryCount < 10`) and is
   automatically excluded from all future auto-retry cycles — verify this directly (it
   should stop retrying, not merely "seem to"), that `lastError` holds a useful
   message, and that Settings > Sync History surfaces it via `stuck()` with a working
   manual "Retry Now" (`resetRetry()`/`resetAllStuck()`) — confirm this UI path
   actually exists and works, since a shopkeeper with a permanently stuck row and no
   visible way to retry it would otherwise silently lose that record from sync forever.
10. **Extended real offline duration.** Device A offline for a genuinely long window
    (24+ hours, not a simulated toggle) doing normal daily shop activity throughout —
    this is the one scenario a short QA pass tends to skip, and the one most likely to
    surface a WorkManager scheduling issue (Doze mode / battery optimization killing a
    periodic job that a short test wouldn't run long enough to hit). Reconnect and
    verify a full, clean drain same as scenario 1.

## What "pass" means for this checklist item

Not "no crash observed once." For each scenario: the final data state is verifiably
correct (documented with before/after numbers, not just "looked fine"), the
`sync_queue` table is inspected directly at least once per scenario (not just the
UI), and every bug found is filed as its own checklist/issue entry rather than this
plan being re-run silently until it happens to pass.
