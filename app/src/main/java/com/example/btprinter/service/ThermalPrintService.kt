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
 * Print Service untuk Android print framework.
 *
 * v2.1 improvements:
 *  - Wrap semua operasi di try/catch(Throwable) supaya app tidak crash
 *  - Selalu disconnect + fresh connect untuk tiap print job (state bersih)
 *  - Pesan error lebih detail supaya bisa di-diagnosa dari Print Queue UI
 *  - Catch Throwable (bukan cuma Exception) untuk handle OutOfMemoryError dll
 */
class ThermalPrintService : PrintService() {

    companion object {
        private const val TAG = "ThermalPrintService"
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
        Log.d(TAG, "onCreatePrinterDiscoverySession")
        return ThermalPrinterDiscoverySession(this, prefs)
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        Log.d(TAG, "Cancel requested for: ${printJob.info.label}")
        try { printJob.cancel() } catch (_: Throwable) {}
    }

    /**
     * Entry point dari print framework. WRAP DALAM TRY/CATCH untuk hindari
     * app crash kalau ada exception tak terduga di sini atau di handleJob.
     */
    override fun onPrintJobQueued(printJob: PrintJob) {
        try {
            Log.d(TAG, "Print job queued: ${printJob.info.label}, " +
                    "${printJob.document.info.pageCount} pages")

            scope.launch {
                try {
                    handleJob(printJob)
                } catch (t: Throwable) {
                    Log.e(TAG, "FATAL in handleJob", t)
                    // Last resort: mark failed dan ke-catch supaya app tidak crash
                    try {
                        printJob.fail("Internal error: ${t::class.simpleName}: ${t.message}")
                    } catch (_: Throwable) { /* nothing more we can do */ }
                    // Pastikan bluetooth bersih untuk job berikutnya
                    try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "FATAL in onPrintJobQueued", t)
            try { printJob.fail("Tidak bisa start: ${t.message}") } catch (_: Throwable) {}
        }
    }

    private suspend fun handleJob(printJob: PrintJob) {
        // Mark sebagai started — kalau gagal, lapor error tapi jangan crash
        try {
            if (!printJob.isQueued) {
                Log.w(TAG, "Print job not in queued state: ${printJob.info.state}")
                return
            }
            printJob.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal start print job", t)
            return
        }

        // Cek MAC
        val mac = prefs.lastPrinterMac
        if (mac.isNullOrBlank()) {
            return failSafe(printJob, "Belum ada printer. Buka aplikasi BT Printer → Hubungkan dulu.")
        }

        // Cek dokumen
        val pfd = try {
            printJob.document.data
        } catch (t: Throwable) {
            return failSafe(printJob, "Tidak bisa baca dokumen: ${t.message}")
        }
        if (pfd == null) {
            return failSafe(printJob, "Dokumen kosong dari sistem")
        }

        // Cek Bluetooth
        val btManager = try {
            getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        } catch (t: Throwable) {
            return failSafe(printJob, "Bluetooth service error: ${t.message}")
        }
        val adapter = btManager?.adapter
            ?: return failSafe(printJob, "Bluetooth tidak tersedia")
        if (!adapter.isEnabled) {
            return failSafe(printJob, "Bluetooth tidak aktif")
        }

        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (t: Throwable) {
            return failSafe(printJob, "MAC printer tidak valid: $mac")
        }

        // v2.1: SELALU disconnect dulu untuk fresh state per job.
        // Sebelumnya kalau retry, pakai socket lama yang mungkin sudah broken.
        Log.d(TAG, "Disconnect existing (fresh state)")
        try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}

        Log.d(TAG, "Connecting to $mac...")
        val connectResult = BluetoothPrinter.connect(device)
        if (connectResult.isFailure) {
            val err = connectResult.exceptionOrNull()
            return failSafe(printJob,
                "Connect gagal: ${err?.javaClass?.simpleName}: ${err?.message}")
        }

        // Proses PDF → ESC/POS
        Log.d(TAG, "Processing PDF (target width = ${prefs.targetWidthPx}px)...")
        val processor = PrintJobProcessor(prefs.targetWidthPx)
        val processResult = try {
            processor.process(pfd.fileDescriptor)
        } catch (t: Throwable) {
            Log.e(TAG, "Processor threw", t)
            try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}
            return failSafe(printJob,
                "Render gagal: ${t::class.simpleName}: ${t.message}")
        }

        // Disconnect setelah selesai (sukses atau gagal) supaya activity bisa pakai
        try { BluetoothPrinter.disconnect() } catch (_: Throwable) {}

        if (processResult.isSuccess) {
            Log.d(TAG, "Print job complete")
            try { printJob.complete() } catch (t: Throwable) {
                Log.e(TAG, "Error marking complete", t)
            }
        } else {
            val err = processResult.exceptionOrNull()
            failSafe(printJob, "Cetak gagal: ${err?.javaClass?.simpleName}: ${err?.message}")
        }
    }

    /**
     * Mark print job sebagai gagal dengan pesan, tidak throw walaupun gagal mark.
     */
    private fun failSafe(printJob: PrintJob, message: String) {
        Log.e(TAG, "FAIL: $message")
        try {
            printJob.fail(message)
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal mark printJob.fail()", t)
        }
    }
}
