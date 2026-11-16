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
    val usesCloud: Boolean get() = keys.settings.value.let { it.hasKey && it.consented }

    val engineName: String get() = if (usesCloud) keys.settings.value.model.label else "On-device AI"

    suspend fun plan(brief: ProjectBrief, request: String): AssistantPlan {
        val user = Assistant.userPrompt(brief, request)
        val text = if (usesCloud) {
            val key = keys.apiKey() ?: throw CloudAiException("Add your Claude API key in Settings → Cloud AI.")
            val client = ClaudeClient(key, keys.settings.value.model)
            try {
                client.ask(Assistant.systemPrompt, user, Assistant.schema)
            } finally {
                client.close()
            }
        } else {
            if (onDevice.installedModel() == null) {
                throw CloudAiException("Download an on-device AI model (Settings → AI models) or add a Claude API key (Settings → Cloud AI).")
            }
            onDevice.ask(
                Assistant.systemPrompt +
                    "\nAnswer with JSON only, like {\"reply\": \"...\", \"actions\": [{\"type\": \"...\", \"name\": \"\", \"text\": \"\", \"value\": 0, \"clip\": 0, \"atSeconds\": 0}]}\n\n" +
                    user,
                maxOutputTokens = 700,
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
        val key = keys.apiKey() ?: return null
        val client = ClaudeClient(key, keys.settings.value.model)
        val text = try {
            client.ask(EditPlanJson.systemPrompt, EditPlanJson.userPrompt(request), EditPlanJson.schema, 12_000L, com.anthropic.models.messages.OutputConfig.Effort.MEDIUM)
        } finally {
            client.close()
        }
        return EditPlanJson.parse(text) ?: throw CloudAiException("Claude's plan couldn't be read. Try again.")
    }

    /** A tiny request to check the saved key works; returns Claude's reply. */
    suspend fun test(): String {
        val key = keys.apiKey() ?: throw CloudAiException("No key saved")
        val client = ClaudeClient(key, keys.settings.value.model)
        return try {
            val brief = ProjectBrief("Test", 10.0, listOf("video 10.0 s"), emptyList(), "", 0f, false)
            val reply = client.ask(Assistant.systemPrompt, Assistant.userPrompt(brief, "Say hello in five words. No actions."), Assistant.schema, 1_000L)
            Assistant.parse(reply, 1).reply
        } finally {
            client.close()
        }
    }
}
