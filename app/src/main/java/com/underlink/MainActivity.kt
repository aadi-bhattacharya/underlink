package com.underlink

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.underlink.audio.VoiceInterface
import com.underlink.codec.Codec
import com.underlink.hardware.CameraEngine
import com.underlink.hardware.TorchController

class MainActivity : AppCompatActivity() {

    // ── UI ───────────────────────────────────────────────────────────────────
    private lateinit var btnMode:   ToggleButton
    private lateinit var btnPtt:    Button
    private lateinit var btnSend:   Button
    private lateinit var btnCalib:  Button
    private lateinit var btnListen: Button
    private lateinit var etInput:   EditText
    private lateinit var tvLog:     TextView
    private lateinit var tvStatus:  TextView

    // ── Core ─────────────────────────────────────────────────────────────────
    private lateinit var codec:  Codec
    private lateinit var voice:  VoiceInterface
    private lateinit var torch:  TorchController
    private lateinit var camera: CameraEngine
    private val ui = Handler(Looper.getMainLooper())

    // ── RX state machine ─────────────────────────────────────────────────────
    private enum class RxState { IDLE, BEACON, GAP_WAIT, RECEIVING, COOLDOWN }

    @Volatile private var rxState   = RxState.IDLE
    @Volatile private var rxEnabled = false

    private val SLOT_MS              = 200L
    private val BEACON_MIN_MS        = 2500L
    private val GAP_DARK_FRAMES_MIN  = 15
    private val END_SILENCE_SLOTS    = 15
    private val MIN_PAYLOAD_SLOTS    = 60
    private val MAX_SLOTS            = 1000

    private var beaconStartTime = 0L
    private var darkFrames          = 0
    private var consecutiveOffSlots = 0
    private var slotBuffer          = mutableListOf<Float>()
    private var frameBuffer         = mutableListOf<Float>()
    private var rawStream           = mutableListOf<Pair<Long, Float>>()
    private var lastSlotTime        = 0L

    private var baseline        = 0f
    private var onThreshold     = 0f
    private var beaconThreshold = 0f

    private var calibrating = false
    private var calibFrames = mutableListOf<Float>()

    // ── TX state ─────────────────────────────────────────────────────────────
    private var isTransmitting = false
    private var lastTransmittedText = ""

    // ── Button handler ───────────────────────────────────────────────────────
    private lateinit var hardwareButtonHandler: HardwareButtonHandler

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // REQ-11: keep screen on underwater
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        btnMode   = findViewById(R.id.btnMode)
        btnPtt    = findViewById(R.id.btnPtt)
        btnSend   = findViewById(R.id.btnSend)
        btnCalib  = findViewById(R.id.btnCalib)
        btnListen = findViewById(R.id.btnListen)
        etInput   = findViewById(R.id.etInput)
        tvLog     = findViewById(R.id.tvLog)
        tvStatus  = findViewById(R.id.tvStatus)

        codec = Codec()
        voice = VoiceInterface(this)
        voice.initialize()

        val cm    = getSystemService(CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList[0]
        torch  = TorchController(cm, camId)
        camera = CameraEngine(cm, camId)

        camera.onBrightness = { brightness -> handleBrightness(brightness) }

        hardwareButtonHandler = HardwareButtonHandler(
            context = this,

            onPttStart = {
                ui.post {
                    voice.stopSpeaking()
                    voice.startListening(
                        onResult = { text ->
                            ui.post {
                                hardwareButtonHandler.setListening(false)
                                etInput.setText(text)
                                transmit(text)
                            }
                        },
                        onError = {
                            ui.post {
                                hardwareButtonHandler.setListening(false)
                                log("STT failed — type instead")
                            }
                        }
                    )
                }
            },

            onPttStop = {
                ui.post {
                    voice.stopListening()
                    hardwareButtonHandler.setListening(false)
                }
            },

            onModeToggle = {
                ui.post { btnMode.isChecked = !btnMode.isChecked }
            },

            onCancelTransmission = {
                torch.cancelTransmission()
            },

            onRetransmitLast = {
                if (lastTransmittedText.isNotEmpty()) {
                    ui.post { transmit(lastTransmittedText) }
                }
            },

            onFlushTts = {
                voice.stopSpeaking()
            },

            onRecalibrate = {
                ui.post {
                    log("Recalibrating...")
                    stopRx()
                    ui.postDelayed({ startRx() }, 300)
                }
            },

            onForceDecodeNow = {
                forceDecodeNow()
            },

            onClearRxBuffer = {
                ui.post {
                    rawStream.clear()
                    slotBuffer.clear()
                    frameBuffer.clear()
                    log("RX buffer manually cleared.")
                }
            },

            onReset = {
                ui.post {
                    torch.cancelTransmission()
                    isTransmitting = false
                    hardwareButtonHandler.setTransmitting(false)
                    hardwareButtonHandler.setListening(false)
                    voice.stopListening()
                    voice.stopSpeaking()
                    stopRx()
                    if (!btnMode.isChecked) {
                        ui.postDelayed({ startRx() }, 300)
                    }
                    log("Full reset.")
                    setStatus("Reset complete.")
                }
            }
        )

        checkPermissions()
        setupButtons()
    }

