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
 * v2.9 FIX "page.render: unsupported pixel format":
 *  - PdfRenderer.Page.render() di AOSP hanya menerima Bitmap.Config.ARGB_8888.
 *    Format lain (RGB_565, ALPHA_8, dll) langsung throw IllegalArgumentException
 *    "Unsupported pixel format". Versi sebelumnya pakai RGB_565 dengan
 *    asumsi hemat memory — itu salah asumsi.
 *  - Sekarang ARGB_8888 (32-bit). Memory: 576px × 6000px × 4 byte = ~14MB max.
 *  - maxHeight diturunkan dari 8000 ke 6000 untuk safety di device low-end.
 *
 * v2.8: terima File langsung untuk PdfRenderer (seekable FD).
 * v2.1: catch Throwable (OOM), RENDER_MODE_FOR_DISPLAY, pesan error detail.
 */
class PrintJobProcessor(
    private val targetWidthPx: Int = 384,
    private val threshold: Int = 160
) {

    companion object {
        private const val TAG = "PrintJobProcessor"
        private const val CHUNK_HEIGHT_PX = 128
        private const val MAX_PAGE_HEIGHT_PX = 6000
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
            val actualHeight = targetHeight.coerceAtMost(MAX_PAGE_HEIGHT_PX)
            if (actualHeight < targetHeight) {
                Log.w(TAG, "Capping height: $targetHeight → $actualHeight")
            }

            Log.d(TAG, "Page $pageIdx: ${pageWidth}x${pageHeight} pt → " +
                    "${targetWidthPx}x${actualHeight} px (ratio=$ratio)")

            // v2.9: HARUS ARGB_8888 — PdfRenderer reject format lain
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