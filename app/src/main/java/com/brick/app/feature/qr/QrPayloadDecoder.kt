package com.brick.app.feature.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Decodes QR payloads from raw camera luminance planes.
 *
 * Kept free of Android types so the scanning path is verifiable on the JVM: the analyzer
 * feeds it the Y plane of a CameraX frame, tests feed it a synthetic plane.
 */
class QrPayloadDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    /**
     * @param luminance 8-bit grayscale plane, [rowStride] bytes per row
     * @return the decoded text, or null when no QR code is present in the frame
     */
    fun decode(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width
    ): String? {
        if (width <= 0 || height <= 0) return null
        if (rowStride < width) return null
        if (luminance.size < rowStride * height) return null

        val source = PlanarYUVLuminanceSource(
            luminance,
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false
        )
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
