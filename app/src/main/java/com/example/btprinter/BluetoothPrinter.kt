package com.example.btprinter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Singleton koneksi Bluetooth ke printer thermal.
 *
 * v2.1: auto-disconnect saat write gagal supaya state tidak korup untuk operasi berikutnya.
 */
object BluetoothPrinter {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val ioLock = Mutex()

    val isConnected: Boolean
        get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        ioLock.withLock {
            try {
                disconnectInternal()
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                outputStream = s.outputStream
                Result.success(Unit)
            } catch (e: Throwable) {
                disconnectInternal()
                Result.failure(e)
            }
        }
    }

    /**
     * Tulis byte mentah ke printer. v2.1: auto-disconnect kalau write gagal,
     * supaya operasi berikutnya tidak pakai socket yang sudah broken.
     */
    suspend fun writeRaw(data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        ioLock.withLock {
            try {
                val os = outputStream ?: return@withLock Result.failure(IOException("Belum terhubung"))
                os.write(data)
                os.flush()
                Result.success(Unit)
            } catch (e: Throwable) {
                // Reset state supaya retry/job berikutnya fresh connect
                disconnectInternal()
                Result.failure(e)
            }
        }
    }

    /**
     * Cetak teks bebas. Init printer, kirim teks pakai ISO-8859-1 encoding,
     * lalu feed 3 baris supaya struk keluar.
     */
    suspend fun printText(text: String): Result<Unit> {
        val bytes = EscPos.INIT +
                text.toByteArray(Charsets.ISO_8859_1) +
                EscPos.LF +
                EscPos.FEED_3
        return writeRaw(bytes)
    }

    fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        try { outputStream?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        outputStream = null
        socket = null
    }
}
