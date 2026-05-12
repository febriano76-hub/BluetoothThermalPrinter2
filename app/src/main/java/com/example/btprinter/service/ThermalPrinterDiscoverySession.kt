package com.example.btprinter.service

import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.example.btprinter.PrinterPrefs

/**
 * Mengumumkan printer Bluetooth thermal ke sistem print Android.
 *
 * Saat user tap "Print" di browser/aplikasi, framework Android memanggil
 * onStartPrinterDiscovery() di setiap PrintService terdaftar. Yang kita lakukan:
 * cek apakah user sudah pernah pilih printer di MainActivity. Kalau iya,
 * tambahkan ke daftar printer yang ditampilkan di dialog.
 */
class ThermalPrinterDiscoverySession(
    private val printService: PrintService,
    private val prefs: PrinterPrefs
) : PrinterDiscoverySession() {

    override fun onStartPrinterDiscovery(priorityList: List<PrinterId>) {
        val mac = prefs.lastPrinterMac ?: return
        val name = prefs.lastPrinterName ?: "Bluetooth Thermal"

        val id = printService.generatePrinterId(mac)
        val capabilities = buildCapabilities(id, prefs.paperWidthMm)
        val info = PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE)
            .setCapabilities(capabilities)
            .build()

        addPrinters(listOf(info))
    }

    private fun buildCapabilities(id: PrinterId, paperWidthMm: Int): PrinterCapabilitiesInfo {
        // PrintAttributes.MediaSize butuh ukuran dalam mils (1/1000 inch).
        // 1 mm = 39.37 mils.
        val widthMils = (paperWidthMm * 39.37).toInt()
        // Tinggi "kertas" untuk thermal roll: pakai 297mm (~A4 height) supaya
        // halaman web/PDF tidak ter-crop untuk struk panjang.
        val heightMils = (297 * 39.37).toInt()

        val mediaSize = PrintAttributes.MediaSize(
            "THERMAL_${paperWidthMm}MM",
            "Thermal ${paperWidthMm}mm",
            widthMils,
            heightMils
        )

        return PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(mediaSize, true)
            .addResolution(
                PrintAttributes.Resolution("std_203dpi", "203 DPI", 203, 203),
                true
            )
            .setColorModes(
                PrintAttributes.COLOR_MODE_MONOCHROME,
                PrintAttributes.COLOR_MODE_MONOCHROME
            )
            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
            .build()
    }

    override fun onStopPrinterDiscovery() {
        // Tidak ada cleanup khusus
    }

    override fun onValidatePrinters(printerIds: List<PrinterId>) {
        // Printer kita selalu valid kalau MAC tersimpan
    }

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        // Untuk versi v1, status printer tidak di-track real-time
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {
        // No-op
    }

    override fun onDestroy() {
        // No-op
    }
}
