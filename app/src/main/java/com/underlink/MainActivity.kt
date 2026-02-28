import com.underlink.R

import android.os.Bundle
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var modeSwitch: SwitchMaterial
    private lateinit var txPanel: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var rxPanel: androidx.constraintlayout.widget.ConstraintLayout

    private lateinit var pttButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var messageInput: TextInputEditText
    private lateinit var txStatus: TextView

    private lateinit var rxLog: TextView
    private lateinit var clearLogButton: MaterialButton
    private lateinit var qualityBar: ProgressBar
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupModeToggle()
        setupTxControls()
        setupRxControls()

        appendLog("App started. Voice disabled for now.")
    }

    private fun bindViews() {
        modeSwitch = findViewById(R.id.modeSwitch)
        txPanel = findViewById(R.id.txPanel)
        rxPanel = findViewById(R.id.rxPanel)

        pttButton = findViewById(R.id.pttButton)
        sendButton = findViewById(R.id.sendButton)
        messageInput = findViewById(R.id.messageInput)
        txStatus = findViewById(R.id.txStatus)

        rxLog = findViewById(R.id.rxLog)
        clearLogButton = findViewById(R.id.clearLogButton)
        qualityBar = findViewById(R.id.qualityBar)
        logScroll = findViewById(R.id.logScroll)
    }

    private fun setupModeToggle() {
        modeSwitch.isChecked = true // TX mode default
        updateModeUi(isTx = true)

        modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateModeUi(isTx = isChecked)
        }
    }

    private fun updateModeUi(isTx: Boolean) {
        txPanel.visibility = if (isTx) android.view.View.VISIBLE else android.view.View.GONE
        rxPanel.visibility = if (isTx) android.view.View.GONE else android.view.View.VISIBLE
        modeSwitch.text = if (isTx) "TX Mode" else "RX Mode"
    }

    private fun setupTxControls() {
        // Voice placeholder
        pttButton.setOnClickListener {
            txStatus.text = "Status: voice not wired yet"
        }

        // Typed message send
        sendButton.setOnClickListener {
            val text = messageInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                txStatus.text = "Status: type something first"
                return@setOnClickListener
            }
            txStatus.text = "Status: sending typed message…"
            sendMessage(text)
        }
    }

    private fun setupRxControls() {
        clearLogButton.setOnClickListener { rxLog.text = "RX log:\n" }
        qualityBar.progress = 30 // placeholder
    }

    // ===== Glue stubs (codec/hardware later) =====
    private fun sendMessage(text: String) {
        appendLog("TX: $text")

        // Later:
        // val bits = codec.encode(text)
        // hardware.transmit(bits)

        // Temporary loopback so RX UI shows something:
        appendLog("RX (simulated): $text")
    }

    // Person 2 will call this later after camera decode:
    fun onBitsReceived(bits: IntArray) {
        val text = "[decoded placeholder] bits=${bits.size}"
        appendLog("RX: $text")
    }

    private fun appendLog(line: String) {
        rxLog.append(line + "\n")
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}