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
 * v2.1 improvements:
 *  - Catch Throwable (bukan cuma Exception) untuk handle OOM
 *  - Pakai RGB_565 (16-bit) instead of ARGB_8888 (32-bit) → halve memory
 *  - Pakai RENDER_MODE_FOR_DISPLAY (lebih kompatibel dari FOR_PRINT)
 *  - Pesan error lebih detail di setiap step
 */
class PrintJobProcessor(
    private val targetWidthPx: Int = 384,
    private val threshold: Int = 160
) {

    companion object {
        private const val TAG = "PrintJobProcessor"
        private const val CHUNK_HEIGHT_PX = 128
    }

    suspend fun process(fileDescriptor: FileDescriptor): Result<Unit> {
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            // Step 1: Duplicate file descriptor
            try {
                pfd = ParcelFileDescriptor.dup(fileDescriptor)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException("dup FD: ${t.message}", t))
            }

            // Step 2: Open PDF renderer
            try {
                renderer = PdfRenderer(pfd!!)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException("PdfRenderer: ${t.message}", t))
            }

            Log.d(TAG, "PDF has ${renderer.pageCount} page(s), target width = $targetWidthPx px")

            if (renderer.pageCount == 0) {
                return Result.failure(RuntimeException("PDF has 0 pages"))
            }

            // Step 3: Init printer
            val initResult = BluetoothPrinter.writeRaw(EscPos.INIT)
            if (initResult.isFailure) {
                return Result.failure(RuntimeException(
                    "Init printer gagal: ${initResult.exceptionOrNull()?.message}"
                ))
            }

            // Step 4: Proses tiap halaman
            for (pageIdx in 0 until renderer.pageCount) {
                val pageResult = processPage(renderer, pageIdx)
                if (pageResult.isFailure) {
                    return pageResult
                }
            }

            // Step 5: Feed di akhir supaya struk keluar
            val feedResult = BluetoothPrinter.writeRaw(EscPos.FEED_3)
            if (feedResult.isFailure) {
                Log.w(TAG, "Feed gagal: ${feedResult.exceptionOrNull()?.message}")
                // Bukan critical error, lanjut return success
            }

            return Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error in process()", t)
            return Result.failure(t)
        } finally {
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    private suspend fun processPage(renderer: PdfRenderer, pageIdx: Int): Result<Unit> {
        var page: PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        try {
            // Open page
            try {
                page = renderer.openPage(pageIdx)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "openPage($pageIdx): ${t.message}", t
                ))
            }

            val pageWidth = page!!.width
            val pageHeight = page.height
            if (pageWidth <= 0 || pageHeight <= 0) {
                return Result.failure(RuntimeException(
                    "Invalid page size: ${pageWidth}x${pageHeight}"
                ))
            }

            val ratio = targetWidthPx.toFloat() / pageWidth
            val targetHeight = (pageHeight * ratio).toInt().coerceAtLeast(1)

            Log.d(TAG, "Page $pageIdx: ${pageWidth}x${pageHeight} pt → " +
                    "${targetWidthPx}x${targetHeight} px (ratio=$ratio)")

            // Cap height untuk mencegah OOM di dokumen yang sangat panjang
            val maxHeight = 8000
            val actualHeight = targetHeight.coerceAtMost(maxHeight)
            if (actualHeight < targetHeight) {
                Log.w(TAG, "Capping height: $targetHeight → $actualHeight")
            }

            // v2.1: RGB_565 = 2 bytes per pixel (vs ARGB_8888 = 4). Cukup untuk monokrom.
            bitmap = try {
                Bitmap.createBitmap(targetWidthPx, actualHeight, Bitmap.Config.RGB_565)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "createBitmap(${targetWidthPx}x${actualHeight}): ${t.message}", t
                ))
            }

            // White background
            Canvas(bitmap).drawColor(Color.WHITE)

            // Render PDF → bitmap. RENDER_MODE_FOR_DISPLAY lebih kompatibel
            // (sebagian device crash dengan RENDER_MODE_FOR_PRINT)
            try {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "page.render: ${t.message}", t
                ))
            }

            page.close()
            page = null

            // Bagi jadi chunk dan kirim
            val chunks = try {
                ImageToEscPos.convertChunked(bitmap, targetWidthPx, CHUNK_HEIGHT_PX, threshold)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "convertChunked: ${t.message}", t
                ))
            }

            Log.d(TAG, "Page $pageIdx → ${chunks.size} chunk(s)")

            for ((i, chunk) in chunks.withIndex()) {
                val sendResult = BluetoothPrinter.writeRaw(chunk)
                if (sendResult.isFailure) {
                    val err = sendResult.exceptionOrNull()
                    return Result.failure(RuntimeException(
                        "Send chunk $i/${chunks.size}: ${err?.message}", err
                    ))
                }
            }

            return Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error processing page $pageIdx", t)
            return Result.failure(t)
        } finally {
            try { page?.close() } catch (_: Throwable) {}
            try { bitmap?.recycle() } catch (_: Throwable) {}
        }
    }
}
