# Release Checklist

## Build
- [ ] `gradle lintDebug` passes
- [ ] `gradle testDebugUnitTest` passes
- [ ] `gradle assembleDebug` passes
- [ ] Release keystore supplied through Gradle properties / CI secrets

## Sync
- [ ] Firebase project configured
- [ ] Branch Code configured and validated
- [ ] Firebase `branch_members/{uid}` grants the device access to that branch
- [ ] Device A offline sale → Device B receives it after sync
- [ ] Device A offline purchase → Device B receives it
- [ ] Stock increments/decrements are correct with both devices offline
- [ ] Customer/supplier balance deltas are correct with both devices offline
- [ ] Expense create/delete sync correctly
- [ ] Product price/category/unit edits sync correctly
- [ ] User active/inactive sync correctly
- [ ] Password reset is understood as device-local by design
- [ ] Sync History contains no unresolved conflicts/failures

## Security
- [ ] Firestore rules deployed
- [ ] Anonymous Auth enabled only if the project intentionally uses it
- [ ] OTP first-phone link requires local password
- [ ] Backup password is stored only in Keystore-encrypted form
- [ ] No real passwords/API secrets committed to Git

## Backup / Restore
- [ ] Manual backup created
- [ ] Restore tested on a second phone with the owner password
- [ ] Automatic backup schedule tested

## Printer
- [ ] 58mm Bluetooth printer tested
- [ ] USB printer tested if used
- [ ] English receipt tested
- [ ] Urdu receipt tested for shaping/RTL

## Database
- [ ] Upgrade from the oldest supported Room version tested
- [ ] No destructive downgrade/upgrade path is relied on for production data
