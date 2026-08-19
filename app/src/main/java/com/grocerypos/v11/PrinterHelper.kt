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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

object PrinterHelper {

    enum class PrinterType { BLUETOOTH, USB }

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val ACTION_USB_PERMISSION = "com.grocerypos.v11.USB_PERMISSION"

    // ESC/POS Commands
    val ESC_INIT = byteArrayOf(0x1B, 0x40)
    val ESC_ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
    val ESC_ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    val ESC_ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)
    val ESC_BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
    val ESC_BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    val FEED_AND_CUT = byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x01)

    // Max rows sent per GS v 0 raster band. Keeps command chunks small so
    // cheap/clone printers with small receive buffers don't choke or drop data.
    private const val MAX_BAND_HEIGHT = 256

    // Pixels darker than this (0-255 luminance) are printed as black dots.
    private const val BLACK_THRESHOLD = 170

    fun hasBluetoothPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun requestBluetoothPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN), requestCode)
        }
    }

    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> {
        if (!hasBluetoothPermission(context)) return emptyList()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }

    fun usbDevices(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return manager.deviceList.values.toList()
    }

    fun hasUsbPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return manager.hasPermission(device)
    }

    fun requestUsbPermission(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) { onResult(false); return }
        if (manager.hasPermission(device)) { onResult(true); return }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
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
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    return usbInterface to endpoint
                }
            }
        }
        return null
    }

    fun printBytes(context: Context, type: PrinterType, address: String, data: ByteArray): Boolean {
        return when (type) {
            PrinterType.BLUETOOTH -> printBluetoothBytes(context, address, data)
            PrinterType.USB -> printUsbBytes(context, address, data)
        }
    }

    @SuppressLint("MissingPermission")
    private fun printBluetoothBytes(context: Context, macAddress: String, data: ByteArray): Boolean {
        if (!hasBluetoothPermission(context)) return false
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        var socket: BluetoothSocket? = null
        return try {
            val device = adapter.getRemoteDevice(macAddress)
            adapter.cancelDiscovery()
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            val out = socket.outputStream
            // Send in chunks - large raster payloads can overrun some Bluetooth
            // SPP buffers if written in a single huge write().
            val chunkSize = 1024
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + chunkSize, data.size)
                out.write(data, offset, end - offset)
                out.flush()
                offset = end
            }
            true
        } catch (e: Exception) { e.printStackTrace(); false }
        finally { try { socket?.close() } catch (_: Exception) {} }
    }

    private fun printUsbBytes(context: Context, deviceName: String, data: ByteArray): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = manager.deviceList.values.find { it.deviceName == deviceName } ?: return false
        if (!manager.hasPermission(device)) return false
        val (usbInterface, endpoint) = findPrinterInterfaceAndEndpoint(device) ?: return false
        var connection: UsbDeviceConnection? = null
        return try {
            connection = manager.openDevice(device) ?: return false
            connection.claimInterface(usbInterface, true)
            // Bulk transfer in chunks to avoid failures on large image payloads.
            val chunkSize = 4096
            var offset = 0
            var allOk = true
            while (offset < data.size) {
                val end = minOf(offset + chunkSize, data.size)
                val chunk = data.copyOfRange(offset, end)
                val sent = connection.bulkTransfer(endpoint, chunk, chunk.size, 5000)
                if (sent < 0) { allOk = false; break }
                offset = end
            }
            connection.releaseInterface(usbInterface)
            allOk
        } catch (e: Exception) { e.printStackTrace(); false }
        finally { try { connection?.close() } catch (_: Exception) {} }
    }

    /**
     * Prints a Bitmap as an ESC/POS raster image (GS v 0).
     *
     * This is the reliable way to print receipts that may contain Urdu or any
     * other non-ASCII text: since we send pixel data instead of text bytes,
     * there is no dependency on the printer's built-in codepage/font, so
     * nothing renders as boxes or "????".
     */
    fun printBitmap(context: Context, type: PrinterType, address: String, bitmap: Bitmap): Boolean {
        val data = ESC_INIT + ESC_ALIGN_CENTER + bitmapToEscPosRaster(bitmap) + FEED_AND_CUT
        return printBytes(context, type, address, data)
    }

    private fun bitmapToEscPosRaster(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = (width + 7) / 8

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val out = mutableListOf<Byte>()
        var y = 0
        while (y < height) {
            val bandHeight = minOf(MAX_BAND_HEIGHT, height - y)

            // GS v 0 m xL xH yL yH [data]
            out.add(0x1D); out.add(0x76); out.add(0x30); out.add(0x00)
            out.add((widthBytes and 0xFF).toByte())
            out.add(((widthBytes shr 8) and 0xFF).toByte())
            out.add((bandHeight and 0xFF).toByte())
            out.add(((bandHeight shr 8) and 0xFF).toByte())

            for (row in 0 until bandHeight) {
                val py = y + row
                var bitCount = 0
                var currentByte = 0
                for (col in 0 until width) {
                    val pixel = pixels[py * width + col]
                    val a = (pixel ushr 24) and 0xFF
                    val r = (pixel ushr 16) and 0xFF
                    val g = (pixel ushr 8) and 0xFF
                    val b = pixel and 0xFF
                    // Transparent pixels count as white/background.
                    val luminance = if (a < 40) 255 else ((r * 299 + g * 587 + b * 114) / 1000)
                    val isBlack = luminance < BLACK_THRESHOLD

                    currentByte = (currentByte shl 1) or (if (isBlack) 1 else 0)
                    bitCount++
                    if (bitCount == 8) {
                        out.add(currentByte.toByte())
                        currentByte = 0
                        bitCount = 0
                    }
                }
                if (bitCount > 0) {
                    currentByte = currentByte shl (8 - bitCount)
                    out.add(currentByte.toByte())
                }
            }
            y += bandHeight
        }
        return out.toByteArray()
    }

    /**
     * Legacy plain-text print. Kept for any callers that only need to send
     * pure ASCII (e.g. debug commands). Do NOT use this for receipts that
     * may contain Urdu/Unicode text - use printBitmap instead, or garbled
     * output will result since most thermal printers don't support UTF-8.
     */
    fun printText(context: Context, type: PrinterType, address: String, text: String): Boolean {
        val data = ESC_INIT + text.toByteArray(Charsets.UTF_8) + FEED_AND_CUT
        return printBytes(context, type, address, data)
    }

    fun testPrint(context: Context, type: PrinterType, address: String, shopName: String = "IBTISAAM Kiryana Store"): Boolean {
        val width = 384
        val bitmap = Bitmap.createBitmap(width, 170, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = width * 0.075f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = width * 0.05f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(shopName, width / 2f, 55f, titlePaint)
        canvas.drawText("TEST PRINT OK", width / 2f, 105f, subPaint)

        return printBitmap(context, type, address, bitmap)
    }
}
