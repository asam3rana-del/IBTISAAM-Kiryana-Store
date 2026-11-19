# Flutter → Kotlin parity (Hanna POS ki updates IBTISAAM Kotlin app mein)

Source: AH-Developer repo, branch `Raffey`. Sab naye features **wahi Firestore collections / field names** use karte hain jo Flutter app karti hai, is liye dono apps ek hi data dekhti hain.

## Batch 1 — Wasooli + Amanat/Loan (2026-11-18) ✅ (CI build se confirm karna baqi)

- **DB v49 → v50** (`MIGRATION_49_50`, khud chalta hai): `customers` mein `dueDays, kind, returnDate, returnEntries, returnLog, paySchedule, qistPlan`; naya table `due_followups`.
- **Amanat / Loan screen** (`AmanatLoanActivity`, sirf admin/manager): nayi party ya maujooda customer, kai entries, entry edit/delete, wapsi/wasooli (FIFO, Cash/Bank register mein OUT/IN), wapsi ki tarikh, WhatsApp/Call, party edit/hatana. Dashboard tile "Deposit / Loan".
- **Collection screen** (`CollectionActivity`, dashboard tile "Collection"):
  - *Aaj ki list* (Wasooli Autopilot): TODO / DONE / BAAD MEIN, "Send next" button (WhatsApp + follow-up khud likha jata hai), aaj ki wasooli.
  - *Sab baqi*: filters (aaj ka kaam, late, wada toota, wade par, kabhi baat nahi, tanazu).
  - Party sheet: follow-up history, nateeja/wada likhna, payment ka din, qist plan (admin/manager), "Paisa mila" (payment ke baad baqi ka wada poochta hai), kahata kholna.
- **Due date renewal**: customer ki payment ke baad uske baqi dated bills ki due date aage (PartyTransactionActivity).
- **Sync**: `due_followups` push/pull/apply; customer doc ke naye fields push/pull. FIX: pull hui party (doosre phone se) ka edit ab usi doc par jata hai, naya duplicate doc nahi banta (`customerEntityId` / `supplierEntityId` pehle `serverId` dekhte hain).
- Logic files: `CollectionLogic.kt` (pure, Flutter models ka 1:1 port), `CollectionRepository.kt` (DB), `CollectionUi.kt` (chhote view helpers).

**Firestore:** live rules/indexes Flutter repo se deploy hain aur `due_followups` pehle se shamil hai — kuch deploy nahi karna. ⚠️ Is repo ki `firestore.rules` purani hai, ise Firebase par **deploy na karein** (Flutter wali rules ko overwrite kar degi).

### Batch 1 mein abhi nahi (baad mein)
- Collection ticker (LED board), roz ki notifications (Autopilot + Amanat wapsi ki tarikh)
- Party ledger/statement mein Amanat history rows, Receivables (aging) screen
- Customer ki default muddat (dueDays) set karne ka UI, ek payment ko kai bills par baantna

## Batch 2 — Shop Rents + Utility Bills + Workers (2026-11-18) ✅ (CI build se confirm karna baqi)

