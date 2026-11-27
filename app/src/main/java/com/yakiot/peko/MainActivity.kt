package com.yakiot.peko

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var ble: CanBleClient
    private lateinit var status: TextView
    private lateinit var engine: TextView
    private lateinit var speed: MeterView
    private lateinit var rpm: MeterView
    private val readouts = mutableMapOf<String, TextView>()
    private var foreground = false
    private var deviceName = ""
    private var deviceAddress = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = CanBleClient(this, { message, connected ->
            status.text = message
            status.setTextColor(if (connected) Color.rgb(27, 139, 101) else Color.rgb(165, 77, 28))
            if (!connected) clearTelemetry()
        }, { key, value ->
            when (key) {
                "speed" -> speed.value = value.toFloat()
                "rpm" -> {
                    rpm.value = value.toFloat()
                    engine.text = if (rpm.value!! > 0) "Engine running (RPM)" else "Engine stopped (RPM)"
                }
                else -> readouts[key]?.let { it.text = "${it.tag}: $value" }
            }
        })
        showDevicePicker()
    }

    private fun showDevicePicker() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
            setBackgroundColor(Color.rgb(246, 248, 247))
        }
        fun text(value: String, size: Float = 18f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(20, 37, 31))
            setPadding(0, 10.dp, 0, 10.dp)
        }
        fun deviceButton(name: String, address: String) = Button(this).apply {
            text = if (address.isBlank()) "$name — address not configured" else name
            textSize = 18f
            setOnClickListener { selectDevice(name, address) }
        }
        content.addView(text("Peko — Select Device", 26f))
        content.addView(text("Choose which ESP32 to connect to:", 15f))
        content.addView(text("CAN: ${AppConstant.CAN_BLE_MAC_ADDRESS.ifBlank { "not configured" }}", 13f))
        content.addView(deviceButton("CAN", AppConstant.CAN_BLE_MAC_ADDRESS))
        content.addView(text("GPS: ${AppConstant.GPS_BLE_MAC_ADDRESS.ifBlank { "not configured" }}", 13f))
        content.addView(deviceButton("GPS", AppConstant.GPS_BLE_MAC_ADDRESS))
        setContentView(content)
    }

    private fun selectDevice(name: String, address: String) {
        deviceName = name
        deviceAddress = address
        showDashboard()
        connectIfAllowed()
    }

    private fun showDashboard() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
            setBackgroundColor(Color.rgb(246, 248, 247))
        }
        fun text(value: String, size: Float = 18f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(20, 37, 31))
            setPadding(0, 10.dp, 0, 10.dp)
        }
        readouts.clear()
        content.addView(text("ESP32_CAN Dashboard", 26f))
        status = text("Disconnected", 15f)
        content.addView(status)
        content.addView(text("$deviceName · ${deviceAddress.ifBlank { "Set address in AppConstant.kt" }}", 13f))
        val meters = LinearLayout(this)
        speed = MeterView(this, "Speed", "km/h", 200f)
        rpm = MeterView(this, "RPM", "rpm", 8000f)
        meters.addView(speed, LinearLayout.LayoutParams(0, 210.dp, 1f))
        meters.addView(rpm, LinearLayout.LayoutParams(0, 210.dp, 1f))
        content.addView(meters)
        engine = text("Engine status: —")
        content.addView(engine)
        linkedMapOf("odo" to "Odometer (km)", "hours" to "Engine hours (h)",
            "fuel" to "Fuel level (%)", "thr" to "Throttle (%)", "pedal" to "Accel pedal (%)",
            "load" to "Engine load (%)", "keyv" to "Keyswitch voltage (V)",
            "g" to "Position (lat, lon)", "v" to "VIN").forEach { (key, label) ->
            val view = text("$label: —")
            view.tag = label
            readouts[key] = view
            content.addView(view)
        }
        content.addView(text("Values appear only when received from firmware. GPS position is shown only while the module reports a valid fix.", 13f))
        content.addView(Button(this).apply {
            text = "Reconnect"
            setOnClickListener { ble.stop(); connectIfAllowed() }
        })
        content.addView(Button(this).apply {
            text = "Change device"
            setOnClickListener { ble.stop(); showDevicePicker() }
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun clearTelemetry() {
        speed.value = null
        rpm.value = null
        engine.text = "Engine status: —"
        readouts.values.forEach { it.text = "${it.tag}: —" }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        connectIfAllowed()
    }

    private fun connectIfAllowed() {
        if (deviceAddress.isBlank()) return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else {
            ble.start(deviceAddress)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (foreground && deviceAddress.isNotBlank()) ble.start(deviceAddress)
        } else {
            status.text = "Bluetooth permission denied. Grant Nearby devices access to connect."
        }
    }

    override fun onStop() {
        foreground = false
        ble.stop()
        clearTelemetry()
        super.onStop()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

private class MeterView(context: android.content.Context, private val label: String,
                        private val unit: String, private val maximum: Float) : View(context) {
    var value: Float? = null
        set(newValue) { field = newValue; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = (minOf(width.toFloat(), height - 32 * density) / 2 - 12 * density).coerceAtLeast(1f)
        val cx = width / 2f
        val cy = radius + 12 * density
        val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 10 * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(219, 229, 223)
        canvas.drawArc(bounds, 135f, 270f, false, paint)
        paint.color = Color.rgb(27, 139, 101)
        canvas.drawArc(bounds, 135f, 270 * ((value ?: 0f) / maximum).coerceIn(0f, 1f), false, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(20, 37, 31)
        paint.textSize = 30 * density
        canvas.drawText(value?.let { String.format(Locale.US, "%.0f", it) } ?: "—", cx, cy, paint)
        paint.textSize = 14 * density
        canvas.drawText(unit, cx, cy + 24 * density, paint)
        canvas.drawText(label, cx, height - 8 * density, paint)
    }
}
