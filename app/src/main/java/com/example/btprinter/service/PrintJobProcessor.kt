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
 * v3.2 FIX "print 1 terputus + print 2 ada garbage di awal":
 *  - Root cause: write timeout mid-raster → disconnect → printer
 *    masih dalam mode raster expecting more data. Print job berikutnya
 *    bytes-nya di-interpret printer sebagai data lanjutan raster lama,
 *    menyebabkan garbage di awal cetakan.
 *  - Fix 1 (preventif): chunk lebih kecil + delay lebih lama + timeout
 *    lebih panjang. Mengurangi kemungkinan disconnect di tengah print.
 *    - CHUNK_HEIGHT_PX 48 → 24 (1.7 KB per chunk untuk 80mm)
 *    - CHUNK_DELAY_MS 80 → 200 (kasih printer waktu print yang cukup)
 *    - CHUNK_WRITE_TIMEOUT_MS 30s → 60s
 *  - Fix 2 (recovery): di awal tiap job, kirim padding NUL byte 2KB
 *    sebelum INIT. Kalau printer punya state corrupt dari job sebelumnya
 *    (masih nunggu raster data), padding ini "menyelesaikan" command
 *    pending. Setelah itu, printer kembali ke command mode, INIT baru
 *    bisa diterima sebagai reset command yang sah.
 *    NUL byte aman: command mode → di-ignore; raster mode → zero-pixel.
 *
 * v3.1: trim trailing whitespace.
 * v3.0: chunk 48, delay 80, write timeout 30s.
 */
class PrintJobProcessor(
    private val targetWidthPx: Int = 384,
    private val threshold: Int = 160
) {

    companion object {
        private const val TAG = "PrintJobProcessor"
        private const val CHUNK_HEIGHT_PX = 24
        private const val MAX_PAGE_HEIGHT_PX = 6000
        private const val CHUNK_DELAY_MS = 200L
        private const val CHUNK_WRITE_TIMEOUT_MS = 60_000L
        private const val FEED_WRITE_TIMEOUT_MS = 10_000L
        private const val TRIM_BOTTOM_MARGIN_PX = 24

        // v3.2: padding untuk recovery dari state corrupt
        private const val FLUSH_PADDING_SIZE = 2048
        private const val POST_FLUSH_DELAY_MS = 200L
        private const val POST_INIT_DELAY_MS = 150L
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
            Log.d(TAG, "PDF has ${r.pageCount} page(s), target width = $targetWidthPx px")

            if (r.pageCount == 0) {
                return Result.failure(RuntimeException("PDF has 0 pages"))
            }

            // ===== v3.2: Robust startup =====
            val startupResult = robustStartup()
            if (startupResult.isFailure) {
                return startupResult
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

    /**
     * v3.2: Sebelum INIT, kirim padding NUL byte. Kalau printer punya
     * pending raster command dari job sebelumnya, padding ini selesaikan
     * (NUL = zero pixels). Setelah selesai, printer kembali ke command
     * mode dan INIT bisa diterima dengan benar.
     */
    private suspend fun robustStartup(): Result<Unit> {
        Log.d(TAG, "Robust startup: kirim ${FLUSH_PADDING_SIZE} byte padding NUL...")
        val flushPadding = ByteArray(FLUSH_PADDING_SIZE) { 0x00 }
        val flushResult = BluetoothPrinter.writeRaw(flushPadding, CHUNK_WRITE_TIMEOUT_MS)
        if (flushResult.isFailure) {
            return Result.failure(RuntimeException(
                "Flush padding: ${flushResult.exceptionOrNull()?.message}"
            ))
        }
        delay(POST_FLUSH_DELAY_MS)

        Log.d(TAG, "Robust startup: kirim INIT...")
        val initResult = BluetoothPrinter.writeRaw(EscPos.INIT, FEED_WRITE_TIMEOUT_MS)
        if (initResult.isFailure) {
            return Result.failure(RuntimeException(
                "Init printer: ${initResult.exceptionOrNull()?.message}"
            ))
        }
        delay(POST_INIT_DELAY_MS)
        return Result.success(Unit)
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

            // v3.1: Trim trailing white space
            val contentHeight = findContentBottom(bm, TRIM_BOTTOM_MARGIN_PX)
            if (contentHeight == 0) {
                Log.d(TAG, "Page $pageIdx kosong sepenuhnya, skip")
                return Result.success(Unit)
            }

            val workingBitmap: Bitmap = if (contentHeight < bm.height) {
                Log.d(TAG, "Trim white space: ${bm.height} → $contentHeight px")
                val cropped = try {
                    Bitmap.createBitmap(bm, 0, 0, bm.width, contentHeight)
                } catch (t: Throwable) {
                    return Result.failure(RuntimeException(
                        "Crop bitmap: ${t.message}", t
                    ))
                }
                bm.recycle()
                bitmap = cropped
                cropped
            } else {
                bm
            }

            val chunks = try {
                ImageToEscPos.convertChunked(workingBitmap, targetWidthPx, CHUNK_HEIGHT_PX, threshold)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "convertChunked: ${t.message}", t
                ))
            }

            Log.d(TAG, "Page $pageIdx → ${chunks.size} chunk(s) of ${CHUNK_HEIGHT_PX}px each, " +
                    "delay ${CHUNK_DELAY_MS}ms per chunk")

            for ((i, chunk) in chunks.withIndex()) {
                val sendResult = BluetoothPrinter.writeRaw(chunk, CHUNK_WRITE_TIMEOUT_MS)
                if (sendResult.isFailure) {
                    val err = sendResult.exceptionOrNull()
                    return Result.failure(RuntimeException(
                        "Send chunk ${i + 1}/${chunks.size}: ${err?.message}", err
                    ))
                }
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

    private fun findContentBottom(bitmap: Bitmap, marginPx: Int): Int {
        val width = bitmap.width
        val height = bitmap.height
        val rowPixels = IntArray(width)

        for (y in height - 1 downTo 0) {
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
            for (px in rowPixels) {
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (lum < threshold) {
                    return (y + 1 + marginPx).coerceAtMost(height)
                }
            }
        }
        return 0
    }
}