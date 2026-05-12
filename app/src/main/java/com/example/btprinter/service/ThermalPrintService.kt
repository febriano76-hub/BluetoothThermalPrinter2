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
 * Terdaftar di sistem via AndroidManifest.xml dengan permission
 * BIND_PRINT_SERVICE. User harus aktifkan secara manual di:
 *   Setelan → Setelan lainnya → Layanan Pencetakan → BT Printer
 *
 * Kalau aktif, printer Bluetooth yang sudah dipilih di MainActivity akan
 * muncul di dialog cetak (window.print()) di semua aplikasi.
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
        scope.cancel()
        BluetoothPrinter.disconnect()
        super.onDestroy()
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        Log.d(TAG, "onCreatePrinterDiscoverySession")
        return ThermalPrinterDiscoverySession(this, prefs)
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        Log.d(TAG, "Cancel requested for: ${printJob.info.label}")
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        Log.d(TAG, "Print job queued: ${printJob.info.label}, " +
                "${printJob.document.info.pageCount} pages")

        scope.launch {
            handleJob(printJob)
        }
    }

    private suspend fun handleJob(printJob: PrintJob) {
        try {
            printJob.start()

            val mac = prefs.lastPrinterMac
            if (mac.isNullOrBlank()) {
                fail(printJob, "Pilih printer dulu di aplikasi BT Printer")
                return
            }

            val document = printJob.document
            val pfd = document.data
            if (pfd == null) {
                fail(printJob, "Dokumen kosong")
                return
            }

            // Pastikan Bluetooth aktif & dapat device
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter
            if (adapter == null) {
                fail(printJob, "Bluetooth tidak tersedia")
                return
            }
            if (!adapter.isEnabled) {
                fail(printJob, "Nyalakan Bluetooth dulu")
                return
            }

            val device = try {
                adapter.getRemoteDevice(mac)
            } catch (e: IllegalArgumentException) {
                fail(printJob, "MAC printer tidak valid: $mac")
                return
            }

            // Connect (atau pakai koneksi yang ada)
            if (!BluetoothPrinter.isConnected) {
                Log.d(TAG, "Connecting to $mac...")
                val connectResult = BluetoothPrinter.connect(device)
                if (connectResult.isFailure) {
                    fail(printJob, "Gagal connect: ${connectResult.exceptionOrNull()?.message}")
                    return
                }
            }

            // Proses PDF → ESC/POS
            val processor = PrintJobProcessor(prefs.targetWidthPx)
            val result = processor.process(pfd.fileDescriptor)

            if (result.isSuccess) {
                Log.d(TAG, "Print job complete")
                printJob.complete()
            } else {
                fail(printJob, "Gagal cetak: ${result.exceptionOrNull()?.message}")
            }

            // Disconnect setelah print supaya MainActivity bisa pakai
            BluetoothPrinter.disconnect()

        } catch (e: Exception) {
            Log.e(TAG, "Error handling job", e)
            fail(printJob, e.message ?: "Error tidak diketahui")
        }
    }

    private fun fail(printJob: PrintJob, message: String) {
        Log.e(TAG, "FAIL: $message")
        try {
            printJob.fail(message)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mark printJob.fail()", e)
        }
    }
}
