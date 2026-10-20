package com.bhttp.tunnel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private lateinit var modeSpinner: Spinner
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var payloadInput: EditText
    private lateinit var payloadContainer: LinearLayout
    private lateinit var connectBtn: Button
    private lateinit var statusText: TextView
    private lateinit var logView: TextView
    private lateinit var errorText: TextView
    private lateinit var logScrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        requestBatteryExemption()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#0B0F19"))
        }

        // Título de la app
        val title = TextView(this).apply {
            text = "BHTTP Tunnel"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0A5CFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        // Estado en texto
        statusText = TextView(this).apply {
            text = "Estado: Desconectado"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        root.addView(statusText)

        // Mensaje de Error de Validación
        errorText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#EF4444"))
            visibility = View.GONE
            setPadding(0, 0, 0, 16)
        }
        root.addView(errorText)

        // Selector de Modo
        val modeLabel = label("Modo de Conexión:")
        root.addView(modeLabel)

        val modes = listOf("BHTTP (v2)", "WS (Custom)")
        val savedMode = prefs.getString("mode", "BHTTP") ?: "BHTTP"
        val initialModeIndex = if (savedMode.startsWith("WS")) 1 else 0

        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes)
            setSelection(initialModeIndex)
            setBackgroundColor(Color.parseColor("#1E293B"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val isWs = pos == 1
                    payloadContainer.visibility = if (isWs) View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        root.addView(modeSpinner)

        // Campos: Vacíos por defecto
        val savedHost = prefs.getString("host", "") ?: ""
        val savedPort = prefs.getString("port", "") ?: ""
        val savedUser = prefs.getString("user", "") ?: ""
        val savedPass = prefs.getString("pass", "") ?: ""
        val savedPayload = prefs.getString("payload", "") ?: ""

        hostInput = createField("Servidor (IP o dominio)", savedHost)
        portInput = createField("Puerto (ej: 443 o 80)", savedPort, isNum = true)
        userInput = createField("Usuario SSH", savedUser)
        passInput = createField("Contraseña SSH", savedPass, isPwd = true)

        root.addView(label("Servidor:"))
        root.addView(hostInput)

        root.addView(label("Puerto:"))
        root.addView(portInput)

        root.addView(label("Usuario SSH:"))
        root.addView(userInput)

        root.addView(label("Contraseña SSH:"))
        root.addView(passInput)

        // Contenedor de Payload (Visible SOLO en modo WS)
        payloadContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initialModeIndex == 1) View.VISIBLE else View.GONE
        }
        payloadInput = createField("Payload (requerido en WS)", savedPayload)
        payloadContainer.addView(label("Payload:"))
        payloadContainer.addView(payloadInput)
        root.addView(payloadContainer)

        // Botón CONECTAR / DESCONECTAR
        connectBtn = Button(this).apply {
            text = "CONECTAR"
            setBackgroundColor(Color.parseColor("#0A5CFF"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 15f
            setPadding(0, 32, 0, 32)
            setOnClickListener { toggleConnection() }
        }
        root.addView(connectBtn)

        // Registro real de eventos
        val logHeader = TextView(this).apply {
            text = "Registro de Eventos:"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        }
        root.addView(logHeader)

        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            setBackgroundColor(Color.parseColor("#020617"))
            setPadding(24, 24, 24, 24)
            typeface = Typeface.MONOSPACE
        }

        logScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#020617"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 350
            )
            addView(logView)
        }
        root.addView(logScrollView)

        val mainScroll = ScrollView(this).apply {
            addView(root)
        }
        setContentView(mainScroll)

        Bus.listener = {
            runOnUiThread { updateUiState() }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 16, 0, 4)
        }
    }

    private fun createField(hint: String, value: String, isPwd: Boolean = false, isNum: Boolean = false): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 20, 24, 20)
            setSingleLine(true)
            if (isPwd) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (isNum) inputType = InputType.TYPE_CLASS_NUMBER
        }
    }

    private fun validateInputs(): Boolean {
        errorText.visibility = View.GONE
        errorText.text = ""

        val host = hostInput.text.toString().trim()
        val portStr = portInput.text.toString().trim()
        val user = userInput.text.toString().trim()
        val pass = passInput.text.toString()
        val isWs = modeSpinner.selectedItemPosition == 1
        val payload = payloadInput.text.toString().trim()

        if (host.isEmpty()) {
            showError("El campo 'Servidor' es obligatorio.")
            hostInput.requestFocus()
            return false
        }

        // Validación simple de host / dominio o IP
        val hostPattern = Regex("^([a-zA-Z0-9.-]+)$")
        if (!hostPattern.matches(host)) {
            showError("El servidor debe ser una IP o dominio válido.")
            hostInput.requestFocus()
            return false
        }

        if (portStr.isEmpty()) {
            showError("El campo 'Puerto' es obligatorio.")
            portInput.requestFocus()
            return false
        }

        val port = portStr.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            showError("El puerto debe ser un número entre 1 y 65535.")
            portInput.requestFocus()
            return false
        }

        if (user.isEmpty()) {
            showError("El campo 'Usuario SSH' es obligatorio.")
            userInput.requestFocus()
            return false
        }

        if (pass.isEmpty()) {
            showError("El campo 'Contraseña SSH' es obligatorio.")
            passInput.requestFocus()
            return false
        }

        if (isWs && payload.isEmpty()) {
            showError("El campo 'Payload' no puede estar vacío en modo WS.")
            payloadInput.requestFocus()
            return false
        }

        return true
    }

    private fun showError(msg: String) {
        errorText.text = "⚠️ $msg"
        errorText.visibility = View.VISIBLE
    }

    private fun toggleConnection() {
        if (Bus.isRunning) {
            startService(Intent(this, TunnelVpnService::class.java).setAction("STOP"))
            return
        }

        if (!validateInputs()) return

        val isWs = modeSpinner.selectedItemPosition == 1
        val modeStr = if (isWs) "WS" else "BHTTP"

        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("mode", modeStr)
            .putString("host", hostInput.text.toString().trim())
            .putString("port", portInput.text.toString().trim())
            .putString("user", userInput.text.toString().trim())
            .putString("pass", passInput.text.toString())
            .putString("payload", payloadInput.text.toString().trim())
            .apply()

        Bus.clearLogs()
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, 100)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        startService(Intent(this, TunnelVpnService::class.java))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            startVpnService()
        } else if (requestCode == 100) {
            showError("Permiso VPN cancelado por el usuario.")
        }
    }

    private fun updateUiState() {
        val s = Bus.state
        statusText.text = "Estado: ${Bus.statusDetail}"

        when (s) {
            VpnStatus.CONNECTED -> {
                statusText.setTextColor(Color.parseColor("#10B981"))
                connectBtn.text = "DESCONECTAR"
                connectBtn.setBackgroundColor(Color.parseColor("#EF4444"))
                setInputsEnabled(false)
            }
            VpnStatus.CONNECTING, VpnStatus.RECONNECTING -> {
                statusText.setTextColor(Color.parseColor("#F59E0B"))
                connectBtn.text = "DESCONECTAR"
                connectBtn.setBackgroundColor(Color.parseColor("#EF4444"))
                setInputsEnabled(false)
            }
            VpnStatus.ERROR -> {
                statusText.setTextColor(Color.parseColor("#EF4444"))
                connectBtn.text = "CONECTAR"
                connectBtn.setBackgroundColor(Color.parseColor("#0A5CFF"))
                setInputsEnabled(true)
            }
            VpnStatus.DISCONNECTED -> {
                statusText.setTextColor(Color.parseColor("#94A3B8"))
                connectBtn.text = "CONECTAR"
                connectBtn.setBackgroundColor(Color.parseColor("#0A5CFF"))
                setInputsEnabled(true)
            }
        }

        // Actualizar logs
        val logs = Bus.logs
        logView.text = logs.joinToString("\n")
        logScrollView.post {
            logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        modeSpinner.isEnabled = enabled
        hostInput.isEnabled = enabled
        portInput.isEnabled = enabled
        userInput.isEnabled = enabled
        passInput.isEnabled = enabled
        payloadInput.isEnabled = enabled
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }
}
