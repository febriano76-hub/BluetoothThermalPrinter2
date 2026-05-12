package com.example.btprinter.service

import android.bluetooth.BluetoothManager
import android.content.Context
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import com.example.btprinter.BluetoothPrinter
import com.example.btprinter.PrinterPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * v2.3 improvements:
 *  - Pakai BluetoothPrinter.connect/writeRaw dengan built-in watchdog timeout
 *  - withTimeout dihilangkan dari sini karena tidak ngefek untuk blocking native call
 *  - Watchdog di level BluetoothPrinter yang benar-benar memaksa abort
 */
class ThermalPrintService : PrintService() {

    companion object {
        private const val TAG = "ThermalPrintService"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PrinterPrefs

    override fun onCreate() {
        super.onCreate()
        prefs = PrinterPrefs(this)
        Log.d(TAG, "Service created")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        try { scope.cancel() } catch (_: Throwable) {}
        try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return ThermalPrinterDiscoverySession(this, prefs)
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        Log.d(TAG, "Cancel: ${printJob.info.label}")
        try { printJob.cancel() } catch (_: Throwable) {}
        // Tutup koneksi supaya operasi yang sedang berjalan abort
        try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        Log.d(TAG, "Job queued: ${printJob.info.label}, " +
                "${printJob.document.info.pageCount} pages")
        scope.launch { runJob(printJob) }
    }

    private suspend fun runJob(printJob: PrintJob) {
        // Mark started
        try {
            if (!printJob.isQueued) {
                Log.w(TAG, "Job not QUEUED: ${printJob.info.state}")
                return
            }
            printJob.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal start", t)
            safeFail(printJob, "Tidak bisa start: ${t.message}")
            return
        }

        // Lakukan kerja
        val result: Result<Unit> = try {
            doPrintJob(printJob)
        } catch (t: Throwable) {
            Result.failure(t)
        }

        // Update state
        if (result.isSuccess) {
            Log.d(TAG, "Job sukses")
            try { printJob.complete() } catch (t: Throwable) {
                Log.e(TAG, "Mark complete error", t)
            }
        } else {
            val err = result.exceptionOrNull()
            val msg = "${err?.javaClass?.simpleName ?: "Error"}: ${err?.message ?: "?"}"
            Log.e(TAG, "Job gagal: $msg", err)
            safeFail(printJob, msg)
        }

        try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
    }

    private suspend fun doPrintJob(printJob: PrintJob): Result<Unit> {
        val mac = prefs.lastPrinterMac
        if (mac.isNullOrBlank()) {
            return Result.failure(RuntimeException(
                "Belum pilih printer. Buka app BT Printer → Hubungkan dulu."
            ))
        }

        val pfd = try {
            printJob.document.data
        } catch (t: Throwable) {
            return Result.failure(RuntimeException("Baca dokumen: ${t.message}", t))
        } ?: return Result.failure(RuntimeException("Dokumen kosong"))

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return Result.failure(RuntimeException("Bluetooth service N/A"))
        val adapter = btManager.adapter
            ?: return Result.failure(RuntimeException("Bluetooth adapter N/A"))
        if (!adapter.isEnabled) {
            return Result.failure(RuntimeException("Bluetooth tidak aktif"))
        }

        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (t: Throwable) {
            return Result.failure(RuntimeException("MAC tidak valid: ${t.message}", t))
        }

        // Fresh connection setiap job
        try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}

        // v2.3: BluetoothPrinter.connect() sekarang punya built-in watchdog
        Log.d(TAG, "Connecting (watchdog ${CONNECT_TIMEOUT_MS}ms)...")
        val connectResult = BluetoothPrinter.connect(device, CONNECT_TIMEOUT_MS)
        if (connectResult.isFailure) {
            val err = connectResult.exceptionOrNull()
            return Result.failure(RuntimeException(
                "Connect gagal: ${err?.message}", err
            ))
        }

        Log.d(TAG, "Connected. Processing PDF (target=${prefs.targetWidthPx}px)...")
        val processor = PrintJobProcessor(prefs.targetWidthPx)
        return try {
            processor.process(pfd.fileDescriptor)
        } catch (t: Throwable) {
            Result.failure(RuntimeException(
                "Proses PDF: ${t.javaClass.simpleName}: ${t.message}", t
            ))
        }
    }

    private fun safeFail(printJob: PrintJob, message: String) {
        try {
            printJob.fail(message)
        } catch (t: Throwable) {
            Log.e(TAG, "Mark fail error", t)
        }
    }
}
