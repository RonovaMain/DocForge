package dev.ronova.docforge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.ronova.docforge.core.DocException
import java.io.Closeable

/** Поиск USB-принтеров (класс 7), запрос разрешения и отправка данных через USB Host. Нужен OTG-кабель. */
class UsbPrinters(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Подключённые устройства, у которых есть интерфейс принтера с каналом на запись. */
    fun list(): List<UsbDevice> =
        manager.deviceList.values.filter { findInterface(it) != null }.sortedBy { it.deviceName }

    fun title(device: UsbDevice): String {
        val name = try {
            device.productName
        } catch (e: Exception) {
            null
        }
        val maker = try {
            device.manufacturerName
        } catch (e: Exception) {
            null
        }
        val label = listOfNotNull(maker, name).joinToString(" ").ifBlank { "USB-принтер" }
        return "$label (%04X:%04X)".format(device.vendorId, device.productId)
    }

    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }
        val action = "${context.packageName}.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    context.unregisterReceiver(this)
                } catch (e: IllegalArgumentException) {
                    // уже снят
                }
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(action).setPackage(context.packageName)
        manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    fun open(device: UsbDevice): Session {
        val intf = findInterface(device) ?: throw DocException("У устройства нет интерфейса принтера")
        val endpoint = findOut(intf) ?: throw DocException("У принтера нет канала для записи")
        val connection = manager.openDevice(device) ?: throw DocException("Не удалось открыть USB-устройство")
        if (!connection.claimInterface(intf, true)) {
            connection.close()
            throw DocException("USB-принтер занят другой программой")
        }
        return Session(connection, intf, endpoint)
    }

    private fun findInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER && findOut(intf) != null) return intf
        }
        return null
    }

    private fun findOut(intf: UsbInterface): UsbEndpoint? {
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_OUT) return e
        }
        return null
    }

    class Session(
        private val connection: UsbDeviceConnection,
        private val intf: UsbInterface,
        private val endpoint: UsbEndpoint,
    ) : Closeable {
        fun write(data: ByteArray, length: Int) {
            var offset = 0
            while (offset < length) {
                val chunk = minOf(CHUNK, length - offset)
                val sent = connection.bulkTransfer(endpoint, data, offset, chunk, TIMEOUT_MS)
                if (sent < 0) throw DocException("Принтер не отвечает по USB")
                offset += sent
            }
        }

        override fun close() {
            connection.releaseInterface(intf)
            connection.close()
        }

        private companion object {
            const val CHUNK = 16 * 1024
            const val TIMEOUT_MS = 15_000
        }
    }
}
