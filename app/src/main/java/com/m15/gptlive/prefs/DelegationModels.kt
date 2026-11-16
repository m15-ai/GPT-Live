package com.m15.gptlive.prefs

/** Responses API models offered as the delegation backend. Any model id can also be typed in. */
data class DelegationModel(val id: String, val detail: String)

object DelegationModels {
    /** The model the GPT-Live delegation guide recommends starting with. */
    const val DEFAULT = "gpt-6-luna"

    val suggested: List<DelegationModel> = listOf(
        DelegationModel("gpt-6-luna", "Fast and cheap; recommended for voice tool calling"),
        DelegationModel("gpt-6-sol", "Stronger reasoning for complex backend tasks"),
        DelegationModel("gpt-6-astra", "Most capable; slowest and priciest"),
    )

    /**
     * Older ids that earlier builds of this app suggested. They still work
     * but are on the deprecations page; the UI nudges users off them.
     */
    val retiring: Map<String, String> = mapOf(
        "gpt-4o" to "shuts down 2027-01-14",
        "gpt-4.1-mini" to "shuts down 2027-01-14",
        "gpt-5-mini" to "shuts down 2026-12-11",
    )
}
