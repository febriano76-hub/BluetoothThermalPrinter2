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
import java.io.File

/**
 * v2.8 FIX "file descriptor not seekable":
 *  - PdfRenderer butuh seekable PFD. Print Framework kasih FD pipe yang
 *    tidak seekable, jadi PdfRenderer langsung throw IOException.
 *  - Solusi: terima File regular (sudah di-copy dari pipe oleh caller),
 *    buka ParcelFileDescriptor dari File itu — file regular selalu
 *    seekable. ParcelFileDescriptor.open(File, MODE_READ_ONLY) clean
 *    daripada dup() FD streaming.
 *
 * v2.1.1: nullable bitmap compile fix
 * v2.1: catch Throwable (OOM), RGB_565 instead of ARGB_8888,
 *  RENDER_MODE_FOR_DISPLAY, pesan error detail
 */
class PrintJobProcessor(
    private val targetWidthPx: Int = 384,
    private val threshold: Int = 160
) {

    companion object {
        private const val TAG = "PrintJobProcessor"
        private const val CHUNK_HEIGHT_PX = 128
    }

    /**
     * v2.8: terima File (bukan FileDescriptor). Caller harus salin PDF
     * dari pipe FD ke file regular (di cacheDir) sebelum memanggil ini.
     */
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

            val initResult = BluetoothPrinter.writeRaw(EscPos.INIT)
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

            val feedResult = BluetoothPrinter.writeRaw(EscPos.FEED_3)
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

            Log.d(TAG, "Page $pageIdx: ${pageWidth}x${pageHeight} pt → " +
                    "${targetWidthPx}x${targetHeight} px (ratio=$ratio)")

            val maxHeight = 8000
            val actualHeight = targetHeight.coerceAtMost(maxHeight)
            if (actualHeight < targetHeight) {
                Log.w(TAG, "Capping height: $targetHeight → $actualHeight")
            }

            val bm: Bitmap = try {
                Bitmap.createBitmap(targetWidthPx, actualHeight, Bitmap.Config.RGB_565)
            } catch (t: Throwable) {
                return Result.failure(RuntimeException(
                    "createBitmap(${targetWidthPx}x${actualHeight}): ${t.message}", t
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