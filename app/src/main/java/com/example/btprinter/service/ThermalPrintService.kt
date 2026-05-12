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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.5 fixes "koneksi mati saat print web":
 *  - REUSE koneksi yang sudah dibuat MainActivity. Code lama selalu
 *    disconnect+reconnect tiap job yang justru memutuskan koneksi
 *    yang sudah work bagus, dan reconnect ke printer thermal BT
 *    murahan sering gagal karena RFCOMM teardown belum selesai.
 *  - Setelah print job selesai, JANGAN disconnect — biarkan koneksi
 *    hidup untuk job berikutnya. UI MainActivity tetap show "Terhubung".
 *  - Disconnect hanya terjadi kalau: user manual tap "Putus" di app,
 *    atau koneksi ke MAC berbeda, atau service di-destroy oleh system,
 *    atau user request cancel print job.
 *
 * v2.4 (sebelumnya): PrintJob lifecycle dari Main thread, job state
 *  mismatch handling, retry logic.
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
            // Tutup koneksi supaya operasi yang sedang berjalan abort.
            // Setelah cancel, user perlu manual reconnect di MainActivity.
            try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        Log.d(TAG, "Job queued: ${printJob.info.label}, " +
                "${printJob.document.info.pageCount} pages")
        scope.launch { runJob(printJob) }
    }

    private suspend fun runJob(printJob: PrintJob) {
        // PrintJob lifecycle methods harus dari main thread
        val startedOk = withContext(Dispatchers.Main) {
            try {
                if (!printJob.isQueued) {
                    Log.w(TAG, "Job not QUEUED: state=${printJob.info.state}")
                    try {
                        printJob.fail("Job state tidak valid: ${printJob.info.state}")
                    } catch (_: Throwable) {}
                    return@withContext false
                }
                printJob.start()
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal start", t)
                try { printJob.fail("Tidak bisa start: ${t.message}") } catch (_: Throwable) {}
                false
            }
        }
        if (!startedOk) return

        val result: Result<Unit> = try {
            doPrintJob(printJob)
        } catch (t: Throwable) {
            Result.failure(t)
        }

        withContext(Dispatchers.Main) {
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

        // PENTING v2.5: JANGAN disconnect di sini!
        // Biarkan koneksi hidup supaya:
        //  - Status di MainActivity tetap "Terhubung"
        //  - Job berikutnya bisa langsung print tanpa reconnect lama
        //  - Printer tidak di-toggle on/off RFCOMM tiap job
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

        // ===== STRATEGI BARU v2.5: Reuse koneksi kalau bisa =====
        val currentMac = BluetoothPrinter.connectedMac
        when {
            currentMac == mac -> {
                // Koneksi sudah ke device yang diinginkan, langsung pakai
                Log.d(TAG, "Reuse koneksi yang ada ke $mac, skip connect")
            }
            currentMac != null -> {
                // Koneksi ke device lain, perlu switch
                Log.d(TAG, "Switch koneksi: $currentMac → $mac")
                try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
                delay(RECONNECT_DELAY_MS)
                val r = connectWithRetry(adapter, mac)
                if (r.isFailure) return r
            }
            else -> {
                // Belum ada koneksi sama sekali
                Log.d(TAG, "Belum ada koneksi, connect ke $mac")
                val r = connectWithRetry(adapter, mac)
                if (r.isFailure) return r
            }
        }

        Log.d(TAG, "Processing PDF (target=${prefs.targetWidthPx}px)...")
        val processor = PrintJobProcessor(prefs.targetWidthPx)
        return try {
            processor.process(pfd.fileDescriptor)
        } catch (t: Throwable) {
            Result.failure(RuntimeException(
                "Proses PDF: ${t.javaClass.simpleName}: ${t.message}", t
            ))
        }
    }

    private suspend fun connectWithRetry(
        adapter: android.bluetooth.BluetoothAdapter,
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