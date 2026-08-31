package com.grocerypos.v11.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.sqlite.db.SimpleSQLiteQuery
import com.grocerypos.v11.DeviceTag
import com.grocerypos.v11.PosDatabase
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backs up / restores the Room database file.
 *
 * - Main copy (used for Restore): app-specific storage at
 *   Android/data/com.grocerypos.v11/files/IBTISAAM POS Backups (no permission needed).
 * - Extra copy (for the user to see/share easily): public
 *   Downloads/IBTISAAM POS Backups folder (its own named subfolder, not mixed loose
 *   into Downloads), saved via MediaStore so no storage permission is needed on any
 *   Android version.
 * - shareBackup(): opens the Android Share menu so the user can send a
 *   backup file to Google Drive, WhatsApp, Gmail, etc. with one tap.
 *
 * UPDATED: folder renamed from generic "Backups" to "IBTISAAM POS Backups" (the shop's
 * software name) so it's identifiable when browsing storage — especially on the public
 * Downloads side, where it used to sit as loose files mixed in with every other download.
 * Filenames now also include the device tag (see DeviceTag.kt) so, on a 2-device setup
 * (Admin + Cashier), it's obvious which device a given backup file came from.
 */
object BackupHelper {

    private const val DB_NAME = "grocery_pos_v11.db"
    private const val FOLDER_NAME = "IBTISAAM POS Backups"

    fun backupFolder(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), FOLDER_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Copies the current database to the app's Backups folder AND to the
     * public Downloads folder, both with a timestamped, device-tagged name.
     * Returns the app-folder file (used internally for Restore/Share), or null on failure.
     *
     * FIX: Room runs in WAL (write-ahead logging) mode by default. Recent writes
     * (e.g. a purchase just added) live in the "<db>-wal" side file and are only
     * merged into the main .db file when SQLite performs a checkpoint. Copying
     * the raw .db file WITHOUT forcing a checkpoint first meant backups could
     * silently miss the most recent entries — and restoring such a backup would
     * wipe out anything added after the last checkpoint. We now force a full
     * checkpoint (via `PRAGMA wal_checkpoint(FULL)`) right before copying, so
     * the .db file always reflects every committed write at backup time.
     */
    fun backupNow(context: Context): File? {
        return try {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return null

            // Force WAL contents to be flushed into the main .db file so the
            // copy below is guaranteed to include every committed write,
            // including anything added moments ago.
            checkpointWal(context)

            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            // Device tag (e.g. "A1B2") identifies which device this backup came from —
            // useful once there are 2 devices (Admin + Cashier) producing backups.
            val fileName = "IBTISAAM_${DeviceTag.current}_backup_$stamp.db"

            // 1) App-specific copy (used by Restore and Share)
            val destFile = File(backupFolder(context), fileName)
            dbFile.copyTo(destFile, overwrite = true)

            // 2) Public Downloads copy (for the user to see/share manually)
            copyToDownloads(context, dbFile, fileName)

            destFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Forces SQLite to write everything currently sitting in the WAL file into
     * the main database file. Safe to call on the live, open database — it
     * does not close any connections.
     */
    private fun checkpointWal(context: Context) {
        try {
            val db = PosDatabase.get(context)
            db.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use { it.moveToFirst() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Saves a copy of the given file into a named subfolder inside public Downloads. */
    private fun copyToDownloads(context: Context, sourceFile: File, fileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER_NAME
                )
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val destFile = File(downloadsDir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Opens the Android Share menu (Google Drive, WhatsApp, Gmail, etc.)
     * for the given backup file. Call this right after backupNow(), or
     * from the Restore list, passing the file the user wants to send.
     */
    fun shareBackup(context: Context, file: File) {
        // FIX (Phase 5 - Stability): this was the one function in BackupHelper with no
        // try/catch — FileProvider.getUriForFile() can throw IllegalArgumentException
        // (e.g. file_paths.xml doesn't cover the file's folder) and startActivity() can
        // throw ActivityNotFoundException on a device with no share-capable app. Either
        // one previously crashed the app instead of just failing the share.
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Backup kis app se bhejein?"))
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(context, "Backup share nahi ho saka.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Lists available backup files (from the app folder), most recent first. */
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
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Restores from a content Uri returned by the system document picker
     * (ACTION_OPEN_DOCUMENT via [android.provider.DocumentsContract]). Unlike [restore],
     * this doesn't require the backup file to already sit inside the app's own
     * Backups folder — the picker can reach Downloads, a cloud-storage app, etc.
     * even on Android 11+, where a plain file manager is blocked from browsing
     * into Android/data/<package>/files by default. This is the reliable path for
     * "I reinstalled the app and need to bring back an old backup".
     */
    fun restoreFromUri(context: Context, uri: Uri): Boolean {
        return try {
            PosDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { streamIn ->
                dbFile.outputStream().use { streamOut -> streamIn.copyTo(streamOut) }
            }
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
