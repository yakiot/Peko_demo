package com.yakiot.peko

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

@SuppressLint("MissingPermission")
class CanBleClient(context: Context, private val onStatus: (String, Boolean) -> Unit,
                   private val onMetric: (String, String) -> Unit) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val serviceId = UUID.fromString(AppConstant.SERVICE_UUID)
    private val telemetryId = UUID.fromString(AppConstant.TELEMETRY_UUID)
    private val cccdId = UUID.fromString(AppConstant.CCCD_UUID)
    private var gatt: BluetoothGatt? = null
    private var active = false
    private var address = "" 
    private val retry = Runnable { connect() }
    private val timeout = Runnable { fail("Connection timed out; retrying…") }

    fun start(deviceAddress: String) {
        if (active && address == deviceAddress) return
        active = true
        address = deviceAddress
        connect()
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        release()
    }

    private fun release() {
        val client = gatt
        gatt = null
        try {
            client?.disconnect()
        } catch (_: SecurityException) {
        } finally {
            client?.close()
        }
    }

    private fun fail(message: String) {
        handler.removeCallbacks(timeout)
        release()
        if (!active) return
        onStatus(message, false)
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, AppConstant.RECONNECT_DELAY_MS)
    }

    private fun connect() {
        if (!active) return
        val normalized = address.trim().uppercase(java.util.Locale.ROOT)
        if (normalized.isEmpty()) {
            onStatus("BLE address not configured in AppConstant", false)
            return
        }
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(normalized)) {
            onStatus("Invalid BLE address in AppConstant", false)
            return
        }
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                fail("Enable Bluetooth; retrying…")
                return
            }
            onStatus("Connecting to $normalized…", false)
            gatt = adapter.getRemoteDevice(normalized).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("Cannot connect; retrying…")
            else handler.postDelayed(timeout, AppConstant.CONNECTION_TIMEOUT_MS)
        } catch (_: SecurityException) {
            fail("Bluetooth permission required")
        } catch (_: IllegalArgumentException) {
            fail("Invalid BLE device address")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (!active || client !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Disconnected ($status); retrying…")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!client.discoverServices()) fail("Service discovery rejected; retrying…")
                }
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            handler.post {
                if (!active || client !== gatt) return@post
                val characteristic = client.getService(serviceId)?.getCharacteristic(telemetryId)
                val descriptor = characteristic?.getDescriptor(cccdId)
                if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || descriptor == null ||
                    !client.setCharacteristicNotification(characteristic, true)) {
                    fail("Telemetry service unavailable; retrying…")
                    return@post
                }
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    client.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    client.writeDescriptor(descriptor)
                }
                if (!accepted) fail("Subscription rejected; retrying…")
            }
        }

        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (!active || client !== gatt || descriptor.uuid != cccdId) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Subscription failed ($status); retrying…")
                } else {
                    handler.removeCallbacks(timeout)
                    onStatus("Connected · ESP32_CAN", true)
                }
            }
        }

        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            receive(client, characteristic.uuid, characteristic.value?.copyOf() ?: return)
        }

        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(client, characteristic.uuid, value.copyOf())
        }
    }

    private fun receive(client: BluetoothGatt, id: UUID, bytes: ByteArray) {
        handler.post {
            if (!active || client !== gatt || id != telemetryId) return@post
            val fields = bytes.toString(Charsets.US_ASCII).trim().split('=', limit = 2)
            if (fields.size != 2) return@post
            val (key, raw) = fields
            if (key == "v") {
                if (raw.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) onMetric(key, raw)
                return@post
            }
            if (key == "g") {
                val parts = raw.split(',')
                val valid = parts.size == 2 && parts.all {
                    it.toFloatOrNull()?.let(Float::isFinite) == true
                }
                if (valid) onMetric(key, raw)
                return@post
            }
            if (key !in setOf("rpm", "speed", "odo", "fuel", "thr", "pedal", "load", "keyv", "hours")) return@post
            val value = raw.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@post
            if (key in setOf("fuel", "thr", "load") && value > 100) return@post
            onMetric(key, raw)
        }
    }
}
