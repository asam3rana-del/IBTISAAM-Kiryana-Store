package com.grocerypos.v11.util

import android.content.Context
import java.security.SecureRandom

/**
 * Stores the password used to encrypt/decrypt local backups (see [BackupCrypto]).
 *
 * A random password is generated automatically the first time it's needed, so
 * scheduled/automatic backups (noon, 9 PM, app-close) work without ever needing to
 * prompt the user. The shop owner should view it once from Settings > Backup and
 * write it down / save it somewhere safe — it's needed to restore a backup on a
 * different phone, or after reinstalling the app on this one. Changing the password
 * only affects backups made AFTER the change; older backups still need their
 * original password to restore.
 */
object BackupPasswordStore {
    private const val PREFS = "backup_prefs"
    private const val KEY_PASSWORD = "backup_password"

    private val CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9')

    /** Returns the current backup password, generating and saving a random one on first call. */
    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var pass = prefs.getString(KEY_PASSWORD, null)
        if (pass == null) {
            pass = generateRandomPassword()
            prefs.edit().putString(KEY_PASSWORD, pass).apply()
        }
        return pass
    }

    /** Lets the shop owner set their own password from Settings > Backup. */
    fun setPassword(context: Context, newPassword: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PASSWORD, newPassword).apply()
    }

    private fun generateRandomPassword(length: Int = 10): String {
        val random = SecureRandom()
        return (1..length).map { CHARS[random.nextInt(CHARS.size)] }.joinToString("")
    }
}
