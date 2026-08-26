package com.grocerypos.v11.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.UUID

/**
 * Handles printing plain-text and Urdu ESC/POS receipts to a 58mm thermal printer,
 * over either Bluetooth (paired device) or USB (host mode).
 *
 * Manifest permissions needed (Bluetooth):
 *   <uses-permission android:name="android.permission.BLUETOOTH" />
 *   <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
 *   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
 *   <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
 *
 * USB needs no manifest permission declaration — access is granted per-device
 * at runtime via a system dialog (requestUsbPermission below).
 */
object PrinterHelper {

    enum class PrinterType { BLUETOOTH, USB }

    /**
     * A structured receipt line, used by [printReceiptLines].
     *
     * Why structured instead of one big pre-formatted string: a plain string
     * with manual space-padding (e.g. "Subtotal" + spaces + "Rs 100") only
     * lines up in a *monospace* font. We render with a real (often
     * proportional/Nastaliq) font, so padding-by-character-count never
     * actually aligns on the printed bitmap. Structured lines let us align by
     * *measured pixel width* instead, and pick RTL/LTR per line rather than
     * for the whole receipt at once (which previously scrambled English
     * lines whenever any Urdu text appeared anywhere in the receipt).
     */
    sealed class ReceiptLine {
        data class Center(val text: String) : ReceiptLine()
        data class Left(val text: String) : ReceiptLine()
        /** Label/value pair rendered as two columns, each right/left-aligned by measured width. */
        data class TwoCol(val left: String, val right: String) : ReceiptLine()
        data class Blank(val heightPx: Int = 10) : ReceiptLine()
        object Divider : ReceiptLine()
    }

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val ACTION_USB_PERMISSION = "com.grocerypos.v11.USB_PERMISSION"

