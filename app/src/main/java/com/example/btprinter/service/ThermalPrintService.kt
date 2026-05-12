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
 * v2.4 fixes for "print web tidak bisa" (manual works, browser print fails):
 *  - Bug 1 FIX: PrintJob lifecycle (start/complete/fail) sekarang dipanggil
 *    dari Dispatchers.Main. Ini bug nomor satu di Android Print Service —
 *    state update dari background thread sering gagal silently sehingga
 *    job stuck di QUEUED dan system tidak pernah deliver dokumen ke kita.
 *  - Bug 2 FIX: Delay 1500ms antara disconnect lama dan connect baru,
 *    supaya RFCOMM session printer ter-teardown sempurna. Banyak printer
 *    thermal BT (terutama murah) tidak bisa terima koneksi baru kalau
 *    yang lama belum sepenuhnya selesai. Plus retry 2x kalau attempt
 *    pertama gagal.
 *  - Bug 3 FIX: Kalau state job sudah bukan QUEUED, kita panggil fail()
 *    bukan diam-diam return — supaya job tidak stuck di queue selamanya.
 *
 * v2.3 (sebelumnya):
 *  - Pakai BluetoothPrinter.connect/writeRaw dengan built-in watchdog timeout
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
        // FIX Bug 1: PrintJob state methods dari main thread
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
        // FIX Bug 1: state check + start() dari main thread
        val startedOk = withContext(Dispatchers.Main) {
            try {
                val state = printJob.info.state
                if (!printJob.isQueued) {
                    Log.w(TAG, "Job not QUEUED: state=$state")
                    // FIX Bug 3: jangan diam-diam return — fail supaya job tidak stuck
                    try { printJob.fail("Job state tidak valid: $state") } catch (_: Throwable) {}
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

        // Lakukan kerja (di IO)
        val result: Result<Unit> = try {
            doPrintJob(printJob)
        } catch (t: Throwable) {
            Result.failure(t)
        }

        // FIX Bug 1: complete/fail dari main thread
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

        // FIX Bug 2: disconnect + delay + retry
        // RFCOMM session perlu waktu untuk teardown sebelum bisa connect ulang.
        try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
        Log.d(TAG, "Sleep ${RECONNECT_DELAY_MS}ms untuk teardown koneksi lama...")
        delay(RECONNECT_DELAY_MS)

        var lastErr: Throwable? = null
        var connected = false
        for (attempt in 1..CONNECT_RETRY_COUNT) {
            Log.d(TAG, "Connect attempt $attempt/$CONNECT_RETRY_COUNT " +
                    "(watchdog ${CONNECT_TIMEOUT_MS}ms)...")
            val r = BluetoothPrinter.connect(device, CONNECT_TIMEOUT_MS)
            if (r.isSuccess) {
                connected = true
                break
            }
            lastErr = r.exceptionOrNull()
            Log.w(TAG, "Attempt $attempt gagal: ${lastErr?.message}")
            if (attempt < CONNECT_RETRY_COUNT) {
                delay(RECONNECT_DELAY_MS)
            }
        }
        if (!connected) {
            return Result.failure(RuntimeException(
                "Connect gagal setelah $CONNECT_RETRY_COUNT attempt: ${lastErr?.message}",
                lastErr
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
}