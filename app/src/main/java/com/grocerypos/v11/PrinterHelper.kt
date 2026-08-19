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
        val adapter = BluetoothAdapter.getDefaultAdapter()?: return emptyList()
        return adapter.bondedDevices?.toList()?: emptyList()
    }

    fun usbDevices(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager?: return emptyList()
        return manager.deviceList.values.toList()
    }

    fun hasUsbPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager?: return false
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
        val adapter = BluetoothAdapter.getDefaultAdapter()?: return false
        var socket: BluetoothSocket? = null
        return try {
            val device = adapter.getRemoteDevice(macAddress)
            adapter.cancelDiscovery()
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            val out = socket.outputStream
            out.write(data)
            out.flush()
            true
        } catch (e: Exception) { e.printStackTrace(); false }
        finally { try { socket?.close() } catch (_: Exception) {} }
    }

    private fun printUsbBytes(context: Context, deviceName: String, data: ByteArray): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager?: return false
        val device = manager.deviceList.values.find { it.deviceName == deviceName }?: return false
        if (!manager.hasPermission(device)) return false
        val (usbInterface, endpoint) = findPrinterInterfaceAndEndpoint(device)?: return false
        var connection: UsbDeviceConnection? = null
        return try {
            connection = manager.openDevice(device)?: return false
            connection.claimInterface(usbInterface, true)
            val sent = connection.bulkTransfer(endpoint, data, data.size, 5000)
            connection.releaseInterface(usbInterface)
            sent >= 0
        } catch (e: Exception) { e.printStackTrace(); false }
        finally { try { connection?.close() } catch (_: Exception) {} }
    }

    fun printText(context: Context, type: PrinterType, address: String, text: String): Boolean {
        val data = ESC_INIT + text.toByteArray(Charsets.UTF_8) + FEED_AND_CUT
        return printBytes(context, type, address, data)
    }

    fun testPrint(context: Context, type: PrinterType, address: String, shopName: String = "IBTISAAM Kiryana Store"): Boolean {
        val data = ESC_INIT + ESC_ALIGN_CENTER + ESC_BOLD_ON + "$shopName\nTEST PRINT OK\n".toByteArray() + ESC_BOLD_OFF + FEED_AND_CUT
        return printBytes(context, type, address, data)
    }
}
