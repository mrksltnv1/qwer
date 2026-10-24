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
 * Design notes:
 *  - Only the luminance plane is used. Colour carries nothing for a black-and-white code.
 *  - `rowStride` is greater than `width` on most devices (padding at the end of each
 *    row). Passing `width` as the data width produces a sheared image that never
 *    decodes — a classic bug that looks like "camera works but QR is invisible".
 *  - Frames are dropped, not queued: `busy` guarantees the analyzer never falls behind
 *    on a slow device, which would otherwise freeze the preview.
 *  - The reader is reused; creating a MultiFormatReader per frame is measurable.
 */
class QrAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                // Our payload is alphanumeric-only, so this is a safe speedup.
                DecodeHintType.TRY_HARDER to false,
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

            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                image.height,
                0,
                0,
                plane.rowStride,
                image.height,
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = runCatching { reader.decode(bitmap) }.getOrNull()
            val text = result?.text
            if (!text.isNullOrBlank()) onDecoded(text)
        } catch (_: Throwable) {
            // A single bad frame is normal while the camera focuses; never crash the
            // preview because of it.
        } finally {
            busy.set(false)
            image.close()
        }
    }
}
