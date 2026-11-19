# App License (Hanna Solutions) — setup

App ab sirf tab chalti hai jab us device ka license valid ho. Teen tareeqe:

| Tareeqa | Kaise | Kab tak |
|---|---|---|
| **Online (Firebase)** | Hanna ke Firebase (`my-grocery-pos-8a444`) mein `licenses/{deviceCode}` | `expiry` date tak — net na ho tab bhi (aakhri jawab yaad rehta hai) |
| **Offline key** | `IBT-YYYYMMDD-XXXXXXXXXX` (private generator se) | key ki date tak |
| **Trial** | kuch na ho to, pehli dafa app khulne se | 30 din |

- Online `active: false` = license **band** (offline key bhi nahi chalegi).
- Phone ki tareekh 1 din se zyada peeche ki to app ruk jati hai jab tak online check na ho.
- License band / khatam ho to bhi **data delete nahi hota** — License screen par Backup ka button hai.
- Aakhri 7 din: app khulne par roz ek dafa yaad-dehani.

## 1. Firestore rule (ek dafa)
Firebase console → Firestore → **Rules** → `match /{document=**}` (deny-all) se **pehle** ye daalein → Publish:

```
    // App license (Hanna Solutions): device apna license sirf PARH sakta hai.
    match /licenses/{code} {
      allow get: if request.auth != null;
      allow list, write: if false;
    }
```
(Flutter repo `firebase/firestore.rules` mein bhi yahi daal dein, taake agli deploy mein na mitay.)

## 2. Dukaan ka license dena / renew / band
1. Dukaan wala: **Settings → License** → "This device's code" (jaise `ABCDE-12345`) aap ko bheje.
2. Firebase console → Firestore → collection **`licenses`** → document ID = woh code (dash ke saath, jaisa screen par hai) → fields:
   - `active` (boolean) = `true`
   - `expiry` (string) = `2027-01-31`
   - `shop` (string) = dukaan ka naam (sirf aap ki pehchan ke liye)
3. Dukaan wala License screen par **"Check again"** dabaye (ya app khud 6 ghante mein check kar leti hai).
- Renew: `expiry` badal dein. Band: `active` = `false`.

## 3. Offline key (internet ke baghair)
- GitHub repo → Settings → Secrets and variables → **Actions** → New repository secret:
  - `LICENSE_KEY_SECRET` = private generator file wala secret (repo public hai — secret kabhi code mein na likhein)
  - `LICENSE_SUPPORT_WHATSAPP` = aap ka WhatsApp number, jaise `923001234567` (License screen par WhatsApp button)
- Secret ke baghair bani APK mein offline key ka option chhupa rehta hai (online + trial chalte rahenge).
- Key: private `IBTISAAM-License-Key-Generator.html` (sirf aap ke paas) → device code + date → key. Dukaan wala: Settings → License → **Enter license key**.
- Secret leak ho jaye: naya secret banayein, GitHub Secret badlein, nayi APK — purani keys band ho jayengi.

Code: `LicenseLogic.kt` (rules, tested in `LicenseLogicTest`), `LicenseManager.kt`, `ui/LicenseActivity` (`LicenseActivity.kt`), check in `MainActivity.onCreate/onResume`.
