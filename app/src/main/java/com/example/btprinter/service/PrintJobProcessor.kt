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
import kotlinx.coroutines.delay
import java.io.File

/**
 * v3.0 FIX "print berhenti & sambungan terputus di tengah jalan":
 *  - Printer thermal BT punya buffer kecil (8-64 KB). Kirim raster chunk
 *    terlalu cepat → buffer penuh → flow control → write() block →
 *    watchdog 10s nyalip → force-close socket → koneksi putus.
 *  - Tiga lever fix kombinasi:
 *    1. CHUNK_HEIGHT_PX 128 → 48 (chunk lebih kecil, ~3.5 KB untuk 80mm)
 *    2. CHUNK_DELAY_MS 0 → 80ms (kasih waktu printer print sebelum kirim
 *       chunk berikutnya)
 *    3. Write timeout 10s → 30s (grace period lebih panjang)
 *
 * v2.9: ARGB_8888 untuk PdfRenderer.
 * v2.8: terima File untuk seekable FD.
 * v2.1: catch Throwable (OOM), RENDER_MODE_FOR_DISPLAY.
 */
class PrintJobProcessor(
    private val targetWidthPx: Int = 384,
    private val threshold: Int = 160
) {

    companion object {
        private const val TAG = "PrintJobProcessor"
        private const val CHUNK_HEIGHT_PX = 48
        private const val MAX_PAGE_HEIGHT_PX = 6000
        private const val CHUNK_DELAY_MS = 80L
        private const val CHUNK_WRITE_TIMEOUT_MS = 30_000L
        private const val FEED_WRITE_TIMEOUT_MS = 10_000L
    }

    suspend fun process(pdfFile: File): Result<Unit> {
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            if (!pdfFile.exists() || pdfFile.length() == 0L) {
                return Result.failure(RuntimeException(
                    "PDF file kosong atau tidak ada: ${pdfFile.absolutePath}"
                ))
            }

            pfd = try {
                ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "Open PDF file: ${t.message}", t
                ))
            }

            renderer = try {
                PdfRenderer(pfd!!)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "PdfRenderer: ${t.message}", t
                ))
            }

            val r = renderer!!
            Log.d(TAG, "PDF has ${r.pageCount} page(s), target width = $targetWidthPx px, " +
                    "chunk=${CHUNK_HEIGHT_PX}px, delay=${CHUNK_DELAY_MS}ms, " +
                    "write timeout=${CHUNK_WRITE_TIMEOUT_MS}ms")

            if (r.pageCount == 0) {
                return Result.failure(RuntimeException("PDF has 0 pages"))
            }

            val initResult = BluetoothPrinter.writeRaw(EscPos.INIT, FEED_WRITE_TIMEOUT_MS)
            if (initResult.isFailure) {
                return Result.failure(RuntimeException(
                    "Init printer gagal: ${initResult.exceptionOrNull()?.message}"
                ))
            }

            for (pageIdx in 0 until r.pageCount) {
                val pageResult = processPage(r, pageIdx)
                if (pageResult.isFailure) {
                    return pageResult
                }
            }

            val feedResult = BluetoothPrinter.writeRaw(EscPos.FEED_3, FEED_WRITE_TIMEOUT_MS)
            if (feedResult.isFailure) {
                Log.w(TAG, "Feed gagal: ${feedResult.exceptionOrNull()?.message}")
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
            page = try {
                renderer.openPage(pageIdx)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "openPage($pageIdx): ${t.message}", t
                ))
            }

            val pageObj: PdfRenderer.Page = page!!

            val pageWidth = pageObj.width
            val pageHeight = pageObj.height
            if (pageWidth <= 0 || pageHeight <= 0) {
                return Result.failure(RuntimeException(
                    "Invalid page size: ${pageWidth}x${pageHeight}"
                ))
            }

            val ratio = targetWidthPx.toFloat() / pageWidth
            val targetHeight = (pageHeight * ratio).toInt().coerceAtLeast(1)
            val actualHeight = targetHeight.coerceAtMost(MAX_PAGE_HEIGHT_PX)
            if (actualHeight < targetHeight) {
                Log.w(TAG, "Capping height: $targetHeight → $actualHeight")
            }

            Log.d(TAG, "Page $pageIdx: ${pageWidth}x${pageHeight} pt → " +
                    "${targetWidthPx}x${actualHeight} px (ratio=$ratio)")

            val bm: Bitmap = try {
                Bitmap.createBitmap(targetWidthPx, actualHeight, Bitmap.Config.ARGB_8888)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "createBitmap(${targetWidthPx}x${actualHeight}, ARGB_8888): ${t.message}", t
                ))
            }
            bitmap = bm

            Canvas(bm).drawColor(Color.WHITE)

            try {
                pageObj.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "page.render: ${t.message}", t
                ))
            }

            pageObj.close()
            page = null

            val chunks = try {
                ImageToEscPos.convertChunked(bm, targetWidthPx, CHUNK_HEIGHT_PX, threshold)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "convertChunked: ${t.message}", t
                ))
            }

            Log.d(TAG, "Page $pageIdx → ${chunks.size} chunk(s) of ${CHUNK_HEIGHT_PX}px each")

            // v3.0: kirim per chunk dengan timeout panjang & delay antara untuk
            // kasih printer waktu print & jangan banjiri buffer.
            for ((i, chunk) in chunks.withIndex()) {
                val sendResult = BluetoothPrinter.writeRaw(chunk, CHUNK_WRITE_TIMEOUT_MS)
                if (sendResult.isFailure) {
                    val err = sendResult.exceptionOrNull()
                    return Result.failure(RuntimeException(
                        "Send chunk ${i + 1}/${chunks.size}: ${err?.message}", err
                    ))
                }
                // Throttle: kasih printer waktu print sebelum chunk berikutnya
                if (i < chunks.size - 1) {
                    delay(CHUNK_DELAY_MS)
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