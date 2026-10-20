package com.brick.app.feature.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.SecureRandom

/**
 * Generates offline printable QR token payloads and standards-compliant QR Code bitmaps.
 *
 * Encoding is performed by ZXing so a generated bitmap is decodable by [QrPayloadDecoder]
 * and by any third-party scanner, which is what makes a printed card a usable physical token.
 */
object QrCodeGenerator {

    const val PREFIX = "BRICK-QR-V1:"

    private const val QUIET_ZONE_MODULES = 4
    private val random = SecureRandom()

    fun generateUniquePayload(label: String = "Card"): String {
        val randomBytes = ByteArray(16)
        random.nextBytes(randomBytes)
        val hex = randomBytes.joinToString("") { "%02x".format(it) }
        return "$PREFIX${sanitizeLabel(label)}_$hex"
    }

    fun isBrickPayload(payload: String): Boolean = payload.startsWith(PREFIX)

    /**
     * Encodes [payload] into the QR module matrix, indexed as `[y][x]`, `true` meaning a dark module.
     * Exposed so the encoding can be verified without an Android graphics stack.
     */
    fun encodeToModules(payload: String): Array<BooleanArray> {
        require(payload.isNotBlank()) { "QR payload must not be blank" }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(matrix.height) { y ->
            BooleanArray(matrix.width) { x -> matrix.get(x, y) }
        }
    }

    /**
     * Renders [payload] as a monochrome QR bitmap, scaled up to at most [width] x [height]
     * on whole-module boundaries so the printed code stays sharp.
     */
    fun generateQrBitmap(payload: String, width: Int = 512, height: Int = 512): Bitmap {
        require(width > 0 && height > 0) { "Bitmap dimensions must be positive" }
        val modules = encodeToModules(payload)
        val moduleCount = modules.size
        val scale = maxOf(1, minOf(width, height) / moduleCount)
        val side = moduleCount * scale

        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            val row = modules[y / scale]
            val rowOffset = y * side
            for (x in 0 until side) {
                pixels[rowOffset + x] = if (row[x / scale]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    private fun sanitizeLabel(label: String): String {
        val cleaned = label.uppercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .trim('_')
        return cleaned.ifEmpty { "CARD" }
    }
}
