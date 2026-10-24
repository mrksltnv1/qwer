package com.qwer.app.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes QR codes from camera frames.
 *
 * THE BUG THIS FILE USED TO HAVE
 * ------------------------------
 * The luminance source was built as
 *
 *   PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, rowStride, height, false)
 *
 * passing `rowStride` as BOTH the data width and the visible width. On most devices
 * `rowStride > width` (the camera pads the end of every row to a hardware alignment),
 * so the padding columns were treated as image content. The visible image was therefore
 * sheared across the whole frame width.
 *
 * A sparse code (a short WireGuard config) survived that distortion; a dense one — a
 * WDTT link carrying host, three ports, password and VK hashes — did not. That is
 * exactly the reported behaviour: "QR for WG and Amnezia reads fine, WDTT is not
 * accepted". Correct form: data width = rowStride, visible width = image.width.
 *
 * Other robustness measures:
 *  - TRY_HARDER is ON: a few milliseconds per frame in exchange for reading a dense code.
 *  - A failed pass is retried on an inverted frame, because a dark QR on a bright phone
 *    screen often arrives inverted through the sensor. Inversion is done on the byte
 *    buffer directly — zxing 3.5.3 ships no public InvertedLuminanceSource class.
 *  - Frames are dropped, not queued: `busy` keeps the analyzer from falling behind.
 */
class QrAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }
    private val busy = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val plane = image.planes.getOrNull(0) ?: return
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            if (data.isEmpty()) return

            val stride = plane.rowStride
            val width = image.width
            val height = image.height

            // Some devices omit the padding of the last row; fall back to a tight stride
            // rather than indexing past the end of the buffer.
            val effectiveStride = if (stride >= width && data.size >= stride * height) {
                stride
            } else {
                width
            }

            val text = decodeFrame(data, effectiveStride, width, height, inverted = false)
                ?: decodeFrame(data, effectiveStride, width, height, inverted = true)

            if (!text.isNullOrBlank()) onDecoded(text)
        } catch (_: Throwable) {
            // A single bad frame is normal while the camera focuses; never crash the
            // preview because of it.
        } finally {
            busy.set(false)
            image.close()
        }
    }

    /**
     * One decode attempt. Inverted mode maps every luminance byte `v` to `255 - v`.
     */
    private fun decodeFrame(
        data: ByteArray,
        stride: Int,
        width: Int,
        height: Int,
        inverted: Boolean,
    ): String? = runCatching {
        val bytes = if (inverted) {
            ByteArray(data.size) { i -> (0xFF - (data[i].toInt() and 0xFF)).toByte() }
        } else {
            data
        }

        val source = PlanarYUVLuminanceSource(
            bytes,
            stride,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}
