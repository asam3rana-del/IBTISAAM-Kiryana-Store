// Add this to PrinterHelper.kt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

// Thermal paper width in dots for 58mm printers (most are 384 dots @ 203dpi)
private const val PRINTER_DOTS_WIDTH = 384

/**
 * Renders Urdu (or any Unicode) text into a monochrome bitmap using Android's
 * own text engine, which handles Arabic/Urdu shaping + RTL correctly —
 * something the printer firmware cannot do.
 */
private fun renderTextToBitmap(text: String, fontSizePx: Float = 28f): Bitmap {
    val paint = TextPaint().apply {
        isAntiAlias = true
        textSize = fontSizePx
        color = Color.BLACK
        // Use a system font that has full Arabic/Urdu glyph coverage.
        // "Noto Sans Arabic" / "Noto Nastaliq Urdu" bundled as an asset gives best shaping.
        typeface = Typeface.DEFAULT
    }

    val layout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, PRINTER_DOTS_WIDTH)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL) // StaticLayout auto-detects RTL runs
        .setLineSpacing(0f, 1.1f)
        .build()

    val bitmap = Bitmap.createBitmap(PRINTER_DOTS_WIDTH, layout.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    layout.draw(canvas)
    return bitmap
}

/**
 * Converts a Bitmap into ESC/POS raster-image bytes (GS v 0), 1-bit monochrome,
 * using simple threshold dithering.
 */
private fun bitmapToEscPosRaster(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val bytesPerRow = (width + 7) / 8

    val header = byteArrayOf(
        0x1D, 0x76, 0x30, 0x00,                 // GS v 0, mode 0 (normal)
        (bytesPerRow and 0xFF).toByte(),
        ((bytesPerRow shr 8) and 0xFF).toByte(),
        (height and 0xFF).toByte(),
        ((height shr 8) and 0xFF).toByte()
    )

    val imageData = ByteArray(bytesPerRow * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = bitmap.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luminance = (r * 0.3 + g * 0.59 + b * 0.11)
            val isBlack = luminance < 128
            if (isBlack) {
                val byteIndex = y * bytesPerRow + (x / 8)
                val bitIndex = 7 - (x % 8)
                imageData[byteIndex] = (imageData[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }
        }
    }
    return header + imageData
}

/** Prints Urdu (or mixed Urdu/English) text by rendering it as an image first. */
fun printUrduText(context: Context, type: PrinterType, address: String, text: String): Boolean {
    val bitmap = renderTextToBitmap(text)
    val payload = ESC_INIT + bitmapToEscPosRaster(bitmap) + FEED_AND_CUT
    // Reuse your existing raw-byte send paths — split printBluetooth/printUsb
    // to accept a ByteArray instead of a String, e.g.:
    //   printBluetoothBytes(context, address, payload)
    //   printUsbBytes(context, address, payload)
    return sendRawBytes(context, type, address, payload)
}
