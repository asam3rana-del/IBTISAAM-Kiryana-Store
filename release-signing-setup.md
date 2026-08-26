# Release signing setup (do this once)

Ye step aap khud apne computer par karein — keystore private hoti hai, kisi aur ko
nahi deni (na GitHub repo mein commit karni hai).

## 1. Release keystore banayein
```
keytool -genkey -v -keystore release.keystore -alias ibtisaam \
  -keyalg RSA -keysize 2048 -validity 10000
```
Ye aapse store password + key password poochega — dono yaad rakhein, sambhaal kar
kahin save kar lein (password manager mein). Ye file (`release.keystore`) kisi ke
saath share na karein, na hi git mein add karein.

## 2. Local build ke liye (agar Android Studio se banate hain)
`~/.gradle/gradle.properties` (repo ke andar NAHI, apne home folder mein) mein ye
4 lines add karein:
```
RELEASE_STORE_FILE=/full/path/to/release.keystore
RELEASE_STORE_PASSWORD=<jo password banaya>
RELEASE_KEY_ALIAS=ibtisaam
RELEASE_KEY_PASSWORD=<jo password banaya>
```
Phir: `./gradlew bundleRelease` ya `./gradlew assembleRelease`.

## 3. GitHub Actions se build ke liye (naya `build-release` job already add kar diya hai)
GitHub repo → Settings → Secrets and variables → Actions → "New repository secret",
ye 4 secrets add karein:

| Secret name | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -i release.keystore` ka output |
| `RELEASE_STORE_PASSWORD` | store password |
| `RELEASE_KEY_ALIAS` | `ibtisaam` |
| `RELEASE_KEY_PASSWORD` | key password |

Isके baad `Dusri-branch` par push karne se `build-release` job automatically ek
signed APK bana kar Actions artifacts mein daal dega.

## 4. Firebase OTP login ke liye SHA fingerprints
Firebase console → Project settings → apni Android app (`com.grocerypos.v11`) → "Add
fingerprint" — is release keystore ka SHA-1 aur SHA-256 add karein:
```
keytool -list -v -keystore release.keystore -alias ibtisaam
```
Ye na kiya to release build mein Phone OTP login fail hoga (debug keystore ka
fingerprint release build ko cover nahi karta).

## 5. Firestore rules aur indexes deploy karna
```
npm install -g firebase-tools   # agar pehle se nahi hai
firebase login
firebase deploy --only firestore:rules,firestore:indexes
```
(Isse `firestore.rules` aur `firestore.indexes.json` — dono is repo mein already
add kar diye gaye hain — Firebase project par apply ho jayenge.)