- **DB v50 → v51** (`MIGRATION_50_51`, khud chalta hai): naye tables `workers, worker_attendance, worker_advances, worker_salary_payments, shop_rents, shop_rent_terms, shop_rent_ledger, utility_bills, utility_bill_payments` (Flutter jaise hi columns).
- **Settings → Shop Rents / Workers / Utility Bills** (teeno sirf admin/manager).
- **Shop Rents** (`ShopRentActivity`): har mahine ka rent khud due, Pay Rent (sab se purana mahina pehle; Expense "Rent" + Cash OUT; zyada diya to advance), Give Advance (Cash OUT, expense nahi), Deduct from Advance (Expense + barabar Cash IN/OUT), Change Rent (% / naya, is ya agle mahine se), Waive month, Close/Reopen, Delete, History (entry hatao), Monthly report.
- **Workers** (`WorkersActivity`): monthly / daily, mahine ka hazri grid (P/A/H/L, tap se badlo, "khali din hazir"), advance (Cash OUT), salary (Expense "Salaries"/"Wages" + Cash OUT sirf net, advance katauti), undo salary, salary ke baad hazri lock.
- **Utility Bills** (`UtilityBillsActivity`): bijli/gas/pani/internet/phone, due din, status (overdue / aaj / kal / baqi din / paid), pay (Expense "Utility Bills" + Cash OUT, chahein to sirf record), kisi bhi mahine ki payment, history + undo, band/shuru, delete.
- **Sync**: 8 collections (`workers, worker_attendance, worker_advances, worker_salary_payments, shop_rents, shop_rent_ledger, utility_bills, utility_bill_payments`) Flutter jaise ids/fields ke saath push/pull/apply; pull fail-soft.
- Files: `PayrollRentLogic.kt` (pure maths), `PayrollRentRepository.kt` (DB), `WorkersActivity.kt`, `ShopRentActivity.kt`, `UtilityBillsActivity.kt`.

### Batch 2 mein abhi nahi
- Utility bill reminders (notification), Recycle Bin mein hatai hui cheezen (Batch 3 ke Recycle Bin ke saath)

## Batch 3 — Repair Tools + Recycle Bin (2026-11-18) ✅ (CI build se confirm karna baqi)

- **DB v51 → v52** (`MIGRATION_51_52`): naya table `recycle_bin` (sirf is phone par, sync nahi — Flutter jaisa).
- **Settings → Repair Tools** (`RepairToolsActivity`):
  - *Bill Cost Repair* (admin): unit badalne se pehle ke bills ki phooli hui cost wali lines (cost > sale × 1.5) dhoondh kar item ki maujooda cost se dobara; chuni lines par apply, bills dobara sync.
  - *Cost Check* (admin/manager): jin items ki average cost aakhri purchase rate se 1.5× door hai, unhein aakhri rate par.
  - *Stock Rebuild* (admin): chune mahine ki purchase − us ke baad ki sale = stock (0 se kam nahi), Stock Take entries ke zariye.
  - *Month Cleanup* (admin): ek mahine ke saare sale + purchase bills normal Delete se (stock/balance/cash/sync sab wapas). "DELETE" likh kar tasdeeq.
- **Settings → Recycle Bin** (`RecycleBinActivity`, admin): Expense, Customer, Supplier, Worker, Shop Rent delete hone par snapshot; Restore (wahi ids, cash rows samet, cloud par doc dobara zinda — `enqueueUndelete`), ya hamesha ke liye hatao.
- Files: `RepairToolsRepository.kt`, `RepairToolsActivity.kt`, `RecycleBinRepository.kt`, `RecycleBinActivity.kt`; hooks: `ExpenseActivity`, `PartyRepository`, `PayrollRentRepository`.

### Batch 3 mein abhi nahi
- **Pending Cost** (minus stock par biki lines ki cost baad mein bharna): Kotlin ke sale flow mein "pending cost" ka system (sale_items.pendingQty/pendingEst + purchase par backfill) hi nahi hai — alag kaam hai.
- Sale / Purchase bills Recycle Bin mein (Kotlin mein bill delete 4-5 screens mein alag alag likha hai).

## Batch 4 — Reports + PDF slips + party photos (2026-11-18) ✅ (CI build se confirm karna baqi)

