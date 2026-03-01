package com.underlink.hardware

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

class CameraEngine(
    private val cameraManager: CameraManager,
    private val cameraId: String
) {
    private val TAG = "CameraEngine"

    var onBrightness: ((Float) -> Unit)? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    @SuppressLint("MissingPermission")
    fun start() {
        bgThread = HandlerThread("CamBG").also { it.start(); bgHandler = Handler(it.looper) }
        reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buf   = plane.buffer
                val rs    = plane.rowStride
                val ps    = plane.pixelStride
                val w     = img.width
                val h     = img.height

                val rowMeans = FloatArray(h)
                val rowBytes = ByteArray(w * ps)
                buf.rewind()
                for (y in 0 until h) {
                    buf.position(y * rs)
                    val toRead = minOf(w * ps, buf.remaining())
                    buf.get(rowBytes, 0, toRead)
                    var sum = 0L
                    for (x in 0 until w) sum += rowBytes[x * ps].toInt() and 0xFF
                    rowMeans[y] = sum.toFloat() / w
                }

                val sorted = rowMeans.sortedDescending()
                val topN = (h * 0.25f).toInt().coerceAtLeast(5)
                val brightness = sorted.take(topN).average().toFloat()

                onBrightness?.invoke(brightness)
            } finally {
                img.close()
            }
        }, bgHandler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                val surface = reader!!.surface
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AE_MODE,        CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME,   2_000_000L)  // 2ms — short enough that most HALs won't override
                    set(CaptureRequest.SENSOR_SENSITIVITY,     1600)
                    set(CaptureRequest.CONTROL_AWB_MODE,       CaptureRequest.CONTROL_AWB_MODE_OFF)
                    set(CaptureRequest.CONTROL_AF_MODE,        CaptureRequest.CONTROL_AF_MODE_OFF)
                }

                val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                    private var logCount = 0
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        // Log actual exposure every 90 frames (~3 seconds) so Logcat
                        // doesn't get spammed. If you see ~33000000ns here, the HAL
                        // is overriding manual exposure and you need 100ms slots.
                        if (logCount % 90 == 0) {
                            val actualExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                            Log.d(TAG, "Actual exposure: ${actualExposure}ns (${actualExposure?.div(1_000_000)}ms)")
                        }
                        logCount++
                    }
                }

                cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        s.setRepeatingRequest(req.build(), captureCallback, bgHandler)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "Config failed")
                    }
                }, bgHandler)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); device = null }
            override fun onError(cam: CameraDevice, err: Int) { cam.close(); device = null }
        }, bgHandler)
    }

    fun stop() {
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
        bgThread?.quitSafely(); bgThread = null; bgHandler = null
    }
}