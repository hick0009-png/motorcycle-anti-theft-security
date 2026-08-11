package com.example.motorcycleantitheftsensor.ui.settings

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatorQrCodeEncoderTest {
    @Test
    fun encodesTotpUriThatRealQrCodeReaderDecodesExactly() {
        val uri = "otpauth://totp/MotorcycleGuard:VehicleOwner" +
            "?secret=JBSWY3DPEHPK3PXP&issuer=MotorcycleGuard" +
            "&algorithm=SHA1&digits=6&period=30"

        val pixels = AuthenticatorQrCodeEncoder.encode(uri, sizePx = 256).getOrThrow()
        val bitmap = BinaryBitmap(
            HybridBinarizer(RGBLuminanceSource(pixels.sizePx, pixels.sizePx, pixels.argb)),
        )

        assertEquals(uri, QRCodeReader().decode(bitmap).text)
    }

    @Test
    fun rejectsNonTotpUri() {
        assertTrue(
            AuthenticatorQrCodeEncoder.encode("https://example.invalid/secret").isFailure,
        )
    }

    @Test
    fun rejectsQrCodeSmallerThanMinimumSize() {
        val uri = "otpauth://totp/MotorcycleGuard:VehicleOwner" +
            "?secret=JBSWY3DPEHPK3PXP&issuer=MotorcycleGuard" +
            "&algorithm=SHA1&digits=6&period=30"

        assertTrue(AuthenticatorQrCodeEncoder.encode(uri, sizePx = 64).isFailure)
    }
}
