package com.grandparentai

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Captures the device screen on behalf of [AgentService].
 *
 * Uses [AccessibilityService.takeScreenshot] (API 30+) — the service has already been granted
 * "Take screenshots" via the accessibility config, so we don't need MediaProjection's separate
 * user consent flow. This keeps the UX one-tap to enable.
 *
 * Returns a base64-encoded PNG ready to ship to the Claude vision endpoint, plus the actual
 * pixel dimensions we used (so we can scale tap coordinates the model returns).
 */
object ScreenCaptureManager {

    private const val TAG = "ScreenCapture"

    /**
     * Long edge cap. 960px is the sweet spot: text stays readable, but upload + model-encode
     * time drops sharply vs 1280. The agent loop calls this every step, so size matters.
     */
    private const val MAX_LONG_EDGE = 960

    /** PNG quality is lossless; this is the JPEG-style "compression" hint Bitmap.compress takes. */
    private const val PNG_QUALITY = 100

    data class Capture(
        /** Base64 PNG of the (possibly downscaled) screenshot. */
        val base64: String,
        /** Width of the encoded image in pixels. */
        val imageWidth: Int,
        /** Height of the encoded image in pixels. */
        val imageHeight: Int,
        /** Width of the actual device display in pixels. */
        val deviceWidth: Int,
        /** Height of the actual device display in pixels. */
        val deviceHeight: Int,
    ) {
        val xScale: Float get() = deviceWidth.toFloat() / imageWidth
        val yScale: Float get() = deviceHeight.toFloat() / imageHeight
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun capture(service: AccessibilityService): Capture? = suspendCoroutine { cont ->
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val bitmap = decodeToBitmap(result) ?: run {
                                cont.resume(null); return
                            }
                            val (out, w, h) = downscaleIfNeeded(bitmap)
                            val base64 = encodePng(out)
                            cont.resume(
                                Capture(
                                    base64 = base64,
                                    imageWidth = w,
                                    imageHeight = h,
                                    deviceWidth = bitmap.width,
                                    deviceHeight = bitmap.height,
                                )
                            )
                            if (out !== bitmap) out.recycle()
                            bitmap.recycle()
                        } catch (t: Throwable) {
                            Log.e(TAG, "decode/encode failed", t)
                            cont.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed: errorCode=$errorCode")
                        cont.resume(null)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "takeScreenshot threw", t)
            cont.resume(null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun decodeToBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? {
        val hb: HardwareBuffer = result.hardwareBuffer ?: return null
        return try {
            // wrapHardwareBuffer keeps the pixels on the GPU; we copy them onto a software
            // bitmap so PNG encoding works on every device.
            val hwBitmap = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return null
            hwBitmap.copy(Bitmap.Config.ARGB_8888, false).also { hwBitmap.recycle() }
        } finally {
            hb.close()
        }
    }

    private fun downscaleIfNeeded(src: Bitmap): Triple<Bitmap, Int, Int> {
        val long = maxOf(src.width, src.height)
        if (long <= MAX_LONG_EDGE) return Triple(src, src.width, src.height)
        val scale = MAX_LONG_EDGE.toFloat() / long
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        return Triple(scaled, w, h)
    }

    private fun encodePng(b: Bitmap): String {
        val baos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    @Suppress("unused")
    private fun ignorePixelFormat(@Suppress("UNUSED_PARAMETER") pf: PixelFormat) = Unit
}
