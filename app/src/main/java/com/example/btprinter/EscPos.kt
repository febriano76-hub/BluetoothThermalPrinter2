package com.example.btprinter

/**
 * Perintah ESC/POS standar untuk printer thermal.
 * Referensi: Epson ESC/POS Command Reference.
 */
object EscPos {
    val INIT = byteArrayOf(0x1B, 0x40)              // Initialize printer
    val LF = byteArrayOf(0x0A)                      // Line feed
    val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
    val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    val ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)
    val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
    val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    val DOUBLE_SIZE = byteArrayOf(0x1D, 0x21, 0x11) // Double height & width
    val NORMAL_SIZE = byteArrayOf(0x1D, 0x21, 0x00)
    val CUT = byteArrayOf(0x1D, 0x56, 0x00)         // Full cut (jika printer punya cutter)
    val FEED_3 = byteArrayOf(0x0A, 0x0A, 0x0A)      // 3 baris kosong supaya struk keluar
}