    // ESC @ - initialize/reset printer
    private val ESC_INIT = byteArrayOf(0x1B, 0x40)
    // Feed a few lines then partial cut (GS V 1) - supported by most 58mm printers
    private val FEED_AND_CUT = byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x01)

    // Thermal paper width in dots for 58mm printers (most are 384 dots @ 203dpi)
    private const val PRINTER_DOTS_WIDTH = 384

    // Optional bundled Urdu font for correct Nastaliq/Naskh shaping when printing.
    // Place a font file at app/src/main/assets/fonts/NotoNastaliqUrdu-Regular.ttf
    // (or change this path) to use it; if missing, we fall back to the system
    // default font, which still renders Urdu via Android's own script fallback.
    private const val URDU_FONT_ASSET_PATH = "fonts/NotoNastaliqUrdu-Regular.ttf"

    // ================= BLUETOOTH =================

    fun hasBluetoothPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun requestBluetoothPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN
                ),
                requestCode
            )
        }
    }

    /** Already-paired Bluetooth devices (pair them from phone Settings first). */
    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> {
        if (!hasBluetoothPermission(context)) return emptyList()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun sendBluetoothBytes(context: Context, macAddress: String, payload: ByteArray): Boolean {
        if (!hasBluetoothPermission(context)) return false
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        var socket: BluetoothSocket? = null
        return try {
            val device = adapter.getRemoteDevice(macAddress)
            adapter.cancelDiscovery()
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            val out: OutputStream = socket.outputStream
            out.write(payload)
            out.flush()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // ================= USB =================

    /** Currently connected USB devices — shown to the user for manual pick. */
    fun usbDevices(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return manager.deviceList.values.toList()
    }

    fun hasUsbPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return manager.hasPermission(device)
    }

    /** Shows the system "Allow app to access USB device" dialog. Result arrives via the passed callback. */
    fun requestUsbPermission(context: Context, device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) { onResult(false); return }

        if (manager.hasPermission(device)) { onResult(true); return }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    onResult(granted)
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        manager.requestPermission(device, permissionIntent)
    }

    private fun findPrinterInterfaceAndEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (e in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(e)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
                ) {
                    return usbInterface to endpoint
                }
            }
        }
        return null
    }

    /** Sends raw ESC/POS bytes to a USB printer. Call requestUsbPermission first if needed. */
    private fun sendUsbBytes(context: Context, deviceName: String, payload: ByteArray): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = manager.deviceList.values.find { it.deviceName == deviceName } ?: return false
        if (!manager.hasPermission(device)) return false

        val (usbInterface, endpoint) = findPrinterInterfaceAndEndpoint(device) ?: return false
        var connection: UsbDeviceConnection? = null
        return try {
            connection = manager.openDevice(device) ?: return false
            connection.claimInterface(usbInterface, true)
            val sent = connection.bulkTransfer(endpoint, payload, payload.size, 5000)
            connection.releaseInterface(usbInterface)
            sent >= 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { connection?.close() } catch (_: Exception) {}
        }
    }

    // ================= UNIFIED BYTE SEND =================

    private fun sendRawBytes(context: Context, type: PrinterType, address: String, payload: ByteArray): Boolean {
        return when (type) {
            PrinterType.BLUETOOTH -> sendBluetoothBytes(context, address, payload)
            PrinterType.USB -> sendUsbBytes(context, address, payload)
        }
    }

    // ================= PLAIN TEXT PRINTING (ASCII/English) =================

    fun printText(context: Context, type: PrinterType, address: String, text: String): Boolean {
        val payload = ESC_INIT + text.toByteArray(Charsets.UTF_8) + FEED_AND_CUT
        return sendRawBytes(context, type, address, payload)
    }

    fun testPrint(context: Context, type: PrinterType, address: String, shopName: String = "IBTISAAM Kiryana Store"): Boolean {
        val sb = StringBuilder()
        sb.append("================================\n")
        sb.append("       TEST PRINT - 58mm\n")
        sb.append("================================\n")
        sb.append(shopName).append("\n")
        sb.append("Printer connected successfully.\n")
        sb.append("Connection: ").append(type.name).append("\n")
        sb.append("--------------------------------\n\n\n")
        return printText(context, type, address, sb.toString())
    }

    // ================= URDU PRINTING (rendered as image) =================

    /**
     * True if the text contains any Arabic-script characters (covers Urdu, since
     * Urdu is written using the Arabic script plus a few extra letters, all of
     * which fall in these Unicode blocks).
     *
     * IMPORTANT: callers should call this per-line (or per-column), not once
     * for an entire multi-line receipt. Checking the whole receipt at once
     * previously caused every English line (headers, "Ref:", "Date:", numeric
     * totals) to be laid out RTL just because *some* Urdu text appeared
     * somewhere else in the receipt.
     */
    private fun containsArabicScript(text: String): Boolean {
        for (ch in text) {
            val code = ch.code
            if (code in 0x0600..0x06FF ||   // Arabic
                code in 0x0750..0x077F ||   // Arabic Supplement
                code in 0x08A0..0x08FF ||   // Arabic Extended-A
                code in 0xFB50..0xFDFF ||   // Arabic Presentation Forms-A
                code in 0xFE70..0xFEFF      // Arabic Presentation Forms-B
            ) return true
        }
        return false
    }

    private var cachedUrduTypeface: Typeface? = null
    private var triedLoadingUrduFont = false

    /**
     * Loads a bundled Urdu font from assets for correct Nastaliq/Naskh shaping.
     * If no font is bundled at [URDU_FONT_ASSET_PATH], silently falls back to
     * Typeface.DEFAULT — Android will still shape the Urdu glyphs correctly via
     * its own system font fallback, just possibly in a different-looking style
     * than a proper Nastaliq font.
     */
    private fun resolveUrduTypeface(context: Context): Typeface {
        cachedUrduTypeface?.let { return it }
        if (triedLoadingUrduFont) return Typeface.DEFAULT
        triedLoadingUrduFont = true
        return try {
            val tf = Typeface.createFromAsset(context.assets, URDU_FONT_ASSET_PATH)
            cachedUrduTypeface = tf
            tf
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }

    /**
     * Renders a list of structured [ReceiptLine]s into a single bitmap, one line
     * at a time, so that:
     *  - each line's RTL/LTR direction is decided independently (fixes the
     *    whole-receipt-goes-RTL bug), and
     *  - [ReceiptLine.TwoCol] columns are aligned by *measured pixel width*
     *    (via Paint.measureText / Paint.Align) instead of space-padding,
     *    which is the only way to get straight columns with a non-monospace
     *    font.
     */
    private fun renderReceiptLines(lines: List<ReceiptLine>, fontSizePx: Float, typeface: Typeface): Bitmap {
        val margin = 8
        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = fontSizePx
            color = Color.BLACK
            this.typeface = typeface
        }
        val lineSpacingExtra = (fontSizePx * 0.35f).toInt()
        val contentWidth = PRINTER_DOTS_WIDTH - margin * 2

        data class Block(val line: ReceiptLine, val layout: StaticLayout?, val height: Int)

        val blocks = ArrayList<Block>(lines.size)
        var totalHeight = 8

        for (line in lines) {
            when (line) {
                is ReceiptLine.Center, is ReceiptLine.Left -> {
                    val text = if (line is ReceiptLine.Center) line.text else (line as ReceiptLine.Left).text
                    val dir = if (containsArabicScript(text)) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
                    val alignment = if (line is ReceiptLine.Center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
                    val layout = StaticLayout.Builder
                        .obtain(text, 0, text.length, paint, contentWidth)
                        .setAlignment(alignment)
                        .setTextDirection(dir)
                        .setLineSpacing(0f, 1.15f)
                        .build()
                    val h = layout.height + lineSpacingExtra
                    blocks.add(Block(line, layout, h))
                    totalHeight += h
                }
                is ReceiptLine.TwoCol -> {
                    val fm = paint.fontMetrics
                    val h = (fm.bottom - fm.top).toInt() + lineSpacingExtra
                    blocks.add(Block(line, null, h))
                    totalHeight += h
                }
                ReceiptLine.Divider -> {
                    val h = 10
                    blocks.add(Block(line, null, h))
                    totalHeight += h
                }
                is ReceiptLine.Blank -> {
                    blocks.add(Block(line, null, line.heightPx))
                    totalHeight += line.heightPx
                }
            }
        }

        val bitmap = Bitmap.createBitmap(PRINTER_DOTS_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = 4f
        for (block in blocks) {
            when (val line = block.line) {
                is ReceiptLine.Center, is ReceiptLine.Left -> {
                    canvas.save()
                    canvas.translate(margin.toFloat(), y)
                    block.layout!!.draw(canvas)
                    canvas.restore()
                    y += block.height
                }
                is ReceiptLine.TwoCol -> {
                    val fm = paint.fontMetrics
                    val baseline = y - fm.top
                    // Whichever side is Urdu naturally sits on the right (how Urdu reads);
                    // the plain/numeric side takes the opposite side. Alignment is by
                    // measured pixel width via Paint.Align, not character-count padding.
                    val leftIsUrdu = containsArabicScript(line.left)
                    val rightIsUrdu = containsArabicScript(line.right)

                    paint.textAlign = if (leftIsUrdu) Paint.Align.RIGHT else Paint.Align.LEFT
                    val leftX = if (leftIsUrdu) (PRINTER_DOTS_WIDTH - margin).toFloat() else margin.toFloat()
                    canvas.drawText(line.left, leftX, baseline, paint)

                    paint.textAlign = if (rightIsUrdu) Paint.Align.LEFT else Paint.Align.RIGHT
                    val rightX = if (rightIsUrdu) margin.toFloat() else (PRINTER_DOTS_WIDTH - margin).toFloat()
                    canvas.drawText(line.right, rightX, baseline, paint)

                    y += block.height
                }
                ReceiptLine.Divider -> {
                    val oldStroke = paint.strokeWidth
                    paint.strokeWidth = 2f
                    canvas.drawLine(
                        margin.toFloat(), y + block.height / 2f,
                        (PRINTER_DOTS_WIDTH - margin).toFloat(), y + block.height / 2f,
                        paint
                    )
                    paint.strokeWidth = oldStroke
                    y += block.height
                }
                is ReceiptLine.Blank -> {
                    y += block.height
                }
            }
        }
        return bitmap
    }

    /**
     * Converts a Bitmap into ESC/POS raster-image bytes (GS v 0), 1-bit monochrome,
     * using simple luminance threshold.
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
                val luminance = r * 0.3 + g * 0.59 + b * 0.11
                if (luminance < 128) {
                    val byteIndex = y * bytesPerRow + (x / 8)
                    val bitIndex = 7 - (x % 8)
                    imageData[byteIndex] = (imageData[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                }
            }
        }
        return header + imageData
    }

    /**
     * Preferred entry point: prints a receipt built from structured [ReceiptLine]s
     * with correct per-line direction and pixel-accurate column alignment.
     */
    fun printReceiptLines(
        context: Context,
        type: PrinterType,
        address: String,
        lines: List<ReceiptLine>,
        typeface: Typeface? = null,
        fontSizePx: Float = 30f
    ): Boolean {
        val resolvedTypeface = typeface ?: resolveUrduTypeface(context)
        val bitmap = renderReceiptLines(lines, fontSizePx, resolvedTypeface)
        val payload = ESC_INIT + bitmapToEscPosRaster(bitmap) + FEED_AND_CUT
        return sendRawBytes(context, type, address, payload)
    }

    /**
     * Prints Urdu (or mixed Urdu/English) plain text by rendering it as an image.
     * Kept for any existing callers that build a plain string. Each line of the
     * input is now given its own RTL/LTR direction (fixing the old whole-receipt
     * RTL bug); for real column alignment (labels/values, item qty/rate/amount),
     * prefer [printReceiptLines] with [ReceiptLine.TwoCol] instead of padding
     * with spaces, since a proportional font can't be aligned that way.
     */
    fun printUrduText(
        context: Context,
        type: PrinterType,
        address: String,
        text: String,
        typeface: Typeface? = null
    ): Boolean {
        val resolvedTypeface = typeface ?: resolveUrduTypeface(context)
        val lines: List<ReceiptLine> = text.split("\n").map { raw ->
            if (raw.isBlank()) ReceiptLine.Blank() else ReceiptLine.Left(raw)
        }
        val bitmap = renderReceiptLines(lines, 30f, resolvedTypeface)
        val payload = ESC_INIT + bitmapToEscPosRaster(bitmap) + FEED_AND_CUT
        return sendRawBytes(context, type, address, payload)
    }
}
