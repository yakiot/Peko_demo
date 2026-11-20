package com.yakiot.peko

object AppConstant {
    // BLE address: d8:13:2a:39:f3:4a
    const val BLE_MAC_ADDRESS = "d8:13:2a:39:f3:4a"
    const val SERVICE_UUID = "7e140001-8c55-4a82-b345-51fa952a6a01"
    const val TELEMETRY_UUID = "7e140002-8c55-4a82-b345-51fa952a6a01"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    const val CONNECTION_TIMEOUT_MS = 15_000L
    const val RECONNECT_DELAY_MS = 5_000L
}
