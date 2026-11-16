package com.m15.gptlive.tools

import org.json.JSONObject

/**
 * A function tool exposed to GPT-Live's delegated Responses backend.
 *
 * The tool is declared in `session.start` under `delegation.responses.tools`.
 * When the backend decides to call it, the app receives the call, runs
 * [execute], and returns the output string as a `function_call_output` item.
 *
 * Keep [name] snake_case and [parameters] a JSON Schema object; both are sent
 * to the API verbatim in the flat Responses function format.
 */
interface LiveTool {
    val name: String
    val description: String

    /** JSON Schema for the arguments. Use an empty object schema for no-arg tools. */
    val parameters: JSONObject
        get() = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("required", org.json.JSONArray())
            .put("additionalProperties", false)

    /**
     * Run the tool. Runs off the main thread. The returned string is handed to
     * the model as-is, so compact JSON or a short sentence both work.
     */
    suspend fun execute(arguments: JSONObject): String

    /** Wire format for `delegation.responses.tools`. */
    fun toSpec(): JSONObject = JSONObject()
        .put("type", "function")
        .put("name", name)
        .put("description", description)
        .put("parameters", parameters)
}
