package com.example.sw2pad

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), Switch2Controller.Listener {

    private lateinit var controller: Switch2Controller
    private lateinit var statusView: TextView
    private lateinit var inputView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = Switch2Controller(this, this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val btn = Button(this).apply { text = "Buscar mando Switch 2"; setOnClickListener { ensurePermsThenScan() } }
        statusView = TextView(this).apply { textSize = 16f; text = "Listo para buscar" }
        inputView = TextView(this).apply { textSize = 18f; typeface = android.graphics.Typeface.MONOSPACE }
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        logScroll = ScrollView(this).apply { addView(logView) }

        root.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(statusView)
        root.addView(inputView)
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onDestroy() { controller.disconnect(); super.onDestroy() }

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermsThenScan() {
        val missing = requiredPerms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) controller.startScan() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) controller.startScan()
        else onStatus("Faltan permisos de Bluetooth")
    }

    // ---- Listener ----
    override fun onStatus(msg: String) { statusView.text = msg; onLog(msg) }

    override fun onLog(msg: String) {
        logView.append(msg + "\n")
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    override fun onState(state: Sw2.State) {
        val l = state.left; val r = state.right
        inputView.text = buildString {
            appendLine("Botones: " + (state.pressed.joinToString(" ").ifEmpty { "-" }))
            appendLine("Stick L: %+.2f %+.2f".format(l.x, l.y))
            appendLine("Stick R: %+.2f %+.2f".format(r.x, r.y))
            state.batteryMv?.let { appendLine("Batería: %d mV%s".format(it, if (state.charging == true) " (cargando)" else "")) }
        }
    }
}
