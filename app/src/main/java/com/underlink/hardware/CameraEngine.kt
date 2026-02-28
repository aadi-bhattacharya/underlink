package com.underlink.hardware

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

class CameraEngine(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val cameraId: String
) {
    private val tag = "CameraEngine"
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Callbacks to pass processed frame data back to the decoder pipeline
    var onFrameProcessedListener: ((rowMeans: FloatArray) -> Unit)? = null

    init {
        // Initialize the ImageReader for YUV_420_888 at 1280x720. 
        // 2 max images is usually sufficient for real-time processing without excessive memory buffering.
        imageReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(tag, "Interrupted while waiting for background thread to die", e)
        }
    }

    @SuppressLint("MissingPermission") // Caller MUST ensure CAMERA permission is granted
    fun startCamera() {
        startBackgroundThread()
        try {
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(tag, "Failed to open camera", e)
        }
    }

    fun stopCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        stopBackgroundThread()
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            Log.e(tag, "Camera error: $error")
        }
    }

    private fun createCameraCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return

        try {
            // REQUIRED: TEMPLATE_PREVIEW or TEMPLATE_MANUAL for fast continuous stream
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(reader.surface)

            // REQUIRED CONFIGURATION: Fixed Manual Exposure
            
            // 1. Disable Auto-Exposure (AE) entirely
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            
            // 2. Set exposure time to 2,000,000 nanoseconds (2ms). 
            // Must be less than flash half-period (2.77ms) to prevent blur between on/off states.
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 2_000_000L)
            
            // 3. Set ISO to 800 for sufficient underwater brightness
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 800)

            // Depending on OEM, these might also need to be disabled to prevent HAL overrides
            captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF) // Assuming fixed focus for this specific task
            
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        // Start the continuous requesting of frames
                        session.setRepeatingRequest(
                            captureRequestBuilder.build(),
                            captureCallback, // We pass a callback to monitor EXPOSURE_TIME active state
                            backgroundHandler
                        )
                    } catch (e: CameraAccessException) {
                        Log.e(tag, "Failed to set repeating request", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(tag, "Failed to configure camera session")
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(tag, "Camera exception in capture session setup", e)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            // Diagnostic check: verify the device HAL isn't overriding our manual exposure duration
            val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            if (exposureTime != null && exposureTime != 2_000_000L) {
               Log.w(tag, "WARNING: Device HAL overrode capture time to $exposureTime ns! (Expected 2,000,000 ns). Per-frame normalization required.")
            }
        }
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image: Image? = reader.acquireNextImage()
        if (image == null) return@OnImageAvailableListener

        try {
            // Processing happens here in the BackgroundThread, leaving UI thread free
            processImage(image)
        } finally {
            // MUST ALWAYS CLOSE THE IMAGE to free the buffer for the next frame
            image.close()
        }
    }

    private fun processImage(image: Image) {
        // REQUIRED: Extract Y-plane (luminance) only
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        
        val width = image.width
        val height = image.height

        val rowMeans = FloatArray(height)

        // PERFORMANCE CRITICAL: 
        // Manually iterate byte buffer rather than using Kotlin collection operators.
        // Needs to process 720 rows in under 33ms (target is 30 FPS).
        
        buffer.rewind()
        // Determine how many bytes we can actually read for a row based on width and pixelStride
        val bytesPerRow = width * pixelStride
        val rowBuffer = ByteArray(bytesPerRow)

        for (y in 0 until height) {
            // Move buffer position to start of current row
            buffer.position(y * rowStride)
            // Read entire row into a temporary byte array for faster sequential access
            buffer.get(rowBuffer, 0, bytesPerRow)

            var rowSum = 0L
            // Iterate only over the actual pixels (skipping padding defined by pixelStride - 1)
            for (x in 0 until width) {
                // Byte is signed from -128 to 127 in Java, convert to unsigned 0-255 int
                val pixelValue = rowBuffer[x * pixelStride].toInt() and 0xFF
                rowSum += pixelValue
            }
            // Store the mean luminance for this specific row
            rowMeans[y] = rowSum.toFloat() / width
        }

        // Pass array of 720 row-means onto the Decoder's RoI Detection and Demodulation stages
        onFrameProcessedListener?.invoke(rowMeans)
    }
}