- **Koi DB change nahi** (version 52 hi), sirf naya DAO `ReportsExtraDao` (read-only queries).
- **Settings → Receivables (Aging)** (`ReceivablesActivity`): customer udhaar 6 dibbon mein — Abhi waqt baqi / 1–7 / 8–30 / 31–60 / 60+ din late / Tareekh nahi. Dibba dabao to us ki parties, party dabao to khata.
- **Settings → Returns Report** (`ReturnsReportActivity`): Sale / Purchase returns, Aaj / 7 din / Is mahine / Sab; har bill ka total, kitna wapas aaya, kitna baqi.
- **Settings → Expiry Report** (`ExpiryReportActivity`): stock wale items jin ki expiry guzar chuki ya 30 din mein hai (tareekh `2026-12-31`, `31/12/2026` ya `12/2026`), lagat ki qeemat samet.
- **PDF slips** (`SlipPdf.kt`): Party khate mein *Statement* aur payment ki *Receipt* share karte waqt "PDF slip" ya "Text message" ka option. PDF Android ke apne text layout se banti hai, is liye Urdu naam sahi jurte hain.
- **Party photos** (`PartyPhotoStore.kt`): khate ke upar gol tasveer — dabao to Gallery se lagao / badlo / hatao. Sirf isi phone par (sync nahi), Flutter jaisa.
- Logic tests: `Aging` + `Expiry` 18/18 pass.

### Batch 4 mein abhi nahi
- Party photo camera se (abhi sirf Gallery); photo party list mein (sirf khate ke header mein).
- Bill (sale/purchase) ki PDF — wo pehle se `BillPreviewActivity` mein image share hoti hai.

## Batch 5 — Reminder notifications (2026-11-18) ✅ (CI build se confirm karna baqi)

- `ReminderNotifications.kt` (`ReminderText` = pure text/faisle, `ReminderNotifier`, `ReminderWorker`). Har 15 min WorkManager + app khulne par (`MainActivity.onResume`) check.
- **Aaj ki wasooli** (har login user): subah 10 baje ke baad ek baar, shaam 6 baje ke baad dobara agar ab bhi parties baqi hon. Matn Flutter wala ("X parties ko yaad dilana baqi … Pehle: …"). Tap → Wasooli screen.
- **Deposit / Loan returns** (admin / manager): subah 10 baje, jin ki wapsi ki tareekh aaj ya guzar chuki. Tap → Amanat / Loan screen.
- **Utility bills** (admin / manager): subah 9 baje — ek din pehle, due wale din, aur guzarne ke baad roz ek baar; har bill ki apni "reminder" setting. Tap → Utility Bills.
- Android 13+ par notification ki ijazat app khulne par ek dafa maangi jati hai (`POST_NOTIFICATIONS` manifest mein). **Settings → Reminders**: wasooli + amanat reminders On/Off, aur phone ki notification settings ka shortcut.
- Tests: `ReminderText` 18/18 pass.

## Batch 6 — Udhaar muddat (due days) + LED Ticker (2026-11-18) ✅ (CI build se confirm karna baqi)

- `DueDays.kt`: **naye udhaar bill par khud due date** = bill ki tareekh + customer ki apni muddat (`Customer.dueDays`), wo na ho to dukaan ki default (7 din). Normal sale + Quick Sale dono (`RoomSaleRepository`). Edit kiye bill ki tareekh nahi badalti; Due Reminders se haath se tareekh pehle jaisi.
- **Settings → Default Credit Period** (admin/manager): din likho, 0 = band. app_settings key `default_due_days` (Flutter jaisi, sirf is phone par).
- **Customer edit** (Parties → Edit, admin/manager): naya khana "Udhaar muddat (din)" — khali = dukaan ki default. Sync mein `dueDays` pehle se jata hai (batch 1).
- **Wasooli → 📺 LED board** (`CollectionTickerActivity`): kaali screen, beech mein ek ek party bari (har 5 sec), neeche chalti line, ghari, Baqi / Ho chuka / Aaj wasool. Har minute khud refresh, screen band nahi hoti.
- Tests: `DueDays` 8/8 pass.

## Batch 7 — Sale / Purchase bills Recycle Bin mein (2026-11-18) ✅ (CI build se confirm karna baqi)

