package com.brick.app.feature.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/** A tightly packed 8-bit grayscale frame: exactly [width] bytes per row. */
class LuminanceFrame(val data: ByteArray, val width: Int, val height: Int)

/**
 * Repacks the Y plane of a camera frame into a dense grayscale buffer.
 *
 * Camera planes are not dense: rows are padded to `rowStride` and samples may be spaced by
 * `pixelStride`, and the frame may carry a crop rectangle. Handing the raw plane to a decoder
 * as if it were dense shears the image and the QR code never decodes, so each row is copied
 * sample by sample and short planes are rejected instead of read out of bounds.
 */
object LuminancePlaneExtractor {

    fun extract(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int
    ): LuminanceFrame? {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null
        if (left < 0 || top < 0) return null

        val plane = buffer.duplicate()
        val out = ByteArray(width * height)
        val lastSampleOffset = (left + width - 1) * pixelStride

        for (y in 0 until height) {
            val rowStart = (top + y) * rowStride
            if (rowStart + lastSampleOffset >= plane.limit()) return null
            var sample = rowStart + left * pixelStride
            val outRow = y * width
            for (x in 0 until width) {
                out[outRow + x] = plane.get(sample)
                sample += pixelStride
            }
        }
        return LuminanceFrame(out, width, height)
    }

    fun extract(image: ImageProxy): LuminanceFrame? {
        val plane = image.planes.firstOrNull() ?: return null
        val crop = image.cropRect
        val width = if (crop.width() > 0) crop.width() else image.width
        val height = if (crop.height() > 0) crop.height() else image.height
        return extract(
            buffer = plane.buffer,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            left = crop.left.coerceAtLeast(0),
            top = crop.top.coerceAtLeast(0),
            width = width,
            height = height
        )
    }
}

/**
 * CameraX analyzer that reports decoded QR text from the frame luminance.
 *
 * [onPayload] is invoked on the analyzer executor, at most once per [QrImageAnalyzer] instance;
 * further frames are ignored so a single card presentation cannot toggle the session twice.
 */
class QrImageAnalyzer(
    private val decoder: QrPayloadDecoder = QrPayloadDecoder(),
    private val onPayload: (String) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    private var consumed = false

    override fun analyze(image: ImageProxy) {
        try {
            if (consumed) return
            val frame = LuminancePlaneExtractor.extract(image) ?: return
            val payload = decoder.decode(frame.data, frame.width, frame.height, frame.width)
                ?: return
            consumed = true
            onPayload(payload)
        } finally {
            image.close()
        }
    }
}
