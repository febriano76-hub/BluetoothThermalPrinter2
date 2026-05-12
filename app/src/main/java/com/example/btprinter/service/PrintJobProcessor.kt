package com.example.btprinter.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.btprinter.BluetoothPrinter
import com.example.btprinter.EscPos
import com.example.btprinter.util.ImageToEscPos
import java.io.FileDescriptor

/**
 * Memproses dokumen PDF (yang dikirim browser/aplikasi via Print framework)
 * jadi perintah raster ESC/POS, lalu kirim ke printer thermal.
 *
 * Alur:
 *   PDF page → render ke Bitmap @ target width → potong jadi strip horizontal
 *   → masing-masing strip dikonversi ke ESC/POS raster bytes → kirim via BT
 */
class PrintJobProcessor(
    private val targetWidthPx: Int = 384,  // 384 untuk 58mm, 576 untuk 80mm
    private val threshold: Int = 160
) {

    companion object {
        private const val TAG = "PrintJobProcessor"
        // Tinggi strip per chunk saat kirim ke printer (mencegah buffer overflow)
        private const val CHUNK_HEIGHT_PX = 128
    }

    /**
     * Proses seluruh dokumen PDF dari file descriptor.
     * Asumsi: BluetoothPrinter sudah terhubung.
     *
     * @param fileDescriptor FD dari PrintJob.document.data
     * @return Result.success kalau semua page sukses dicetak
     */
    suspend fun process(fileDescriptor: FileDescriptor): Result<Unit> {
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            pfd = ParcelFileDescriptor.dup(fileDescriptor)
            renderer = PdfRenderer(pfd)

            Log.d(TAG, "PDF has ${renderer.pageCount} page(s), target width = $targetWidthPx px")

            // Init printer di awal
            BluetoothPrinter.writeRaw(EscPos.INIT).getOrElse {
                return Result.failure(it)
            }

            for (pageIdx in 0 until renderer.pageCount) {
                val pageResult = processPage(renderer, pageIdx)
                if (pageResult.isFailure) {
                    return pageResult
                }
            }

            // Feed di akhir supaya struk keluar dari printer
            BluetoothPrinter.writeRaw(EscPos.FEED_3).getOrElse {
                return Result.failure(it)
            }

            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal proses PDF", e)
            return Result.failure(e)
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun processPage(renderer: PdfRenderer, pageIdx: Int): Result<Unit> {
        var page: PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        try {
            page = renderer.openPage(pageIdx)

            // Hitung tinggi bitmap dengan aspect ratio yang sama
            val ratio = targetWidthPx.toFloat() / page.width
            val targetHeight = (page.height * ratio).toInt().coerceAtLeast(1)

            Log.d(TAG, "Page $pageIdx: ${page.width}x${page.height} pt → " +
                    "$targetWidthPx x $targetHeight px (ratio=$ratio)")

            // Render PDF page ke bitmap. Latar putih dulu karena PdfRenderer
            // tidak otomatis isi background.
            bitmap = Bitmap.createBitmap(
                targetWidthPx, targetHeight, Bitmap.Config.ARGB_8888
            )
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(
                bitmap, null, null,
                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
            )
            page.close()
            page = null

            // Bagi jadi strip dan kirim
            val chunks = ImageToEscPos.convertChunked(
                bitmap, targetWidthPx, CHUNK_HEIGHT_PX, threshold
            )
            Log.d(TAG, "Page $pageIdx split into ${chunks.size} chunk(s)")

            for ((i, chunk) in chunks.withIndex()) {
                val result = BluetoothPrinter.writeRaw(chunk)
                if (result.isFailure) {
                    Log.e(TAG, "Gagal kirim chunk $i: ${result.exceptionOrNull()?.message}")
                    return result
                }
            }

            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal proses page $pageIdx", e)
            return Result.failure(e)
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { bitmap?.recycle() } catch (_: Exception) {}
        }
    }
}
