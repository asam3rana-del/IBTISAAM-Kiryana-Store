package com.grocerypos.v11

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * NEW (Flutter parity, batch 4): party photos — like Flutter's party_photo_store.dart the photo
 * stays ON THIS DEVICE only (never synced; Firestore docs stay small). Saved as a down-scaled
 * JPEG in filesDir/party_photos/<c|s>_<id>.jpg, so it is also outside the cache and survives.
 */
object PartyPhotoStore {
    private const val MAX = 512

    private fun file(ctx: Context, isCustomer: Boolean, id: Long) =
        File(File(ctx.filesDir, "party_photos").apply { mkdirs() }, (if (isCustomer) "c_" else "s_") + id + ".jpg")

    fun load(ctx: Context, isCustomer: Boolean, id: Long): Bitmap? {
        val f = file(ctx, isCustomer, id)
        return if (f.exists()) try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null } else null
    }

    fun remove(ctx: Context, isCustomer: Boolean, id: Long) { file(ctx, isCustomer, id).delete(); thumbs.remove(key(isCustomer, id)) }

    // NEW (batch 9): small cached thumbnails for the party LIST (decoded once, ~128 px).
    private val thumbs = HashMap<String, Pair<Long, Bitmap?>>()
    private fun key(isCustomer: Boolean, id: Long) = (if (isCustomer) "c" else "s") + id

    fun thumb(ctx: Context, isCustomer: Boolean, id: Long): Bitmap? {
        val f = file(ctx, isCustomer, id)
        if (!f.exists()) return null
        val k = key(isCustomer, id)
        thumbs[k]?.let { (mod, b) -> if (mod == f.lastModified()) return b }
        val b = try { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }) } catch (e: Exception) { null }
        thumbs[k] = f.lastModified() to b
        return b
    }

    /** NEW (batch 9): camera preview bitmap → saved like a gallery photo. */
    fun saveBitmap(ctx: Context, isCustomer: Boolean, id: Long, raw: Bitmap): Bitmap? = try {
        val scale = MAX.toFloat() / maxOf(raw.width, raw.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true) else raw
        FileOutputStream(file(ctx, isCustomer, id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        thumbs.remove(key(isCustomer, id))
        bmp
    } catch (e: Exception) { null }

    /** Reads [uri], scales the long side to ≤512 px and saves it. Returns the saved bitmap or null. */
    fun save(ctx: Context, isCustomer: Boolean, id: Long, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX && bounds.outHeight / (sample * 2) >= MAX) sample *= 2
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("decode")
        val scale = MAX.toFloat() / maxOf(raw.width, raw.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true) else raw
        FileOutputStream(file(ctx, isCustomer, id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        thumbs.remove(key(isCustomer, id))
        bmp
    } catch (e: Exception) { null }
}
