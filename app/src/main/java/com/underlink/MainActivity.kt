package com.underlink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.underlink.hardware.CameraEngine
import com.underlink.hardware.TorchController

class MainActivity : AppCompatActivity() {

    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    private lateinit var cameraManager: CameraManager
    
    private var torchController: TorchController? = null
    private var cameraEngine: CameraEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.textureView)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        findViewById<Button>(R.id.btnTestFlash).setOnClickListener {
            testFlashlight()
        }

        // Request camera permissions at startup
        if (checkCameraPermission()) {
            setupHardware()
        } else {
            requestCameraPermission()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupHardware()
            } else {
                Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
                finish() // App cannot function without camera
            }
        }
    }

    private fun setupHardware() {
        try {
            // Find the rear facing camera
            val cameraIdList = cameraManager.cameraIdList
            var rearCameraId: String? = null
            
            for (id in cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    rearCameraId = id
                    break
                }
            }

            if (rearCameraId != null) {
                // Initialize controllers
                torchController = TorchController(cameraManager, rearCameraId)
                torchController?.start()

                cameraEngine = CameraEngine(this, cameraManager, rearCameraId)
                
                // Set up the listener to receive the 720 row-means from the bright/dark stripes
                cameraEngine?.onFrameProcessedListener = { rowMeans ->
                    // This runs ~30 times a second! 
                    // Log the first value just to prove it's working without spamming Logcat too badly
                    // Log.d("MainActivity", "Frame received, Row 0 mean: ${rowMeans[0]}")
                }
                
                // Start capturing YUV frames!
                cameraEngine?.startCamera()
                
            } else {
                Log.e("MainActivity", "No rear camera found!")
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to setup hardware", e)
        }
    }

    private fun testFlashlight() {
        val torch = torchController ?: return
        
        // Generate an alternating 101010 pattern for testing
        // At 180Hz, 360 bits = exactly 2 seconds of transmission
        val testBits = BooleanArray(360) { i -> i % 2 == 0 } 
        
        Toast.makeText(this, "Flashing for 2 seconds...", Toast.LENGTH_SHORT).show()
        
        // Disable button to prevent spamming while transmitting
        findViewById<Button>(R.id.btnTestFlash).isEnabled = false
        
        torch.transmitBits(testBits) {
            // Callback runs when transmission is complete
            runOnUiThread {
                findViewById<Button>(R.id.btnTestFlash).isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Crucial: Clean up background threads and camera sessions when app closes
        cameraEngine?.stopCamera()
        torchController?.stop()
    }
}