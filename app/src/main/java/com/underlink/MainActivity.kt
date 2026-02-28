package com.underlink

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.underlink.audio.VoiceInterface
import com.underlink.codec.Codec
import com.underlink.codec.Framer
import com.underlink.hardware.CameraEngine
import com.underlink.hardware.Demodulator
import com.underlink.hardware.TorchController

class MainActivity : AppCompatActivity() {

    private lateinit var switchMode:      Switch
    private lateinit var etTx:            EditText
    private lateinit var btnSend:         Button
    private lateinit var btnPtt:          Button
    private lateinit var tvRxLog:         TextView
    private lateinit var scrollRx:        ScrollView
    private lateinit var progressQuality: ProgressBar

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraId: String
    private var torchController: TorchController? = null
    private var cameraEngine: CameraEngine? = null

    private val codec = Codec()
    private val voiceInterface = VoiceInterface()

    private val demodulator = Demodulator(slotMs = 100L) { rawBits, linkQuality ->
        runOnUiThread { onRawBitsReceived(rawBits, linkQuality) }
    }
    private var rxBitBuffer = IntArray(0)

    companion object {
        private const val PERMISSIONS_REQUEST = 42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchMode      = findViewById(R.id.switchMode)
        etTx            = findViewById(R.id.etTx)
        btnSend         = findViewById(R.id.btnSend)
        btnPtt          = findViewById(R.id.btnPtt)
        tvRxLog         = findViewById(R.id.tvRxLog)
        scrollRx        = findViewById(R.id.scrollRx)
        progressQuality = findViewById(R.id.progressQuality)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager.cameraIdList.firstOrNull() ?: "0"

        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        torchController?.stop()
        cameraEngine?.stopCamera()
        voiceInterface.shutdown()
    }

    private fun checkPermissions() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) onPermissionsReady()
        else ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSIONS_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) onPermissionsReady()
            else log("ERROR: Camera permission denied.")
        }
    }

    private fun onPermissionsReady() {
        voiceInterface.initialize(this)

        torchController = TorchController(cameraManager, cameraId).also { it.start() }
        cameraEngine = CameraEngine(this, cameraManager, cameraId).also { engine ->
            engine.onFrameProcessedListener = { rowMeans -> demodulator.onFrame(rowMeans) }
        }

        switchMode.isChecked = true
        switchMode.text = "TX Mode"
        setTxUiEnabled(true)

        switchMode.setOnCheckedChangeListener { _, isTx ->
            switchMode.text = if (isTx) "TX Mode" else "RX Mode"
            setTxUiEnabled(isTx)
            if (isTx) {
                cameraEngine?.stopCamera()
                demodulator.reset()
                log("→ TX mode. Point flash at receiver.")
            } else {
                rxBitBuffer = IntArray(0)
                demodulator.reset()
                cameraEngine?.startCamera()
                log("→ RX mode. Calibrating 500ms — hold still...")
            }
        }

        btnPtt.setOnClickListener {
            if (btnPtt.text == "SPEAK") {
                btnPtt.text = "STOP"
                log("Listening...")
                voiceInterface.startListening(
                    onResult = { text ->
                        runOnUiThread {
                            btnPtt.text = "SPEAK"
                            etTx.setText(text)
                            log("Heard: \"$text\" — transmitting...")
                            transmit(text)
                        }
                    },
                    onError = {
                        runOnUiThread {
                            btnPtt.text = "SPEAK"
                            log("Voice error — try again.")
                        }
                    }
                )
            } else {
                voiceInterface.stopListening()
                btnPtt.text = "SPEAK"
                log("Stopped listening.")
            }
        }

        btnSend.setOnClickListener {
            val text = etTx.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) { log("Type a message first."); return@setOnClickListener }
            transmit(text)
        }

        log("Ready. 100ms/slot.")
    }

    private fun setTxUiEnabled(enabled: Boolean) {
        etTx.isEnabled    = enabled
        btnSend.isEnabled = enabled
        btnPtt.isEnabled  = enabled
    }

    private fun transmit(text: String) {
        val tc = torchController ?: run { log("ERROR: hardware not ready."); return }
        btnSend.isEnabled = false
        btnPtt.isEnabled  = false
        val encoded = codec.encode(text)
        val framed  = Framer.wrap(encoded)
        log("TX \"$text\" -> ${framed.size} slots (~${framed.size * 100L / 1000}s)")
        tc.transmitBits(framed) {
            runOnUiThread {
                log("TX complete")
                btnSend.isEnabled = true
                btnPtt.isEnabled  = true
            }
        }
    }

    private fun onRawBitsReceived(rawBits: IntArray, linkQuality: Int) {
        progressQuality.progress = linkQuality
        log("RX: ${rawBits.size} slots, quality ${linkQuality}%")

        rxBitBuffer = rxBitBuffer + rawBits
        var remaining = rxBitBuffer
        var decoded = false

        while (true) {
            val result = Framer.tryUnwrap(remaining) ?: break
            val (payload, leftover) = result
            remaining = leftover
            decoded = true
            log("  Frame: ${payload.size} bits — decoding...")
            try {
                val soft = FloatArray(payload.size) { payload[it].toFloat() }
                val text = codec.decode(soft)
                log("  \"$text\"")
                voiceInterface.speak(text) {}
            } catch (e: Exception) {
                log("  Decode error: ${e.message}")
            }
        }

        rxBitBuffer = remaining
        if (!decoded) log("  No valid frame found.")
    }

    private fun log(line: String) {
        tvRxLog.append("$line\n")
        scrollRx.post { scrollRx.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}