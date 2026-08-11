package com.example.motorcycleantitheftsensor.ui.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal class QrCodePixels(
    val sizePx: Int,
    val argb: IntArray,
)

internal object AuthenticatorQrCodeEncoder {
    private const val MINIMUM_SIZE_PX = 128
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun encode(contents: String, sizePx: Int = 512): Result<QrCodePixels> = runCatching {
        require(contents.startsWith("otpauth://totp/"))
        require(sizePx >= MINIMUM_SIZE_PX)
        val matrix = QRCodeWriter().encode(
            contents,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4,
            ),
        )
        val argb = IntArray(sizePx * sizePx) { index ->
            val x = index % sizePx
            val y = index / sizePx
            if (matrix[x, y]) BLACK else WHITE
        }
        QrCodePixels(sizePx, argb)
    }
}
