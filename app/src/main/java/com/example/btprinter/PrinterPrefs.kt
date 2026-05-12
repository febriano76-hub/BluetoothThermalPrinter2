package com.example.btprinter

import android.content.Context

/**
 * Penyimpanan settings printer di SharedPreferences.
 * Dipakai bersama oleh MainActivity dan ThermalPrintService.
 */
class PrinterPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("btprinter_prefs", Context.MODE_PRIVATE)

    /** MAC address printer yang terakhir dipakai (mis. "AA:BB:CC:DD:EE:FF"). */
    var lastPrinterMac: String?
        get() = prefs.getString("last_printer_mac", null)
        set(value) {
            prefs.edit().putString("last_printer_mac", value).apply()
        }

    /** Nama printer (untuk ditampilkan di dialog cetak Android). */
    var lastPrinterName: String?
        get() = prefs.getString("last_printer_name", null)
        set(value) {
            prefs.edit().putString("last_printer_name", value).apply()
        }

    /** Lebar kertas dalam mm. 58 atau 80. */
    var paperWidthMm: Int
        get() = prefs.getInt("paper_width_mm", 58)
        set(value) {
            prefs.edit().putInt("paper_width_mm", value).apply()
        }

    /**
     * Target lebar bitmap dalam pixel saat render PDF.
     * 58mm printer = 384 dots, 80mm = 576 dots (8 dots/mm).
     */
    val targetWidthPx: Int
        get() = when (paperWidthMm) {
            80 -> 576
            else -> 384
        }
}
