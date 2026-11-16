package com.m15.gptlive.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Demo tool with arguments and a side effect: starts a countdown timer in the
 * system clock app.
 *
 * Say "set a five minute timer for the pasta" and the backend calls this with
 * `{"seconds": 300, "label": "pasta"}`. The clock app starts the timer
 * silently (`EXTRA_SKIP_UI`) and the assistant confirms by voice. Needs only
 * the normal `com.android.alarm.permission.SET_ALARM` manifest permission.
 */
class TimerTool(private val context: Context) : LiveTool {

    override val name = "set_timer"

    override val description =
        "Start a countdown timer on the phone. Use when the user asks to set a timer, " +
        "count down, or be told when some amount of time has passed. Convert the " +
        "duration to whole seconds."

    override val parameters: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("seconds", JSONObject()
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", MAX_SECONDS)
                .put("description", "Timer length in seconds, e.g. 300 for five minutes"))
            .put("label", JSONObject()
                .put("type", "string")
                .put("description", "Short name for the timer, e.g. \"pasta\". Omit if the user gave none")))
        .put("required", JSONArray().put("seconds"))
        .put("additionalProperties", false)

    override suspend fun execute(arguments: JSONObject): String {
        val seconds = arguments.optInt("seconds", 0)
        if (seconds < 1 || seconds > MAX_SECONDS) {
            return JSONObject().put("error", "seconds must be between 1 and $MAX_SECONDS").toString()
        }
        val label = arguments.optString("label").trim().take(40)

        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // started from a non-Activity context
        if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)

        return try {
            context.startActivity(intent)
            JSONObject()
                .put("ok", true)
                .put("seconds", seconds)
                .put("label", label.ifEmpty { JSONObject.NULL })
                .toString()
        } catch (_: ActivityNotFoundException) {
            JSONObject().put("error", "no clock app on this phone can set a timer").toString()
        }
    }

    private companion object {
        /** 24 hours, the most a stock clock app accepts. */
        const val MAX_SECONDS = 24 * 60 * 60
    }
}
