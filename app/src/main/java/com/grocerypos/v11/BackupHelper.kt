package com.grocerypos.v11.util

import android.content.Context
import com.grocerypos.v11.PosDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backs up / restores the Room database file to app-specific external storage.
 * Location: Android/data/com.grocerypos.v11/files/Backups  (no runtime permission needed).
 */
object BackupHelper {

    private const val DB_NAME = "grocery_pos_v11.db"

    fun backupFolder(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "Backups")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Copies the current database to the Backups folder with a timestamped name. */
    fun backupNow(context: Context): File? {
        return try {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return null
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val destFile = File(backupFolder(context), "backup_$stamp.db")
            dbFile.copyTo(destFile, overwrite = true)
            destFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Lists available backup files, most recent first. */
    fun listBackups(context: Context): List<File> {
        return backupFolder(context)
            .listFiles { f -> f.extension == "db" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Restores the given backup file over the live database.
     * Closes the current Room instance first. The app should be restarted
     * after a successful restore so Room re-opens the restored file cleanly.
     */
    fun restore(context: Context, backupFile: File): Boolean {
        return try {
            PosDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            backupFile.copyTo(dbFile, overwrite = true)
            // Remove stale write-ahead-log files so Room doesn't get confused
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
