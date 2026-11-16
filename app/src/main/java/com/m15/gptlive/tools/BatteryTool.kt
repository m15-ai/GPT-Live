package com.m15.gptlive.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject

/**
 * Demo tool: reports the phone's battery level and charging state.
 *
 * Ask "how much battery do I have left?" and GPT-Live delegates to the
 * backend model, which calls this function and speaks the answer.
 */
class BatteryTool(private val context: Context) : LiveTool {

    override val name = "get_battery_status"

    override val description =
        "Get the phone's current battery level as a percentage and whether it is charging. " +
        "Call this whenever the user asks about battery, charge, or power remaining."

    override suspend fun execute(arguments: JSONObject): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        // Sticky broadcast: registering with a null receiver just returns the last value.
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val statusCode = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val charging = statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusCode == BatteryManager.BATTERY_STATUS_FULL
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }

        return JSONObject()
            .put("level_percent", level)
            .put("charging", charging)
            .put("power_source", source)
            .toString()
    }
}
