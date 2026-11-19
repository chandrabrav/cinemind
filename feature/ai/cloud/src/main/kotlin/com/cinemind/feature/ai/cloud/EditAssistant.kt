package com.cinemind.feature.ai.cloud

import com.cinemind.core.domain.assistant.Assistant
import com.cinemind.core.domain.assistant.AssistantPlan
import com.cinemind.core.domain.assistant.ProjectBrief
import com.cinemind.core.domain.aiedit.EditPlan
import com.cinemind.core.domain.aiedit.EditPlanJson
import com.cinemind.core.domain.aiedit.PlanRequest
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The editor's assistant. With the user's own Claude key (and their OK to send the text summary)
 * it asks Claude; otherwise the on-device model. Only text is sent: the request and a short
 * project summary (clip lengths, titles, start of captions) — never pictures, video or sound.
 */
@Singleton
class EditAssistant @Inject constructor(
    private val keys: CloudKeyStore,
    private val onDevice: OnDeviceLlm,
) {
    /** Cloud only when the AI mode allows it and the user added a key and agreed to send text. */
    val usesCloud: Boolean get() = keys.settings.value.let {
        com.cinemind.core.domain.aigen.AiRouter.cloudPlanningAllowed(it.mode, it.hasKey && it.consented)
    }

    val engineName: String get() = if (usesCloud) keys.settings.value.engineLabel else "On-device AI"

    suspend fun plan(brief: ProjectBrief, request: String): AssistantPlan {
        val user = Assistant.userPrompt(brief, request)
        val text = if (usesCloud) {
            val client = keys.client() ?: throw CloudAiException("Add a cloud AI key and model in AI models → Cloud.")
            try {
                client.ask(Assistant.systemPrompt, user, Assistant.schema)
            } finally {
                client.close()
            }
        } else {
            if (onDevice.textModel() == null) {
                throw CloudAiException("Download an on-device model or add a cloud AI key (both in AI models).")
            }
            onDevice.ask(
                Assistant.systemPrompt +
                    "\nAnswer with JSON only, like {\"reply\": \"...\", \"actions\": [{\"type\": \"...\", \"name\": \"\", \"text\": \"\", \"value\": 0, \"clip\": 0, \"atSeconds\": 0}]}\n\n" +
                    user,
                maxOutputTokens = 700,
                // The planning model (Spark) when installed: it reasons about edits better.
                reasoning = true,
            )
        }
        return Assistant.parse(text, brief.clips.size)
    }

    /**
     * A complete creative edit plan from Claude for Smart Edit, or null when cloud AI isn't set up
     * (no key or no consent). Only the text footage report is sent: measurements, on-device
     * descriptions and caption text — never pictures, video or sound. Throws [CloudAiException]
     * with a readable message on any provider problem.
     */
    suspend fun planEdit(request: PlanRequest): EditPlan? {
        if (!usesCloud) return null
        val client = keys.client() ?: return null
        val text = try {
            client.ask(EditPlanJson.systemPrompt, EditPlanJson.userPrompt(request), EditPlanJson.schema, 12_000L, deep = keys.settings.value.mode == com.cinemind.core.domain.aigen.AiMode.CLOUD)
        } finally {
            client.close()
        }
        return EditPlanJson.parse(text) ?: throw CloudAiException("The cloud plan couldn't be read. Try again.")
    }

    /** A tiny request to check the saved key works; returns Claude's reply. */
    suspend fun test(): String {
        val client = keys.client() ?: throw CloudAiException(if (keys.apiKey() == null) "No key saved" else "Choose a model first")
        return try {
            val brief = ProjectBrief("Test", 10.0, listOf("video 10.0 s"), emptyList(), "", 0f, false)
            val reply = client.ask(Assistant.systemPrompt, Assistant.userPrompt(brief, "Say hello in five words. No actions."), Assistant.schema, 1_000L)
            Assistant.parse(reply, 1).reply
        } finally {
            client.close()
        }
    }
}
