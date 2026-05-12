package com.example.btprinter.util

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.min

/**
 * Konversi Bitmap ke perintah raster ESC/POS (GS v 0).
 *
 * Format perintah GS v 0:
 *   GS (0x1D) | v (0x76) | 0 (0x30) | m (0x00=normal) |
 *   xL | xH (jumlah byte per baris, little-endian) |
 *   yL | yH (jumlah baris pixel, little-endian) |
 *   d1...dk (data bitmap, MSB first, packed 8 pixel per byte)
 *
 * Pixel "1" = hitam (di-print), "0" = putih (kosong).
 */
object ImageToEscPos {

    /**
     * Konversi penuh: scale, threshold, dan encode jadi ESC/POS.
     *
     * @param bitmap bitmap input (lebar apa saja)
     * @param targetWidthPx lebar target dalam pixel (akan di-round ke kelipatan 8).
     *                     Untuk 58mm pakai 384, untuk 80mm pakai 576.
     * @param threshold luminance di bawah angka ini dianggap hitam (0-255).
     *                  Default 128 (median). Turunkan untuk hasil lebih gelap.
     * @return byte array siap kirim ke printer
     */
    fun convert(bitmap: Bitmap, targetWidthPx: Int, threshold: Int = 160): ByteArray {
        // Round target width ke kelipatan 8 (karena 8 pixel = 1 byte)
        val widthPx = (targetWidthPx / 8) * 8
        if (widthPx <= 0) return ByteArray(0)

        // Resize bitmap kalau lebar berbeda, jaga aspect ratio
        val scaled = if (bitmap.width != widthPx) {
            val ratio = widthPx.toFloat() / bitmap.width
            val newHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, widthPx, newHeight, true)
        } else {
            bitmap
        }

        val height = scaled.height
        val widthBytes = widthPx / 8

        // Ambil semua pixel sekaligus (lebih cepat dari getPixel per pixel)
        val pixels = IntArray(widthPx * height)
        scaled.getPixels(pixels, 0, widthPx, 0, 0, widthPx, height)

        // Buffer hasil: 8 byte header + (widthBytes × height) byte data
        val output = ByteArray(8 + widthBytes * height)

        // Header
        output[0] = 0x1D
        output[1] = 0x76
        output[2] = 0x30
        output[3] = 0x00
        output[4] = (widthBytes and 0xFF).toByte()
        output[5] = ((widthBytes shr 8) and 0xFF).toByte()
        output[6] = (height and 0xFF).toByte()
        output[7] = ((height shr 8) and 0xFF).toByte()

        // Data
        var idx = 8
        for (y in 0 until height) {
            val rowStart = y * widthPx
            for (byteX in 0 until widthBytes) {
                var b = 0
                val baseX = byteX * 8
                for (bit in 0 until 8) {
                    val pixelIdx = rowStart + baseX + bit
                    val color = pixels[pixelIdx]
                    // Hitung luminance perceptual (Rec. 601)
                    val r = Color.red(color)
                    val g = Color.green(color)
                    val bl = Color.blue(color)
                    val lum = (0.299 * r + 0.587 * g + 0.114 * bl).toInt()
                    if (lum < threshold) {
                        b = b or (1 shl (7 - bit))
                    }
                }
                output[idx++] = b.toByte()
            }
        }

        // Recycle scaled bitmap kalau berbeda dari input (jangan recycle input!)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        return output
    }

    /**
     * Konversi bitmap besar jadi beberapa chunk perintah ESC/POS.
     *
     * Banyak printer thermal punya buffer terbatas (~64KB). Kalau kirim
     * raster image sekaligus untuk halaman panjang, printer bisa hang.
     * Solusinya: bagi bitmap jadi strip horizontal, kirim per strip.
     *
     * @param chunkHeightPx tinggi strip per chunk dalam pixel
     * @return list byte array, masing-masing strip
     */
    fun convertChunked(
        bitmap: Bitmap,
        targetWidthPx: Int,
        chunkHeightPx: Int = 128,
        threshold: Int = 160
    ): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var y = 0
        while (y < bitmap.height) {
            val h = min(chunkHeightPx, bitmap.height - y)
            val chunk = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, h)
            chunks.add(convert(chunk, targetWidthPx, threshold))
            chunk.recycle()
            y += chunkHeightPx
        }
        return chunks
    }
}