- `BillRecycle.kt`: poora bill delete hone par (Sale screen, Sale History, History, Purchase delete — sab `RoomPurchaseRepository.deletePurchase` se) usi transaction mein snapshot: bill, lines, us ki payments + cash, bill se jurri payments (+ cash), aur har line ka kitna stock hila.
- **Restore** bilkul delete ka ulta, wahi invoice / billNo: Sale ka stock dobara nikalta hai (stock kam ho to restore ruk jata hai aur wajah batata hai), customer ka baqi (total − paid) wapas; Purchase ka stock + weighted-average cost wapas, supplier ka baqi wapas; payments/cash wapas; cloud par docs dobara zinda.
- Repair Tools ka *Month Cleanup* bhi normal delete use karta hai, is liye us ke bills bhi bin mein aate hain.
- Party khate mein aakhri line hata kar bill khatam hona bin mein nahi jata (line-level delete).
- DB version wahi 52 (sirf naye DAO functions). Tests: cost math 6/6 pass.

## Batch 8 — Pending Cost (2026-11-18) ✅ (CI build se confirm karna baqi)

- **DB v52 → v53** (`MIGRATION_52_53`): `sale_items` par 3 naye columns `pendingQty`, `pendingEst`, `backfillLog` (bilkul Flutter wale, sync mein bhi same keys).
- `CostBackfill.kt` (Flutter `cost_backfill.dart` ka port): sale par jis line ki asal cost maloom nahi (item ki cost 0, ya stock kam) wo hissa "pending". Item ki purchase aane par purani sale pehle bharti hai (cost = cost − andaza + qty × purchase rate). Purchase delete / return / edit / line edit / line delete par us bill ka bhara hua hissa wapas pending, phir maujooda lines se dobara.
- Hooks: `RoomSaleRepository` (sale + quick sale + edit), `RoomPurchaseRepository` (save/edit/delete), purchase return (History + Purchase History), Party khata (purchase line edit/delete, sale line qty edit), Recycle Bin purchase restore.
- **Settings → Pending Cost** (`PendingCostActivity`, admin): pending lines ki list; chuni lines item ki maujooda cost se bharo (jab cost baad mein likhi ho, purchase ke baghair). Profit par asar pehle dikhata hai.
- Note: Kotlin ka sale screen stock se zyada bechne nahi deta, is liye yahan pending zyada tar "item cost 0" wali sales se banegi; Flutter se sync hui minus-stock sales bhi isi tarah bharengi.
- Tests: Flutter ke `cost_backfill_test` ke saare cases + Flutter log JSON compatibility, 15/15 pass.

## Batch 9 — Chhoti baqi cheezen (2026-11-18) ✅ (CI build se confirm karna baqi)

- **Party khate mein Amanat / Loan history** (Flutter `ledgerLogRows`): "Amanat wasool hui / wapas ki / Qarz diya / wasool hua / ayjast / entry hatai" rows, sirf dikhane ke liye (raqam opening balance mein pehle se hai, dobara nahi gini jati). Purani device ki wajah se log adhoora ho to ek "pehle ka record" row taake kul hamesha mile. (`ReturnLog.ledgerRows` in `CollectionLogic.kt`.)
- **Party photo camera se** bhi (Gallery ke ilawa) — camera ki ijazat pehle maangi jati hai.
- **Party list mein photo** (Parties screen) — icon ki jagah gol tasveer (chhota cached thumbnail).
- "Ek payment kai bills par baantna": Flutter mein ye function UI mein kahin use hi nahi hota, is liye port nahi kiya.
- Tests: ledger rows 3/3 pass.

## Tests (CI mein khud chalte hain)

- `app/src/test/java/com/grocerypos/v11/parity/ParityBatch1…9*Test.kt` — har batch ki pure logic (Wasooli autopilot, payroll/rent/bills, repair math, aging/expiry, reminders, due days, restore cost, Pending Cost — Flutter `cost_backfill_test` ke cases samet, Amanat ledger). GitHub Actions ka `testDebugUnitTest` step inhein chalata hai; koi bhi case toote to build wahin ruk jati hai aur FAIL line case ka naam batati hai.
- Ye tests Android / org.json par nahi chalte (JVM par seedhe), is liye CI mein "not mocked" errors nahi aate.

## Baqi
- Flutter ki taraf se koi feature baqi nahi.
