# Basic Unit Tests — Kya Add Kiya Gaya

## Kahan hain
`app/src/test/java/com/grocerypos/v11/` — ye Gradle ka standard "unit test"
source set hai: JVM par chalta hai, koi phone/emulator nahi chahiye.

Run karne ke liye:
```
./gradlew testDebugUnitTest
```
Ya Android Studio mein: `app/src/test` folder par right-click → Run Tests.

## Kya cover kiya gaya (3 files, 28 tests)

1. **`DiscountCalculatorTest.kt`** — Bill ka subtotal/discount/total/paid/due
   math. Sab clamp boundaries cover ki gayi hain (discount > subtotal,
   negative paid, paid > total, waghera) — ye wahi function hai jo Sale
   screen ka live preview aur actual save, dono use karte hain.

2. **`PasswordHasherTest.kt`** — Login/User Management ka PBKDF2 password
   hashing. Sahi/ghalat password, case-sensitivity, har hash ka unique salt,
   aur purani plain-text values (jaise pehle wala `"admin123"` bug) sahi
   tarah "not hashed" pehchane jayein — ye sab check kiya gaya hai.

3. **`ProductUnitConversionTest.kt`** — Sabse critical: 3-tier unit conversion
   (Carton → Box → Pcs jaisi ladder). unitLadder detection (1/2/3 tier),
   toSmallestUnits/fromSmallestUnits round-trip, case-insensitive unit
   matching, whole-number enforcement piece-based items ke liye, aur rate
   conversion (per-Box price → per-Carton price). Ye woh logic hai jo har
   Purchase/Sale/Return screen stock update karne se pehle use karta hai —
   yahan bug ho to poori app mein stock silently corrupt ho sakta hai.

## Note — sandbox mein run nahi kar saka
Is environment mein Android SDK/Gradle wrapper nahi hai, is liye maine tests
ko actual `gradlew test` se compile-verify nahi kiya — lekin har test
signature/field name asli source files (`Database.kt`, `DiscountCalculator.kt`,
`PasswordHasher.kt`) se match karke likha hai. Apne machine par pehli dafa
chalane ke baad agar koi chhoti compile error aaye (typo waghera) to bata
dena, main fix kar dunga.

## Agla step (jab chahein)
`SaveSaleUseCase` aur `SaveQuickSaleUseCase` (validation: empty items,
customer-required-for-due, stock issues) abhi test nahi ho paaye kyunke
`SaleRepository` ek concrete class hai jo seedha Room `PosDatabase` se bandhi
hai — usko test karne ke liye ya to `SaleRepository` ko ek interface bana kar
ek "fake" test double banana padega, ya phir Robolectric/instrumented test
add karna padega. Chahein to ye bhi kar deta hoon.

## Update — SaveSaleUseCase / SaveQuickSaleUseCase Ab Testable Hain

`SaleRepository.kt` ko do files mein split kiya gaya hai:
- **`SaleRepository.kt`** — ab sirf ek **interface** hai (koi Room/Android
  dependency nahi).
- **`RoomSaleRepository.kt`** — wahi purani poori implementation, bas naam
  badla aur `SaleRepository` interface ko implement karti hai. Behavior
  **bilkul same** hai, kuch bhi logic change nahi hua.
- `SaleViewModelFactory.kt` mein sirf ek line badli: ab `RoomSaleRepository(...)`
  construct karta hai (interface type ke through use hota hai) — baqi poori
  app (SaleActivity, SaleViewModel, UseCases) bina kisi change ke pehle jaisa
  kaam karti rahegi.

### Naye Tests (18 tests, 2 files)
- **`FakeSaleRepository.kt`** — test-only in-memory double (`app/src/test/...`),
  production code mein nahi hai.
- **`SaveSaleUseCaseTest.kt`** (11 tests) — empty items, customer-required-for-
  due, cash vs credit method switch, wholesale/retail mapping, edit-invoice
  reuse, discount-before-due-check order, case-insensitive customer matching,
  aur stock-issue error propagation.
- **`SaveQuickSaleUseCaseTest.kt`** (6 tests) — success/credit result mapping,
  customer name trimming, stock-unavailable aur invalid-qty error mapping.

Total ab **46 unit tests, 6 files** hain poore project mein.
