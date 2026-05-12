package com.example.btprinter.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * v2.8 FIX "file descriptor not seekable":
 *  - Print Framework kasih pipe FD (streaming) tapi PdfRenderer butuh
 *    seekable FD. Sebelum process, salin isi pipe ke file regular di
 *    cacheDir, lalu kasih File itu ke processor.
 *  - Cleanup temp file di finally block.
 *
 * v2.7: Smart-cast fix, helper function untuk readability.
 * v2.6: document.data diakses di Main thread.
 * v2.5: Reuse koneksi yang sudah dibuat MainActivity.
 * v2.4: PrintJob lifecycle dari Main thread, retry logic.
 */
class ThermalPrintService : PrintService() {

    companion object {
        private const val TAG = "ThermalPrintService"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 1500L
        private const val CONNECT_RETRY_COUNT = 2
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
        scope.launch {
            withContext(Dispatchers.Main) {
                try { printJob.cancel() } catch (_: Throwable) {}
            }
            try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        Log.d(TAG, "Job queued: ${printJob.info.label}, " +
                "${printJob.document.info.pageCount} pages")
        scope.launch { runJob(printJob) }
    }

    private suspend fun runJob(printJob: PrintJob) {
        val pfd = withContext(Dispatchers.Main) {
            prepareAndStart(printJob)
        } ?: return

        val result: Result<Unit> = try {
            doPrintJob(pfd)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            try { pfd.close() } catch (_: Throwable) {}
        }

        withContext(Dispatchers.Main) {
            finishJob(printJob, result)
        }
    }

    private fun prepareAndStart(printJob: PrintJob): ParcelFileDescriptor? {
        return try {
            if (!printJob.isQueued) {
                Log.w(TAG, "Job not QUEUED: state=${printJob.info.state}")
                try {
                    printJob.fail("Job state tidak valid: ${printJob.info.state}")
                } catch (_: Throwable) {}
                return null
            }

            val pfd: ParcelFileDescriptor = try {
                printJob.document.data ?: run {
                    try { printJob.fail("Dokumen kosong (data null)") } catch (_: Throwable) {}
                    return null
                }
            } catch (t: Throwable) {
                Log.e(TAG, "document.data error", t)
                try { printJob.fail("Baca dokumen: ${t.message}") } catch (_: Throwable) {}
                return null
            }

            if (!printJob.start()) {
                Log.w(TAG, "printJob.start() returned false")
                try { printJob.fail("Gagal start job") } catch (_: Throwable) {}
                try { pfd.close() } catch (_: Throwable) {}
                return null
            }

            pfd
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal init di Main", t)
            try { printJob.fail("Init error: ${t.message}") } catch (_: Throwable) {}
            null
        }
    }

    private fun finishJob(printJob: PrintJob, result: Result<Unit>) {
        if (result.isSuccess) {
            Log.d(TAG, "Job sukses")
            try { printJob.complete() } catch (t: Throwable) {
                Log.e(TAG, "Mark complete error", t)
            }
        } else {
            val err = result.exceptionOrNull()
            val msg = "${err?.javaClass?.simpleName ?: "Error"}: ${err?.message ?: "?"}"
            Log.e(TAG, "Job gagal: $msg", err)
            try { printJob.fail(msg) } catch (t: Throwable) {
                Log.e(TAG, "Mark fail error", t)
            }
        }
    }

    private suspend fun doPrintJob(pfd: ParcelFileDescriptor): Result<Unit> {
        val mac = prefs.lastPrinterMac
        if (mac.isNullOrBlank()) {
            return Result.failure(RuntimeException(
                "Belum pilih printer. Buka app BT Printer → Hubungkan dulu."
            ))
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return Result.failure(RuntimeException("Bluetooth service N/A"))
        val adapter = btManager.adapter
            ?: return Result.failure(RuntimeException("Bluetooth adapter N/A"))
        if (!adapter.isEnabled) {
            return Result.failure(RuntimeException("Bluetooth tidak aktif"))
        }

        // Reuse koneksi kalau bisa (v2.5)
        val currentMac = BluetoothPrinter.connectedMac
        when {
            currentMac == mac -> {
                Log.d(TAG, "Reuse koneksi yang ada ke $mac, skip connect")
            }
            currentMac != null -> {
                Log.d(TAG, "Switch koneksi: $currentMac → $mac")
                try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
                delay(RECONNECT_DELAY_MS)
                val r = connectWithRetry(adapter, mac)
                if (r.isFailure) return r
            }
            else -> {
                Log.d(TAG, "Belum ada koneksi, connect ke $mac")
                val r = connectWithRetry(adapter, mac)
                if (r.isFailure) return r
            }
        }

        // ===== v2.8: Salin pipe FD ke temp file (seekable) =====
        Log.d(TAG, "Copy PDF dari pipe FD ke temp file...")
        val tempFile = try {
            copyPfdToTempFile(pfd)
        } catch (t: Throwable) {
            return Result.failure(RuntimeException(
                "Copy PDF ke temp: ${t.message}", t
            ))
        }
        Log.d(TAG, "Temp file: ${tempFile.absolutePath} (${tempFile.length()} bytes)")

        return try {
            Log.d(TAG, "Processing PDF (target=${prefs.targetWidthPx}px)...")
            val processor = PrintJobProcessor(prefs.targetWidthPx)
            processor.process(tempFile)
        } catch (t: Throwable) {
            Result.failure(RuntimeException(
                "Proses PDF: ${t.javaClass.simpleName}: ${t.message}", t
            ))
        } finally {
            try { tempFile.delete() } catch (_: Throwable) {}
        }
    }

    /**
     * Salin isi ParcelFileDescriptor (yang mungkin pipe / non-seekable)
     * ke file regular di cacheDir. File regular selalu seekable, jadi
     * aman untuk PdfRenderer.
     */
    private fun copyPfdToTempFile(pfd: ParcelFileDescriptor): File {
        val tempFile = File.createTempFile("printjob_", ".pdf", cacheDir)
        FileInputStream(pfd.fileDescriptor).use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }

    private suspend fun connectWithRetry(
        adapter: BluetoothAdapter,
        mac: String
    ): Result<Unit> {
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (t: Throwable) {
            return Result.failure(RuntimeException("MAC tidak valid: ${t.message}", t))
        }

        var lastErr: Throwable? = null
        for (attempt in 1..CONNECT_RETRY_COUNT) {
            Log.d(TAG, "Connect attempt $attempt/$CONNECT_RETRY_COUNT " +
                    "(watchdog ${CONNECT_TIMEOUT_MS}ms)...")
            val r = BluetoothPrinter.connect(device, CONNECT_TIMEOUT_MS)
            if (r.isSuccess) return Result.success(Unit)
            lastErr = r.exceptionOrNull()
            Log.w(TAG, "Attempt $attempt gagal: ${lastErr?.message}")
            if (attempt < CONNECT_RETRY_COUNT) {
                delay(RECONNECT_DELAY_MS)
            }
        }
        return Result.failure(RuntimeException(
            "Connect gagal setelah $CONNECT_RETRY_COUNT attempt: ${lastErr?.message}",
            lastErr
        ))
    }
}