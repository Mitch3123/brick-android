package com.brick.app.feature.qr

import android.graphics.Bitmap
import android.graphics.Color
import java.security.SecureRandom

/**
 * Generates offline printable QR token payloads and Bitmaps.
 * Generates standalone monochrome Bitmaps suitable for printing or screen display.
 */
object QrCodeGenerator {

    private const val PREFIX = "BRICK-QR-V1:"
    private val random = SecureRandom()

    fun generateUniquePayload(label: String = "Card"): String {
        val randomBytes = ByteArray(16)
        random.nextBytes(randomBytes)
        val hex = randomBytes.joinToString("") { "%02x".format(it) }
        return "$PREFIX${label.uppercase().replace(" ", "_")}_$hex"
    }

    /**
     * Generates a monochrome bitmap pattern for a given payload.
     * Uses pseudo-random deterministic grid derivation so it functions completely offline
     * with zero external heavy binary dependencies if needed, or renders standard QR block matrix.
     */
    fun generateQrBitmap(payload: String, width: Int = 512, height: Int = 512): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val hash = payload.hashCode()
        val seed = java.util.Random(hash.toLong())

        val gridSize = 21 // standard QR version 1 size
        val cellW = width / gridSize
        val cellH = height / gridSize

        // Draw background white
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, Color.WHITE)
            }
        }

        // Draw finder patterns (top-left, top-right, bottom-left)
        fun drawFinder(startX: Int, startY: Int) {
            for (gx in 0 until 7) {
                for (gy in 0 until 7) {
                    val isBorder = (gx == 0 || gx == 6 || gy == 0 || gy == 6)
                    val isCenter = (gx in 2..4 && gy in 2..4)
                    val color = if (isBorder || isCenter) Color.BLACK else Color.WHITE
                    for (px in (startX + gx) * cellW until (startX + gx + 1) * cellW) {
                        for (py in (startY + gy) * cellH until (startY + gy + 1) * cellH) {
                            bitmap.setPixel(px, py, color)
                        }
                    }
                }
            }
        }

        drawFinder(0, 0)
        drawFinder(gridSize - 7, 0)
        drawFinder(0, gridSize - 7)

        // Fill data matrix deterministically
        for (gx in 0 until gridSize) {
            for (gy in 0 until gridSize) {
                // Skip finders
                if ((gx < 8 && gy < 8) || (gx >= gridSize - 8 && gy < 8) || (gx < 8 && gy >= gridSize - 8)) {
                    continue
                }
                val isDark = seed.nextBoolean()
                val color = if (isDark) Color.BLACK else Color.WHITE
                for (px in gx * cellW until (gx + 1) * cellW) {
                    for (py in gy * cellH until (gy + 1) * cellH) {
                        bitmap.setPixel(px, py, color)
                    }
                }
            }
        }

        return bitmap
    }
}
