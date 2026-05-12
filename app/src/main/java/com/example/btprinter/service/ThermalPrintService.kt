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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Print Service untuk Android print framework.
 *
 * v2.2 improvements:
 *  - Overall timeout 2 menit untuk job — kalau hang, otomatis fail dengan pesan
 *  - Timeout 15 detik untuk Bluetooth connect
 *  - Restructured: handleJob return Result, caller handle state transitions
 *  - Guarantee: setiap job pasti dapat complete() atau fail() (no leak)
 */
class ThermalPrintService : PrintService() {

    companion object {
        private const val TAG = "ThermalPrintService"
        private const val OVERALL_TIMEOUT_MS = 120_000L  // 2 menit
        private const val CONNECT_TIMEOUT_MS = 15_000L   // 15 detik
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
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        Log.d(TAG, "Job queued: ${printJob.info.label}, " +
                "${printJob.document.info.pageCount} pages")

        scope.launch {
            runJob(printJob)
        }
    }

    /**
     * Top-level job runner. JAMINKAN setiap job pasti dapat complete() atau fail().
     * Pakai overall timeout supaya tidak ada hang yang stuck di "Memproses".
     */
    private suspend fun runJob(printJob: PrintJob) {
        // Phase 1: mark sebagai started
        try {
            if (!printJob.isQueued) {
                Log.w(TAG, "Job bukan QUEUED state: ${printJob.info.state}, skip")
                return
            }
            printJob.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal start job", t)
            safeFail(printJob, "Tidak bisa start: ${t.message}")
            return
        }

        // Phase 2: lakukan kerja dengan timeout
        val result: Result<Unit> = try {
            withTimeout(OVERALL_TIMEOUT_MS) {
                doPrintJob(printJob)
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(RuntimeException("Timeout 2 menit (proses terlalu lama)"))
        } catch (t: Throwable) {
            Result.failure(t)
        }

        // Phase 3: update state berdasarkan hasil
        if (result.isSuccess) {
            Log.d(TAG, "Job sukses")
            try { printJob.complete() } catch (t: Throwable) {
                Log.e(TAG, "Gagal mark complete", t)
            }
        } else {
            val err = result.exceptionOrNull()
            val msg = "${err?.javaClass?.simpleName ?: "Error"}: ${err?.message ?: "tidak diketahui"}"
            Log.e(TAG, "Job gagal: $msg", err)
            safeFail(printJob, msg)
        }

        // Phase 4: cleanup (selalu disconnect)
        try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
    }

    /**
     * Lakukan print job. Return Result, jangan call printJob.complete/fail di sini —
     * itu tanggung jawab runJob.
     */
    private suspend fun doPrintJob(printJob: PrintJob): Result<Unit> {
        // Cek MAC
        val mac = prefs.lastPrinterMac
        if (mac.isNullOrBlank()) {
            return Result.failure(RuntimeException(
                "Belum pilih printer. Buka app BT Printer → Hubungkan dulu."
            ))
        }

        // Cek dokumen
        val pfd = try {
            printJob.document.data
        } catch (t: Throwable) {
            return Result.failure(RuntimeException("Baca dokumen: ${t.message}", t))
        } ?: return Result.failure(RuntimeException("Dokumen kosong dari sistem"))

        // Cek Bluetooth
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return Result.failure(RuntimeException("Bluetooth service tidak tersedia"))
        val adapter = btManager.adapter
            ?: return Result.failure(RuntimeException("Tidak ada Bluetooth adapter"))
        if (!adapter.isEnabled) {
            return Result.failure(RuntimeException("Bluetooth tidak aktif"))
        }

        // Get device
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (t: Throwable) {
            return Result.failure(RuntimeException("MAC tidak valid ($mac): ${t.message}", t))
        }

        // Fresh disconnect dulu
        try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}

        // Connect dengan timeout
        Log.d(TAG, "Connecting to $mac with ${CONNECT_TIMEOUT_MS}ms timeout...")
        val connectResult = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            BluetoothPrinter.connect(device)
        } ?: return Result.failure(RuntimeException(
            "Connect timeout ${CONNECT_TIMEOUT_MS / 1000}s — printer mati / sibuk / di luar jangkauan?"
        ))

        if (connectResult.isFailure) {
            val err = connectResult.exceptionOrNull()
            return Result.failure(RuntimeException(
                "Connect gagal: ${err?.javaClass?.simpleName}: ${err?.message}", err
            ))
        }

        // Process PDF
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

    /**
     * Mark print job sebagai gagal dengan pesan, tidak throw walaupun gagal mark.
     */
    private fun safeFail(printJob: PrintJob, message: String) {
        try {
            printJob.fail(message)
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal mark printJob.fail()", t)
        }
    }
}
