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
import android.text.TextUtils
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
        /** Label/value pair rendered as two columns, each right/left-aligned by measured width.
         *  [bold] renders both sides in bold — used to make the TOTAL line stand out. */
        data class TwoCol(val left: String, val right: String, val bold: Boolean = false) : ReceiptLine()
        data class Blank(val heightPx: Int = 10) : ReceiptLine()
        object Divider : ReceiptLine()

        /**
         * A bordered table row — one or more cells laid out in fixed-width
         * columns (proportioned by [weights]), with a vertical divider line drawn
         * between every column and a horizontal divider line drawn under the row.
         *
         * Kept for callers that still want a ruled grid somewhere on the receipt.
         * The item table itself no longer uses this (see [Row3]/[ItemRow] below) —
         * the customer wanted the printed item list to match the on-screen preview
         * card, which has no grid lines at all.
         *
         * [cells] and [weights] must be the same size — weights are relative (they
         * don't need to sum to any particular number; a column with weight 3 is 3x
         * as wide as one with weight 1).
         *
         * [bold] renders the row in bold (used for the header row).
         * [topBorder] additionally draws a line above this row.
         *
         * Cell text that doesn't fit its column width is ellipsized ("…") rather
         * than wrapped, so every row stays exactly one line tall and the grid lines
         * stay perfectly straight.
         */
        data class TableRow(
            val cells: List<String>,
            val weights: List<Float>,
            val bold: Boolean = false,
            val topBorder: Boolean = false
        ) : ReceiptLine()

        /**
         * Plain, borderless 3-column row (col1 left/RTL-aware, col2 centered,
         * col3 right-aligned) — used for the "Item / Qty / Amount" header row
         * so it matches the on-screen preview card, which has no grid.
         */
        data class Row3(
            val col1: String,
            val col2: String,
            val col3: String,
            // Amount widened further (2.2 -> 2.7) per request — Item trimmed to
            // compensate so the row keeps using the same total width.
            val weights: List<Float> = listOf(2.3f, 1.2f, 2.7f),
            val bold: Boolean = false
        ) : ReceiptLine()

        /**
         * One item row rendered exactly like the on-screen preview card:
         * line 1 is the item name (bold, left/RTL-aware) with qty centered and
         * amount right-aligned in the same row; line 2 is "@ rate" in smaller
         * plain text directly under the name. No borders, no grid — just the
         * two stacked lines per item, same as BillPreviewActivity's `kv`-style
         * item rows on screen.
         */
        data class ItemRow(
            val name: String,
            val qty: String,
            val rate: String,
            val amount: String,
            // Amount widened (was 1.7) per request — uses more of the right-side
            // space so the Amount column isn't left with unused blank paper.
            // Qty kept the same width that already fixed the earlier overlap.
            val weights: List<Float> = listOf(2.8f, 1.2f, 2.2f)
        ) : ReceiptLine()
    }

    // Urdu/Arabic item names were reported "muskil se parha jata" (barely readable)
    // at the same small size used for numeric cells. Item names are drawn noticeably
    // bigger and slightly bolder than the numeric columns for legibility on thermal
    // print, on top of the wider item column.
    private const val ARABIC_ITEM_FONT_BOOST = 1.2f

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val ACTION_USB_PERMISSION = "com.grocerypos.v11.USB_PERMISSION"

    // ESC @ - initialize/reset printer
    private val ESC_INIT = byteArrayOf(0x1B, 0x40)

    // REVERTED: an ESC 7 "heating density" command (0x1B 0x37 ...) was tried here as an
    // experimental hardware-level darkness boost. On this printer it was NOT interpreted
    // as a heating command — the firmware read it as something else entirely (looked like
    // a code-page/character-set switch) and the whole receipt printed as corrupted
    // symbol/CJK garbage afterward. Removed completely; do not re-add this command for
    // this printer. Darkness improvements are limited to the bitmap-level changes above
    // (FILL_AND_STROKE synthetic bold + the 195 luminance threshold) — if those aren't
    // enough, the fix has to be a hardware one (printer settings/firmware, or the head
    // itself), not another blind ESC/POS command guess.

    // Feed a few lines then partial cut (GS V 1) - supported by most 58mm printers.
    // NOTE: printers with no cutter hardware (most handheld/mobile 58mm Bluetooth
    // printers) simply ignore an unsupported cut command, so this is safe to always send.
    private val FEED_AND_CUT = byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x01)

    // Thermal paper width in dots for 58mm printers (most are 384 dots @ 203dpi).
    //
    // FIX (blank strip on the right side of the paper): widening the Amount column's
    // weight alone didn't close the gap — confirmed this isn't a column-sizing issue,
    // it's the bitmap itself being narrower than this printer's actual print head.
    // Raised 384 -> 480 (a common width for higher-density 58mm printers and some
    // 80mm units) so the rendered receipt uses more of the physical paper.
    //
    // HOW TO TUNE FURTHER: reprint and check the right edge. If there's still a
    // blank strip, raise this again (try 512, then 576). If instead text starts
    // getting cut off or wrapping onto a second physical line, drop back down
    // (try 420, then 400) until it prints cleanly with no leftover blank margin.
    private const val PRINTER_DOTS_WIDTH = 480

    // FIX (print reliability): a whole multi-item receipt was previously rendered as
    // ONE raster image and sent to the printer in a single GS v 0 command. Long bills
    // (many items) produce a tall bitmap, and a lot of cheap 58mm ESC/POS printers
    // (Bluetooth SPP in particular) have a small internal receive/render buffer — a
    // single oversized raster command either gets truncated, prints garbled/blank, or
    // the printer just stops responding partway through. This is the most common cause
    // of "print theek nahi aata" on longer bills. The fix: split the bitmap into safe
    // horizontal strips and send them as separate GS v 0 commands, with a short pause
    // between each so the printer's buffer has time to actually print/clear before the
    // next chunk arrives.
    //
    // FIX 2 (overlapping / "double exposure" print — receipt lines printing on top of
    // each other, e.g. "Date" merging into the next line, item names showing as
    // garbled/tangled marks): real-world testing on a customer's printer showed this
    // STILL happening with the original 200px/40ms values, and even after the first
    // round of tuning (80px / 3ms-per-row / 60ms floor) some units continued to
    // overlap — most visible on Urdu lines, where two overlapping cursive lines
    // produce meaningless tangled shapes instead of legible letters (this is what
    // looked like "garbled Urdu font" but was actually two strips overlapping on the
    // paper, not a font/shaping problem).
    //
    // Root cause: the printer was still physically feeding/printing strip N when
    // strip N+1 arrived, so strip N+1 started printing before the paper had advanced
    // past strip N. Three changes tighten this further:
    //   1. Smaller strips (48px instead of 80px) — even less data per raster command,
    //      so each one finishes printing/feeding faster and pacing is finer-grained.
    //   2. A larger per-row pause (6ms/row instead of 3ms/row) and a higher minimum
    //      floor (100ms instead of 60ms) — gives slower mechanical feed more margin.
    //   3. A short settle delay right after ESC_INIT (before the first strip) and
    //      right before FEED_AND_CUT (after the last strip) — some printers need a
    //      moment to finish initializing / finish their last print job before the
    //      next command is safe to send.
    // If overlap still happens on your printer, raise MS_PER_STRIP_ROW further (e.g.
    // 6f -> 9f or 12f) and/or MIN_INTER_CHUNK_DELAY_MS (e.g. 100 -> 150) — those are
    // the two knobs to tune per-printer-model. Slower prints are always safer than
    // overlapping ones.
    // SPEED TUNING: fewer, slightly larger strips means fewer separate write+flush
    // round-trips (each one has its own Bluetooth/USB overhead), which is a real
    // speed win — while the *pacing per row* (MS_PER_STRIP_ROW) still guarantees each
    // strip has fully fed through the printer before the next one lands, so the
    // anti-overlap fix from before is preserved. Only raise MAX_STRIP_HEIGHT_PX
    // further if you also see garbled/overlapping print return — smaller strips are
    // always the safer fallback.
    //
    // FIX 3 (item table now matches the on-screen preview): the ruled grid table
    // (TableRow) was replaced for the item list with borderless Row3/ItemRow lines
    // (see below), which also has the side benefit of fewer draw operations per
    // item (no grid strokes), so this doesn't fight the anti-overlap pacing above.
    private const val MAX_STRIP_HEIGHT_PX = 64
    private const val MIN_INTER_CHUNK_DELAY_MS = 90L
    private const val MS_PER_STRIP_ROW = 5f
    private const val SETTLE_DELAY_MS = 60L

    /** How long to pause after sending a strip of [stripHeightPx] dots, before sending
     *  the next one — scaled to strip height with a safe minimum floor. See FIX 2 above. */
    private fun interChunkDelayFor(stripHeightPx: Int): Long =
        maxOf(MIN_INTER_CHUNK_DELAY_MS, (stripHeightPx * MS_PER_STRIP_ROW).toLong())

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

    /**
     * Opens one Bluetooth connection and writes ESC_INIT, then each chunk (flushed
     * individually with a pause scaled to that chunk's strip height — see
     * [interChunkDelayFor] — before the next one is sent), then FEED_AND_CUT — all over
     * the same socket. Used instead of [sendBluetoothBytes] for raster-image receipts
     * so long bills don't overrun the printer's buffer or overlap print (see the FIX
     * comments on [MAX_STRIP_HEIGHT_PX]).
     */
    @SuppressLint("MissingPermission")
    private fun sendBluetoothChunks(context: Context, macAddress: String, chunks: List<Pair<ByteArray, Int>>): Boolean {
        if (!hasBluetoothPermission(context)) return false
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        var socket: BluetoothSocket? = null
        return try {
            val device = adapter.getRemoteDevice(macAddress)
            adapter.cancelDiscovery()
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            val out: OutputStream = socket.outputStream

            out.write(ESC_INIT)
            out.flush()
            // Settle delay — see FIX 2 above. Let the printer finish initializing
            // before the first raster strip lands on it.
            Thread.sleep(SETTLE_DELAY_MS)

            for ((chunk, stripHeight) in chunks) {
                out.write(chunk)
                out.flush()
                Thread.sleep(interChunkDelayFor(stripHeight))
            }

            // Settle delay before feed/cut — give the last strip time to fully print.
            Thread.sleep(SETTLE_DELAY_MS)
            out.write(FEED_AND_CUT)
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

    /**
     * USB counterpart to [sendBluetoothChunks]: opens the device once, writes
     * ESC_INIT + each chunk (each chunk itself split at 4096-byte boundaries, since a
     * single bulkTransfer call has its own size ceiling, with a pause scaled to that
     * chunk's strip height between chunks) + FEED_AND_CUT, then closes.
     */
    private fun sendUsbChunks(context: Context, deviceName: String, chunks: List<Pair<ByteArray, Int>>): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = manager.deviceList.values.find { it.deviceName == deviceName } ?: return false
        if (!manager.hasPermission(device)) return false

        val (usbInterface, endpoint) = findPrinterInterfaceAndEndpoint(device) ?: return false
        var connection: UsbDeviceConnection? = null
        return try {
            connection = manager.openDevice(device) ?: return false
            connection.claimInterface(usbInterface, true)

            fun writeAll(bytes: ByteArray): Boolean {
                var offset = 0
                while (offset < bytes.size) {
                    val len = minOf(4096, bytes.size - offset)
                    val slice = if (offset == 0 && len == bytes.size) bytes else bytes.copyOfRange(offset, offset + len)
                    val sent = connection!!.bulkTransfer(endpoint, slice, slice.size, 5000)
                    if (sent < 0) return false
                    offset += len
                }
                return true
            }

            var ok = writeAll(ESC_INIT)
            if (ok) Thread.sleep(SETTLE_DELAY_MS)
            for ((chunk, stripHeight) in chunks) {
                if (!ok) break
                ok = writeAll(chunk)
                if (ok) Thread.sleep(interChunkDelayFor(stripHeight))
            }
            if (ok) {
                Thread.sleep(SETTLE_DELAY_MS)
                ok = writeAll(FEED_AND_CUT)
            }

            connection.releaseInterface(usbInterface)
            ok
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

    private fun sendChunks(context: Context, type: PrinterType, address: String, chunks: List<Pair<ByteArray, Int>>): Boolean {
        return when (type) {
            PrinterType.BLUETOOTH -> sendBluetoothChunks(context, address, chunks)
            PrinterType.USB -> sendUsbChunks(context, address, chunks)
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

    // Named system font families that ship Arabic-script glyphs on most
    // AOSP-derived builds (incl. many budget Android POS terminals), tried
    // in order before we give up on anything better than plain Roboto. See
    // resolveUrduTypeface() below.
    private val SYSTEM_ARABIC_FONT_FAMILIES = listOf("noto-naskh-arabic", "noto-sans-arabic")

    /**
     * Resolves the best available Urdu-capable typeface, in order:
     *  1. A font bundled by the app at [URDU_FONT_ASSET_PATH]
     *     (app/src/main/assets/fonts/NotoNastaliqUrdu-Regular.ttf — see the
     *     README.md placed next to that path). This is the only option that
     *     guarantees the same Nastaliq look on every device.
     *  2. A named system Arabic font family, if this device's ROM exposes
     *     one under a recognized name.
     *  3. Typeface.DEFAULT — Android's own script-fallback will still shape
     *     Urdu glyphs correctly when drawn via StaticLayout/Canvas as long as
     *     the device has *some* Arabic-capable font installed; only the
     *     calligraphic style is out of our control at that point.
     * Result is cached after the first resolution (per process) so this
     * never re-hits the filesystem/font-family lookup on every receipt.
     */
    private fun resolveUrduTypeface(context: Context): Typeface {
        cachedUrduTypeface?.let { return it }
        if (triedLoadingUrduFont) return Typeface.DEFAULT
        triedLoadingUrduFont = true

        try {
            val tf = Typeface.createFromAsset(context.assets, URDU_FONT_ASSET_PATH)
            cachedUrduTypeface = tf
            return tf
        } catch (e: Exception) {
            // Bundled font missing — fall through to system options below.
        }

        for (family in SYSTEM_ARABIC_FONT_FAMILIES) {
            try {
                val tf = Typeface.create(family, Typeface.NORMAL)
                if (tf != null && tf != Typeface.DEFAULT) {
                    cachedUrduTypeface = tf
                    return tf
                }
            } catch (e: Exception) {
                // Family not present on this ROM — try the next one.
            }
        }

        cachedUrduTypeface = Typeface.DEFAULT
        return Typeface.DEFAULT
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
     *  - [ReceiptLine.TableRow] draws a full ruled grid (vertical column
     *    dividers + a bottom border per row) for callers that still want one.
     *  - [ReceiptLine.Row3] / [ReceiptLine.ItemRow] draw the borderless,
     *    preview-matching item list layout (see their docs above).
     */
    private fun renderReceiptLines(lines: List<ReceiptLine>, fontSizePx: Float, typeface: Typeface): Bitmap {
        val margin = 6
        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = fontSizePx
            color = Color.BLACK
            this.typeface = typeface
            // FIX (print too light / faint): thin anti-aliased glyph edges were often
            // landing as mid-gray in the 1-bit conversion below, which a lot of thermal
            // print heads render as barely-there. FILL_AND_STROKE + a small stroke width
            // thickens every character uniformly (a "synthetic bold" effect) so more of
            // each glyph crosses the black/white threshold — same technique as bolding a
            // whole font, but applied globally without changing which lines are already
            // marked bold vs normal (those still look heavier relative to this baseline).
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = fontSizePx * 0.035f
        }
        // SPEED TUNING: slightly tighter spacing than before shrinks the overall
        // bitmap height (and so the total bytes sent to the printer) without making
        // the receipt cramped or hard to read.
        val lineSpacingExtra = (fontSizePx * 0.28f).toInt()
        val contentWidth = PRINTER_DOTS_WIDTH - margin * 2
        // Table/row cells use a slightly smaller font than the rest of the receipt so
        // 3-4 columns (Item/Qty/Amount, or Item/Qty/Rate/Amount) fit comfortably on a
        // 384-dot (58mm) paper width without excessive ellipsizing.
        val tableFontSize = fontSizePx * 0.78f
        // FIX (cramped print — text touching between columns/lines): both of these
        // were too tight (8 / 5), which combined with the qty-column overflow bug
        // made everything look crammed together with no visible gaps. Bumped up for
        // clearer separation; combined with the ellipsize fix above, columns can no
        // longer touch even in the worst case.
        val tableRowPaddingV = 12 // extra top/bottom padding inside each row
        val tableCellPaddingH = 8 // left/right padding inside each cell, before ellipsizing

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
                is ReceiptLine.TableRow -> {
                    // Height is computed using the bigger of the two possible cell font
                    // sizes (the Arabic item-name boost), so a row has enough vertical
                    // room whether or not it actually contains Arabic text this time.
                    paint.textSize = tableFontSize * ARABIC_ITEM_FONT_BOOST
                    val fm = paint.fontMetrics
                    paint.textSize = fontSizePx
                    var h = (fm.bottom - fm.top).toInt() + tableRowPaddingV * 2
                    if (line.topBorder) h += 2 // room for the extra top border stroke
                    blocks.add(Block(line, null, h))
                    totalHeight += h
                }
                is ReceiptLine.Row3 -> {
                    paint.textSize = tableFontSize
                    val fm = paint.fontMetrics
                    paint.textSize = fontSizePx
                    val h = (fm.bottom - fm.top).toInt() + tableRowPaddingV
                    blocks.add(Block(line, null, h))
                    totalHeight += h
                }
                is ReceiptLine.ItemRow -> {
                    // Two stacked lines: bold name/qty/amount row, then smaller "@ rate"
                    // row. Measured using the boosted Arabic size for line 1 so there's
                    // enough room whether or not the item name is Urdu this time.
                    paint.textSize = tableFontSize * ARABIC_ITEM_FONT_BOOST
                    val fmName = paint.fontMetrics
                    paint.textSize = tableFontSize * 0.8f
                    val fmRate = paint.fontMetrics
                    paint.textSize = fontSizePx
                    val h = (fmName.bottom - fmName.top).toInt() +
                        (fmRate.bottom - fmRate.top).toInt() +
                        (tableRowPaddingV * 0.35f).toInt() + // rateLineGap — must match the draw-time value below
                        tableRowPaddingV * 2
                    blocks.add(Block(line, null, h))
                    totalHeight += h
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
                    // FIX (alignment bug): this used to detect Urdu on either side and
                    // "flip" that side's anchor to the opposite margin — meant to look
                    // smart, but when the RIGHT side (the value) was Urdu, both the
                    // label and the value ended up anchored at the LEFT margin and drew
                    // on top of each other. The on-screen preview (`kv()` in
                    // BillPreviewActivity) never does this: the label is always on the
                    // left, the value is always on the right, no matter which script it's
                    // in — Canvas.drawText with Paint.Align.RIGHT/LEFT already shapes and
                    // positions Arabic/Urdu glyphs correctly at a fixed anchor, so no
                    // side-swapping is needed. Fixed sides here match that, and stop the
                    // overlap.
                    val fm = paint.fontMetrics
                    val baseline = y - fm.top

                    val oldBold = paint.isFakeBoldText
                    paint.isFakeBoldText = line.bold

                    paint.textAlign = Paint.Align.LEFT
                    canvas.drawText(line.left, margin.toFloat(), baseline, paint)

                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(line.right, (PRINTER_DOTS_WIDTH - margin).toFloat(), baseline, paint)

                    paint.isFakeBoldText = oldBold
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
                is ReceiptLine.TableRow -> {
                    val rowTop = y + if (line.topBorder) 2f else 0f
                    val rowBottom = y + block.height
                    val tableLeft = margin.toFloat()
                    val tableRight = (PRINTER_DOTS_WIDTH - margin).toFloat()
                    val tableWidth = tableRight - tableLeft

                    // ---- column x boundaries, proportioned by weight ----
                    val totalWeight = line.weights.sum().coerceAtLeast(0.01f)
                    val colX = FloatArray(line.weights.size + 1)
                    colX[0] = tableLeft
                    for (i in line.weights.indices) {
                        colX[i + 1] = colX[i] + (line.weights[i] / totalWeight) * tableWidth
                    }

                    // ---- grid lines: outer/inner verticals + bottom border (+ top border if header) ----
                    val oldStroke = paint.strokeWidth
                    paint.strokeWidth = 2f
                    paint.style = Paint.Style.STROKE
                    if (line.topBorder) canvas.drawLine(tableLeft, rowTop, tableRight, rowTop, paint)
                    canvas.drawLine(tableLeft, rowBottom, tableRight, rowBottom, paint)
                    for (x in colX) canvas.drawLine(x, rowTop, x, rowBottom, paint)
                    paint.strokeWidth = oldStroke
                    // FIX: restore to the global FILL_AND_STROKE synthetic-bold style
                    // (set once in renderReceiptLines) — left as pure STROKE here would
                    // have made the cell text below render as hollow/outline-only glyphs.
                    paint.style = Paint.Style.FILL_AND_STROKE

                    // ---- cell text ----
                    paint.textSize = tableFontSize
                    val oldBold = paint.isFakeBoldText
                    paint.isFakeBoldText = line.bold
                    val fm = paint.fontMetrics
                    val baseline = rowTop + tableRowPaddingV - fm.top

                    for (i in line.cells.indices) {
                        val cellLeft = colX[i] + tableCellPaddingH
                        val cellRight = colX[i + 1] - tableCellPaddingH
                        val cellWidth = (cellRight - cellLeft).coerceAtLeast(1f)
                        val rawText = line.cells[i]
                        val isItemColumn = i == 0
                        val isUrdu = containsArabicScript(rawText)

                        // Urdu item names get a bigger, slightly bolder rendering than
                        // the numeric columns — see ARABIC_ITEM_FONT_BOOST above. This
                        // must be set BEFORE ellipsize so the "does it fit" measurement
                        // matches what's actually drawn.
                        val useArabicBoost = isItemColumn && isUrdu
                        if (useArabicBoost) {
                            paint.textSize = tableFontSize * ARABIC_ITEM_FONT_BOOST
                            paint.isFakeBoldText = true
                        }

                        val fitText = TextUtils.ellipsize(rawText, paint, cellWidth, TextUtils.TruncateAt.END).toString()

                        when {
                            isItemColumn && isUrdu -> {
                                paint.textAlign = Paint.Align.RIGHT
                                canvas.drawText(fitText, cellRight, baseline, paint)
                            }
                            isItemColumn -> {
                                paint.textAlign = Paint.Align.LEFT
                                canvas.drawText(fitText, cellLeft, baseline, paint)
                            }
                            else -> {
                                paint.textAlign = Paint.Align.CENTER
                                canvas.drawText(fitText, (cellLeft + cellRight) / 2f, baseline, paint)
                            }
                        }

                        if (useArabicBoost) {
                            paint.textSize = tableFontSize
                            paint.isFakeBoldText = line.bold
                        }
                    }
                    paint.isFakeBoldText = oldBold
                    paint.textSize = fontSizePx

                    y += block.height
                }
                is ReceiptLine.Row3 -> {
                    // Borderless header-style row: col1 left/RTL-aware, col2 centered,
                    // col3 right-aligned — matches the on-screen "ITEM / QTY / AMOUNT"
                    // header, no grid lines drawn.
                    paint.textSize = tableFontSize
                    val oldBold = paint.isFakeBoldText
                    paint.isFakeBoldText = line.bold
                    val fm = paint.fontMetrics
                    val baseline = y + tableRowPaddingV / 2 - fm.top

                    val tableLeft = margin.toFloat()
                    val tableRight = (PRINTER_DOTS_WIDTH - margin).toFloat()
                    val tableWidth = tableRight - tableLeft
                    val totalWeight = line.weights.sum().coerceAtLeast(0.01f)
                    val colX = FloatArray(4)
                    colX[0] = tableLeft
                    for (i in 0..2) colX[i + 1] = colX[i] + (line.weights[i] / totalWeight) * tableWidth

                    val col1Rtl = containsArabicScript(line.col1)
                    paint.textAlign = if (col1Rtl) Paint.Align.RIGHT else Paint.Align.LEFT
                    val col1X = if (col1Rtl) colX[1] - tableCellPaddingH else colX[0] + tableCellPaddingH
                    canvas.drawText(line.col1, col1X, baseline, paint)

                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(line.col2, (colX[1] + colX[2]) / 2f, baseline, paint)

                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(line.col3, colX[3] - tableCellPaddingH, baseline, paint)

                    paint.isFakeBoldText = oldBold
                    paint.textSize = fontSizePx
                    y += block.height
                }
                is ReceiptLine.ItemRow -> {
                    // Matches the on-screen preview card's item rows exactly:
                    //   line 1: <name>                <qty>            <amount>   (name+amount bold)
                    //   line 2: @ <rate>                                            (smaller, plain)
                    // No borders, no grid.
                    val tableLeft = margin.toFloat()
                    val tableRight = (PRINTER_DOTS_WIDTH - margin).toFloat()
                    val tableWidth = tableRight - tableLeft
                    val totalWeight = line.weights.sum().coerceAtLeast(0.01f)
                    val colX = FloatArray(4)
                    colX[0] = tableLeft
                    for (i in 0..2) colX[i + 1] = colX[i] + (line.weights[i] / totalWeight) * tableWidth

                    val nameIsUrdu = containsArabicScript(line.name)
                    val nameSize = tableFontSize * (if (nameIsUrdu) ARABIC_ITEM_FONT_BOOST else 1f)

                    // ---- line 1: name (bold, ellipsized) + qty (center) + amount (right, bold) ----
                    paint.textSize = nameSize
                    paint.isFakeBoldText = true
                    var fm = paint.fontMetrics
                    val line1Baseline = y + tableRowPaddingV - fm.top
                    val line1Height = fm.bottom - fm.top

                    val nameColWidth = (colX[1] - colX[0] - tableCellPaddingH * 2).coerceAtLeast(1f)
                    val fitName = TextUtils.ellipsize(line.name, paint, nameColWidth, TextUtils.TruncateAt.END).toString()
                    paint.textAlign = if (nameIsUrdu) Paint.Align.RIGHT else Paint.Align.LEFT
                    val nameX = if (nameIsUrdu) colX[1] - tableCellPaddingH else colX[0] + tableCellPaddingH
                    canvas.drawText(fitName, nameX, line1Baseline, paint)

                    // FIX (columns overlapping on paper — "1 Bnd2900.00" with no gap):
                    // qty and amount were drawn at full size with no width check, so a
                    // qty like "10 Bnd" or "15 kg" — wider than the (narrow) qty column
                    // — spilled out on both sides of its center point and ran straight
                    // into the amount text next to it. Ellipsizing both to their actual
                    // column width (same technique already used for the item name)
                    // guarantees they can never touch, no matter how long the text is.
                    paint.textSize = tableFontSize
                    val qtyColWidth = (colX[2] - colX[1] - tableCellPaddingH * 2).coerceAtLeast(1f)
                    val fitQty = TextUtils.ellipsize(line.qty, paint, qtyColWidth, TextUtils.TruncateAt.END).toString()
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(fitQty, (colX[1] + colX[2]) / 2f, line1Baseline, paint)

                    val amountColWidth = (colX[3] - colX[2] - tableCellPaddingH * 2).coerceAtLeast(1f)
                    val fitAmount = TextUtils.ellipsize(line.amount, paint, amountColWidth, TextUtils.TruncateAt.END).toString()
                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(fitAmount, colX[3] - tableCellPaddingH, line1Baseline, paint)

                    // ---- line 2: "@ rate" — smaller, plain, directly under the name ----
                    // Small explicit gap (rateLineGap) added below line 1 so the two
                    // lines don't sit flush against each other on paper.
                    val rateLineGap = tableRowPaddingV * 0.35f
                    paint.textSize = tableFontSize * 0.8f
                    paint.isFakeBoldText = false
                    fm = paint.fontMetrics
                    val line2Baseline = y + tableRowPaddingV + line1Height + rateLineGap - fm.top
                    paint.textAlign = if (nameIsUrdu) Paint.Align.RIGHT else Paint.Align.LEFT
                    val rateX = if (nameIsUrdu) colX[1] - tableCellPaddingH else colX[0] + tableCellPaddingH
                    canvas.drawText("@ ${line.rate}", rateX, line2Baseline, paint)

                    paint.textSize = fontSizePx
                    paint.textAlign = Paint.Align.LEFT
                    y += block.height
                }
            }
        }
        return bitmap
    }

    /**
     * Converts a Bitmap into a list of (ESC/POS raster-image command, stripHeight)
     * pairs — 1-bit monochrome via simple luminance threshold, split into horizontal
     * strips of at most [MAX_STRIP_HEIGHT_PX] each so a tall multi-item receipt never
     * becomes one oversized raster command, and so each strip's actual height is known
     * to the sender for computing a proportional pause via [interChunkDelayFor] (see
     * the FIX comments on [MAX_STRIP_HEIGHT_PX] for why the pause needs to scale).
     */
    private fun bitmapToEscPosRasterChunks(bitmap: Bitmap): List<Pair<ByteArray, Int>> {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8

        // Reading all pixels once up front is much faster than repeated getPixel()
        // calls per strip, especially for tall receipts.
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val chunks = mutableListOf<Pair<ByteArray, Int>>()
        var y = 0
        while (y < height) {
            val stripHeight = minOf(MAX_STRIP_HEIGHT_PX, height - y)
            val header = byteArrayOf(
                0x1D, 0x76, 0x30, 0x00,
                (bytesPerRow and 0xFF).toByte(),
                ((bytesPerRow shr 8) and 0xFF).toByte(),
                (stripHeight and 0xFF).toByte(),
                ((stripHeight shr 8) and 0xFF).toByte()
            )
            val imageData = ByteArray(bytesPerRow * stripHeight)
            for (row in 0 until stripHeight) {
                val srcRowStart = (y + row) * width
                for (x in 0 until width) {
                    val pixel = pixels[srcRowStart + x]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val bch = pixel and 0xFF
                    val luminance = r * 0.3 + g * 0.59 + bch * 0.11
                    // FIX (print too light): was <128 — only fairly dark pixels became
                    // black dots, so lighter gray (anti-aliased edges, thin strokes)
                    // printed as nothing at all. Raised to <195 so those pixels also
                    // print solid, which — combined with the synthetic-bold stroke
                    // above — makes the whole receipt noticeably darker and easier to
                    // read on a faint-printing thermal head.
                    if (luminance < 195) {
                        val byteIndex = row * bytesPerRow + (x / 8)
                        val bitIndex = 7 - (x % 8)
                        imageData[byteIndex] = (imageData[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                    }
                }
            }
            chunks.add((header + imageData) to stripHeight)
            y += stripHeight
        }
        return chunks
    }

    /**
     * Preferred entry point: prints a receipt built from structured [ReceiptLine]s
     * with correct per-line direction, pixel-accurate column alignment, and chunked
     * raster transmission (with a per-strip pause scaled to strip height) so
     * long/multi-item receipts print reliably without overlapping.
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
        val chunks = bitmapToEscPosRasterChunks(bitmap)
        return sendChunks(context, type, address, chunks)
    }

    /**
     * Prints Urdu (or mixed Urdu/English) plain text by rendering it as an image.
     * Kept for any existing callers that build a plain string. Each line of the
     * input is now given its own RTL/LTR direction (fixing the old whole-receipt
     * RTL bug); for real column alignment (labels/values, item qty/rate/amount),
     * prefer [printReceiptLines] with [ReceiptLine.TwoCol]/[ReceiptLine.Row3]/
     * [ReceiptLine.ItemRow] instead of padding with spaces, since a proportional
     * font can't be aligned that way.
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
        val chunks = bitmapToEscPosRasterChunks(bitmap)
        return sendChunks(context, type, address, chunks)
    }
}
