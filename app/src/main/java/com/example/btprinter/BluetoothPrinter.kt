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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.4: Tambah property `connectedMac` supaya caller bisa cek apakah
 * koneksi current sudah ke device yang diinginkan, tanpa perlu disconnect+
 * reconnect. Ini critical untuk Print Service yang harus reuse koneksi
 * yang sudah dibuat MainActivity.
 *
 * v2.3: Bluetooth connect/write pakai WATCHDOG THREAD untuk paksa timeout.
 */
object BluetoothPrinter {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val ioLock = Mutex()

    val isConnected: Boolean
        get() = socket?.isConnected == true

    /**
     * MAC address printer yang sedang terhubung, atau null kalau belum connect.
     * Dipakai PrintService untuk cek apakah perlu reconnect atau reuse koneksi.
     */
    val connectedMac: String?
        @SuppressLint("MissingPermission")
        get() = try {
            socket?.takeIf { it.isConnected }?.remoteDevice?.address
        } catch (_: Throwable) {
            null
        }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, timeoutMs: Long = 15_000L): Result<Unit> =
        withContext(Dispatchers.IO) {
            ioLock.withLock {
                disconnectInternal()
                connectWithWatchdog(device, timeoutMs)
            }
        }

    private fun connectWithWatchdog(device: BluetoothDevice, timeoutMs: Long): Result<Unit> {
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (t: Throwable) {
            return Result.failure(RuntimeException("Buat socket gagal: ${t.message}", t))
        }

        val watchdogFired = AtomicBoolean(false)
        val operationDone = AtomicBoolean(false)

        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                // v2.4: pakai compareAndSet supaya atomic dengan operationDone
                if (operationDone.compareAndSet(false, true)) {
                    watchdogFired.set(true)
                    try { s.close() } catch (_: Throwable) {}
                }
            } catch (_: InterruptedException) {
                // watchdog dibatalkan, OK
            }
        }
        watchdog.isDaemon = true
        watchdog.name = "BTPrinter-ConnectWatchdog"
        watchdog.start()

        return try {
            s.connect()  // blocking; throws IOException kalau watchdog close socket
            // v2.4: cek apakah watchdog sudah claim operationDone duluan
            if (!operationDone.compareAndSet(false, true)) {
                // Watchdog menang race - close socket yang baru terbentuk
                try { s.close() } catch (_: Throwable) {}
                Result.failure(IOException(
                    "Connect timeout ${timeoutMs}ms — printer mati / sibuk / di luar jangkauan"
                ))
            } else {
                watchdog.interrupt()
                socket = s
                outputStream = s.outputStream
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            operationDone.set(true)
            watchdog.interrupt()
            try { s.close() } catch (_: Throwable) {}
            if (watchdogFired.get()) {
                Result.failure(IOException(
                    "Connect timeout ${timeoutMs}ms — printer mati / sibuk / di luar jangkauan"
                ))
            } else {
                Result.failure(t)
            }
        }
    }

    suspend fun writeRaw(data: ByteArray, timeoutMs: Long = 10_000L): Result<Unit> =
        withContext(Dispatchers.IO) {
            ioLock.withLock {
                writeWithWatchdog(data, timeoutMs)
            }
        }

    private fun writeWithWatchdog(data: ByteArray, timeoutMs: Long): Result<Unit> {
        val os = outputStream ?: return Result.failure(IOException("Belum terhubung"))
        val sock = socket
        val watchdogFired = AtomicBoolean(false)
        val operationDone = AtomicBoolean(false)

        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                if (operationDone.compareAndSet(false, true)) {
                    watchdogFired.set(true)
                    try { sock?.close() } catch (_: Throwable) {}
                }
            } catch (_: InterruptedException) { /* OK */ }
        }
        watchdog.isDaemon = true
        watchdog.name = "BTPrinter-WriteWatchdog"
        watchdog.start()

        return try {
            os.write(data)
            os.flush()
            if (!operationDone.compareAndSet(false, true)) {
                // watchdog menang race
                disconnectInternal()
                Result.failure(IOException(
                    "Write timeout ${timeoutMs}ms (${data.size} bytes) — printer hang?"
                ))
            } else {
                watchdog.interrupt()
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            operationDone.set(true)
            watchdog.interrupt()
            disconnectInternal()
            if (watchdogFired.get()) {
                Result.failure(IOException(
                    "Write timeout ${timeoutMs}ms (${data.size} bytes) — printer hang?"
                ))
            } else {
                Result.failure(t)
            }
        }
    }

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