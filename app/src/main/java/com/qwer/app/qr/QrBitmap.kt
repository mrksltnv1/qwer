package com.qwer.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Turns a [QrCodec] payload string into a scannable bitmap.
 *
 * Error correction is M rather than L: a phone screen shows a QR through glass and
 * sometimes at an angle, and M survives roughly 15 % damage while costing only a
 * modest number of modules. L saves density but fails on real devices.
 */
object QrBitmap {

    /** Pixel size of the generated bitmap. Large enough to scan from another phone. */
    const val SIZE_PX = 900

    fun encode(payload: String, sizePx: Int = SIZE_PX): Bitmap? {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            // 2 modules of quiet zone: below that scanners lose the finder patterns.
            EncodeHintType.MARGIN to 2,
        )
        val matrix = runCatching {
            QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        }.getOrNull() ?: return null

        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
