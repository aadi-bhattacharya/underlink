package com.underlink

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    //
    // IDLE       → watching for brightness to go high and stay high
    // BEACON     → brightness is currently high, timing how long it stays on
    // GAP_WAIT   → beacon confirmed (was on ≥2500ms), waiting for silence gap to end
    // RECEIVING  → collecting frames until end-silence detected
    // COOLDOWN   → just decoded, ignoring everything for 3 seconds
    //
    private enum class RxState { IDLE, BEACON, GAP_WAIT, RECEIVING, COOLDOWN }

    @Volatile private var rxState   = RxState.IDLE
    @Volatile private var rxEnabled = false

    private val SLOT_MS              = 200L
    private val BEACON_MIN_MS        = 2500L  // must see brightness for at least this long
    private val GAP_DARK_FRAMES_MIN  = 15     // ~500ms of dark = gap is underway, start receiving
    private val END_SILENCE_SLOTS    = 15     // 15 × 200ms = 3 seconds silence = message over
    private val MIN_PAYLOAD_SLOTS    = 60     // minimum slots for any real message
    private val MAX_SLOTS            = 1000

    // Beacon timing
    private var beaconStartTime = 0L

    // Gap / receiving
    private var darkFrames          = 0
    private var consecutiveOffSlots = 0
    private var slotBuffer          = mutableListOf<Float>()   // per-slot averages (for end-silence detection)
    private var frameBuffer         = mutableListOf<Float>()   // frames in the current slot window
    private var rawStream           = mutableListOf<Pair<Long, Float>>()   // ← NEW: every raw brightness frame stored here
    private var lastSlotTime        = 0L

    // Thresholds
    private var baseline        = 0f
    private var onThreshold     = 0f
    private var beaconThreshold = 0f

    // Calibration
    private var calibrating = false
    private var calibFrames = mutableListOf<Float>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        checkPermissions()
        setupButtons()
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnMode.setOnCheckedChangeListener { _, isTx ->
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
                android.view.MotionEvent.ACTION_DOWN -> voice.startListening(
                    onResult = { text -> transmit(text) },
                    onError  = { log("STT failed — type instead") }
                )
                android.view.MotionEvent.ACTION_UP -> voice.stopListening()
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
        val slots = codec.encodeToSlots(text)
        val durationSec = (slots.size * SLOT_MS + 3000 + 1500) / 1000
        log("Sending " + slots.size + " slots (~" + durationSec + "s total)")
        setStatus("Transmitting — 3s beacon then payload...")
        
        // Disable camera receiver before TX. Active CameraCaptureSessions will
        // intercept and ignore `setTorchMode` requests if they arrive too fast, 
        // turning our Manchester code into garbage.
        stopRx()
        
        torch.transmitMessage(slots) {
            ui.post { 
                setStatus("TX done.") 
                startRx() // Restart listening now that torch is mine
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
        rawStream.clear()      // ← clear raw stream on stop
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
            beaconThreshold = amb + 20f
            onThreshold     = amb + 30f

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

    // ── RX: frame handler (~30 calls/sec from camera thread) ─────────────────

    private fun handleBrightness(brightness: Float) {
        if (calibrating) { calibFrames.add(brightness); return }
        if (!rxEnabled) return

        val isBright = brightness > beaconThreshold
        val now      = System.currentTimeMillis()

        when (rxState) {

            // ── IDLE ──────────────────────────────────────────────────────────
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

            // ── GAP_WAIT ──────────────────────────────────────────────────────
            RxState.GAP_WAIT -> {
                if (!isBright) {
                    darkFrames++
                    if (darkFrames >= GAP_DARK_FRAMES_MIN) {
                        rxState             = RxState.RECEIVING
                        darkFrames          = 0
                        consecutiveOffSlots = 0
                        slotBuffer.clear()
                        frameBuffer.clear()
                        rawStream.clear()               // ← fresh raw stream
                        lastSlotTime = System.currentTimeMillis()
                        ui.post {
                            log("Gap clear — receiving payload (raw stream mode)")
                            setStatus("Receiving...")
                        }
                    }
                } else {
                    darkFrames = 0
                }
            }

            // ── RECEIVING ─────────────────────────────────────────────────────
            //
            // Two things happen in parallel:
            //   1. rawStream  — every single brightness frame is stored here.
            //      The decoder will use this for accurate phase-aligned slot binning.
            //   2. slotBuffer — timer-based slot averages, used ONLY for
            //      end-silence detection (same logic as before).
            //
            RxState.RECEIVING -> {
                // 1. Always store the raw frame with its timestamp
                rawStream.add(Pair(now, brightness))

                // 2. Timer-based slot logic for end-silence detection only
                frameBuffer.add(brightness)

                if (now - lastSlotTime >= SLOT_MS) {
                    val slotBrightness = if (frameBuffer.isNotEmpty())
                        frameBuffer.average().toFloat() else baseline
                    frameBuffer.clear()
                    lastSlotTime += SLOT_MS

                    val softVal = ((slotBrightness - onThreshold) /
                            (onThreshold - baseline + 1f) + 0.5f).coerceIn(0f, 1f)
                    val isOn = softVal > 0.5f

                    // Skip leading OFF slots
                    if (slotBuffer.isEmpty() && !isOn) {
                        lastSlotTime = now
                        // Still keep rawStream frames — decoder's phase search handles alignment
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
                                log("End silence — $slotCount slots / ${capturedRaw.size} raw frames captured")
                                setStatus("Decoding...")
                            }
                            tryDecode(capturedRaw, slotCount)
                        }
                    }

                    // Safety cap
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

            // ── COOLDOWN ──────────────────────────────────────────────────────
            RxState.COOLDOWN -> { }
        }
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    private fun tryDecode(rawFrames: List<Pair<Long, Float>>, slotCount: Int) {
        Thread {
            ui.post { log("Trying phase-search decode (${rawFrames.size} raw frames, ~$slotCount slots)") }

            // Pass the midpoint threshold to the Decoder. This prevents the separator from shifting 
            // due to extra silence slots padding the end of the raw buffer.
            val threshold = (baseline + onThreshold) / 2f
            val text = codec.decodeFromRawStream(rawFrames, threshold)

            ui.post {
                if (text.isNotEmpty()) {
                    log("✓ Decoded: \"$text\"")
                    setStatus("Got: $text")
                    voice.speak(text)
                } else {
                    log("✗ Decode failed (${rawFrames.size} raw frames)")
                    setStatus("Decode failed — check signal then recalibrate")
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
        torch.stop(); camera.stop(); voice.shutdown()
    }
}