    // REQ-5: intercept volume keys before system handles them
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::hardwareButtonHandler.isInitialized &&
            hardwareButtonHandler.handleKeyEvent(event)) {
            return true  // consumed — no super call, no volume overlay
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnMode.setOnCheckedChangeListener { _, isTx ->
            if (isTransmitting) {
                // Block mode switch during TX
                btnMode.isChecked = true
                hardwareButtonHandler.notifyBlocked()
                return@setOnCheckedChangeListener
            }
            hardwareButtonHandler.setMode(isTx)
            if (isTx) {
                stopRx()
                torch.start()
                setStatus("TX ready. Type or hold PTT.")
                btnListen.text = "▶ Start Listening"
            } else {
                torch.stop()
                setStatus("RX mode — press Start Listening")
            }
        }

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) transmit(text)
        }

        btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    voice.stopSpeaking()
                    voice.startListening(
                        onResult = { text ->
                            ui.post {
                                hardwareButtonHandler.setListening(false)
                                etInput.setText(text)
                                transmit(text)
                            }
                        },
                        onError = {
                            ui.post {
                                hardwareButtonHandler.setListening(false)
                                log("STT failed — type instead")
                            }
                        }
                    )
                }
                android.view.MotionEvent.ACTION_UP -> {
                    voice.stopListening()
                    hardwareButtonHandler.setListening(false)
                }
            }
            true
        }

        btnListen.setOnClickListener {
            if (rxEnabled || calibrating) {
                log("Recalibrating...")
                stopRx()
                ui.postDelayed({ startRx() }, 300)
            } else {
                startRx()
            }
        }

        btnCalib.setOnClickListener {
            log("Recalibrating...")
            stopRx()
            ui.postDelayed({ startRx() }, 300)
        }
    }

    // ── TX ────────────────────────────────────────────────────────────────────

    private fun transmit(text: String) {
        log("TX: \"$text\"")
        lastTransmittedText = text
        val slots = codec.encodeToSlots(text)
        val durationSec = (slots.size * SLOT_MS + 3000 + 1500) / 1000
        log("Sending " + slots.size + " slots (~" + durationSec + "s total)")
        setStatus("Transmitting — 3s beacon then payload...")

        stopRx()

        isTransmitting = true
        hardwareButtonHandler.setTransmitting(true)

        torch.transmitMessage(slots) {
            ui.post {
                isTransmitting = false
                hardwareButtonHandler.setTransmitting(false)
                setStatus("TX done.")
                startRx()
            }
        }
    }

    // ── RX: calibration ───────────────────────────────────────────────────────

    private fun stopRx() {
        rxEnabled   = false
        calibrating = false
        rxState     = RxState.IDLE
        beaconStartTime     = 0L
        darkFrames          = 0
        consecutiveOffSlots = 0
        slotBuffer.clear()
        frameBuffer.clear()
        rawStream.clear()
        calibFrames.clear()
        camera.stop()
    }

    private fun startRx() {
        stopRx()
        calibrating    = true
        btnListen.text = "↺ Recalibrate RX"
        setStatus("Calibrating — hold phones still and pointed at each other...")
        camera.start()

        ui.postDelayed({
            val amb = if (calibFrames.isNotEmpty()) calibFrames.average().toFloat() else 20f
            calibFrames.clear()

            baseline        = amb
            beaconThreshold = amb + 40f   // increased margins for dark-room reliability
            onThreshold     = amb + 55f

            log("Calibrated: ambient=" + amb.toInt() +
                    "  beaconThr=" + beaconThreshold.toInt() +
                    "  onThr=" + onThreshold.toInt())

            if (amb > 60f) {
                setStatus("⚠ Room too bright (ambient=" + amb.toInt() + ") — turn off lights then recalibrate")
            } else {
                setStatus("Listening — waiting for 3-second beacon flash")
            }

            calibrating = false
            rxEnabled   = true
            rxState     = RxState.IDLE
        }, 2000)
    }

    // ── Force decode — Vol Up long in RX mode ─────────────────────────────────

    private fun forceDecodeNow() {
        if (rxState != RxState.RECEIVING) return
        val capturedRaw = rawStream.toList()
        val slotCount   = slotBuffer.size
        slotBuffer.clear()
        rawStream.clear()
        rxState = RxState.COOLDOWN
        ui.post {
            log("Force decode — ${capturedRaw.size} raw frames captured")
            setStatus("Decoding...")
        }
        tryDecode(capturedRaw, slotCount)
    }

    // ── RX: frame handler (~30 calls/sec from camera thread) ──────────────────

    private fun handleBrightness(brightness: Float) {
        if (calibrating) { calibFrames.add(brightness); return }
        if (!rxEnabled) return

        val isBright = brightness > beaconThreshold
        val now      = System.currentTimeMillis()

        when (rxState) {

            RxState.IDLE -> {
                if (isBright) {
                    if (beaconStartTime == 0L) beaconStartTime = now
                } else {
                    if (beaconStartTime != 0L) {
                        val beaconDuration = now - beaconStartTime
                        beaconStartTime = 0L
                        if (beaconDuration >= BEACON_MIN_MS) {
                            rxState    = RxState.GAP_WAIT
                            darkFrames = 0
                            ui.post {
                                log("✓ Beacon confirmed (" + beaconDuration + "ms) — waiting for gap")
                                setStatus("Beacon seen — waiting for message...")
                            }
                        } else {
                            ui.post { log("Ignored short flash (" + beaconDuration + "ms)") }
                        }
                    }
                }
            }

            RxState.BEACON -> { /* unused */ }

            RxState.GAP_WAIT -> {
                if (!isBright) {
                    darkFrames++
                    if (darkFrames >= GAP_DARK_FRAMES_MIN) {
                        rxState             = RxState.RECEIVING
                        darkFrames          = 0
                        consecutiveOffSlots = 0
                        slotBuffer.clear()
                        frameBuffer.clear()
                        rawStream.clear()
                        lastSlotTime = System.currentTimeMillis()
                        ui.post {
                            log("Gap clear — receiving payload")
                            setStatus("Receiving...")
                        }
                    }
                } else {
                    darkFrames = 0
                }
            }

            RxState.RECEIVING -> {
                rawStream.add(Pair(now, brightness))
                frameBuffer.add(brightness)

                if (now - lastSlotTime >= SLOT_MS) {
                    val slotBrightness = if (frameBuffer.isNotEmpty())
                        frameBuffer.average().toFloat() else baseline
                    frameBuffer.clear()
                    lastSlotTime += SLOT_MS

                    val softVal = ((slotBrightness - onThreshold) /
                            (onThreshold - baseline + 1f) + 0.5f).coerceIn(0f, 1f)
                    val isOn = softVal > 0.5f

                    if (slotBuffer.isEmpty() && !isOn) {
                        lastSlotTime = now
                    } else {
                        log("Slot " + slotBuffer.size + ": " +
                                slotBrightness.toInt() + " → " + (if (isOn) "ON " else "OFF") +
                                "  (thr=" + onThreshold.toInt() + ")")
                        slotBuffer.add(softVal)
                    }

                    if (isOn) {
                        consecutiveOffSlots = 0
                    } else {
                        consecutiveOffSlots++
                        if (consecutiveOffSlots >= END_SILENCE_SLOTS &&
                            slotBuffer.size >= MIN_PAYLOAD_SLOTS) {
                            val capturedRaw = rawStream.toList()
                            val slotCount   = slotBuffer.size
                            slotBuffer.clear()
                            rawStream.clear()
                            rxState = RxState.COOLDOWN
                            ui.post {
                                log("End silence — $slotCount slots / ${capturedRaw.size} raw frames")
                                setStatus("Decoding...")
                            }
                            tryDecode(capturedRaw, slotCount)
                        }
                    }

                    if (slotBuffer.size > MAX_SLOTS) {
                        val capturedRaw = rawStream.toList()
                        slotBuffer.clear()
                        rawStream.clear()
                        rxState = RxState.COOLDOWN
                        ui.post { log("Safety cap — forcing decode") }
                        tryDecode(capturedRaw, MAX_SLOTS)
                    }
                }
            }

            RxState.COOLDOWN -> { }
        }
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    private fun tryDecode(rawFrames: List<Pair<Long, Float>>, slotCount: Int) {
        Thread {
            ui.post { log("Trying decode (${rawFrames.size} raw frames, ~$slotCount slots)") }

            val threshold = (baseline + onThreshold) / 2f
            val text = codec.decodeFromRawStream(rawFrames, threshold)

            ui.post {
                if (text.isNotEmpty()) {
                    log("✓ Decoded: \"$text\"")
                    setStatus("Got: $text")
                    voice.speak(text)
                    hardwareButtonHandler.vibrateDecodeResult(true)
                } else {
                    log("✗ Decode failed (${rawFrames.size} raw frames)")
                    setStatus("Decode failed — check signal then recalibrate")
                    hardwareButtonHandler.vibrateDecodeResult(false)
                }

                ui.postDelayed({
                    if (rxEnabled) {
                        beaconStartTime = 0L
                        darkFrames      = 0
                        rxState         = RxState.IDLE
                        log("Ready for next message")
                        setStatus("Listening — waiting for 3-second beacon flash")
                    }
                }, 3000)
            }
        }.start()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun checkPermissions() {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) onPermissionsReady()
        else ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(req, perms, grants)
        if (grants.all { it == PackageManager.PERMISSION_GRANTED }) onPermissionsReady()
        else log("Permissions denied — camera and mic required")
    }

    private fun onPermissionsReady() { setStatus("Ready. Switch to TX or RX to begin.") }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun log(msg: String) {
        ui.post {
            val lines = tvLog.text.toString().split("\n").takeLast(40)
            tvLog.text = (lines + msg).joinToString("\n")
        }
    }

    private fun setStatus(msg: String) { ui.post { tvStatus.text = msg } }

    override fun onDestroy() {
        super.onDestroy()
        if (::hardwareButtonHandler.isInitialized) hardwareButtonHandler.release()
        torch.stop(); camera.stop(); voice.shutdown()
    }
}