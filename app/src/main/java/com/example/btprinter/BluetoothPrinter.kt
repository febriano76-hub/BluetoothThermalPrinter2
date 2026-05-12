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
 * v2.3: Bluetooth connect/write pakai WATCHDOG THREAD untuk paksa timeout.
 *
 * Latar belakang: BluetoothSocket.connect() dan OutputStream.write() adalah
 * blocking native call. Kotlin withTimeout() hanya bisa cancel coroutine
 * kooperatif — tidak bisa interrupt JVM thread yang stuck di native code.
 * Akibatnya kalau Bluetooth hang, withTimeout tidak ngefek → job stuck forever.
 *
 * Solusi: thread terpisah (watchdog) yang sleep selama timeoutMs, lalu
 * force-close socket dari luar. Ini menyebabkan blocking native call throw
 * IOException, dan flow bisa lanjut.
 */
object BluetoothPrinter {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val ioLock = Mutex()

    val isConnected: Boolean
        get() = socket?.isConnected == true

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
                if (!operationDone.get()) {
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
            operationDone.set(true)
            watchdog.interrupt()
            socket = s
            outputStream = s.outputStream
            Result.success(Unit)
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

    /**
     * v2.3: write juga pakai watchdog untuk handle kasus printer terima
     * sebagian data lalu hang (mis. buffer penuh, printer reset).
     */
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
                if (!operationDone.get()) {
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
            operationDone.set(true)
            watchdog.interrupt()
            Result.success(Unit)
        } catch (t: Throwable) {
            operationDone.set(true)
            watchdog.interrupt()
            disconnectInternal()  // socket korup → reset state
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
