# Multi-device sync test matrix

1. Configure Device A and B with the same Firebase project and same valid Branch Code.
2. Create a product on A while both devices are online; verify B receives name/category/prices/3-tier units.
3. Put both devices offline. Sell 2 units on A and buy 5 on B. Reconnect both. Verify final stock = starting stock - 2 + 5.
4. While offline, change the same customer's balance on both devices. Reconnect both. Verify both balance deltas are preserved, not overwritten by a snapshot.
5. Create an expense on A, sync to B, delete it on A, sync again; verify it disappears on B.
6. Delete a sale/purchase on A; verify the tombstone removes the corresponding record and items on B.
7. Change product price on A and B offline; reconnect; verify one timestamp winner is recorded in Sync History rather than an endless ping-pong.
8. Reset a user password on A; verify the password remains local to A and no password hash appears in Firestore.
9. Verify OTP first-phone linking requires both OTP and the existing local account password.
