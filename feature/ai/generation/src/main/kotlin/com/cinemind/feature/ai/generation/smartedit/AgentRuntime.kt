package com.cinemind.feature.ai.generation.smartedit

import android.content.Context
import com.cinemind.core.domain.agents.CineMindModel
import com.cinemind.core.domain.agents.ModelDescriptor
import com.cinemind.core.domain.agents.ModelRequest
import com.cinemind.core.domain.agents.ModelSkill
import com.cinemind.core.domain.agents.Policy
import com.cinemind.core.domain.agents.ResourceBudget
import com.cinemind.core.domain.agents.ResourcePolicy
import com.cinemind.core.domain.agents.RuntimeKind
import com.cinemind.feature.ai.cloud.CloudKeyStore
import com.cinemind.feature.ai.llm.ondevice.AiPreferences
import com.cinemind.feature.ai.llm.ondevice.DeviceResources
import com.cinemind.feature.ai.llm.ondevice.ModelSpec
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * The live model registry and adapters for the decision engine: what is installed on the phone,
 * which cloud service the user connected (if any), the phone's current resources and the user's
 * privacy choices. Model choice is data here, not code paths spread through the app.
 */
class AgentRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llm: OnDeviceLlm,
    private val prefs: AiPreferences,
    private val keys: CloudKeyStore,
) {
    fun budget(): ResourceBudget = ResourcePolicy.budget(DeviceResources.snapshot(context))

    fun policy(): Policy = keys.settings.value.let { Policy(it.mode, it.consented, useOnDeviceAi = prefs.useAiInSmartEdit) }

    /** Every model the engine may choose from right now. */
    fun models(): List<ModelDescriptor> = local().map { it.descriptor } + listOfNotNull(cloud()?.descriptor)

    fun model(id: String?): CineMindModel? = (local() + listOfNotNull(cloud())).firstOrNull { it.descriptor.id == id }

    private fun local(): List<LocalModel> {
        val planner = llm.reasoningModel()
        return llm.installedModels()
            .filter { !it.reasoning || it.id == planner?.id }
            .map { LocalModel(it) }
    }

    private fun cloud(): CloudModel? = keys.settings.value.takeIf { it.hasKey }?.let { CloudModel(it.engineLabel) }

    /** Gemma or Spark through LiteRT-LM (one engine in memory; the runtime swaps as needed). */
    private inner class LocalModel(private val spec: ModelSpec) : CineMindModel {
        override val descriptor = ModelDescriptor(
            id = spec.id,
            kind = RuntimeKind.ON_DEVICE,
            skills = buildSet {
                add(ModelSkill.TEXT_REASONING)
                add(ModelSkill.STRUCTURED_OUTPUT)
                if (spec.supportsImages) add(ModelSkill.VISION)
                if (spec.supportsAudio) add(ModelSkill.AUDIO)
            },
            // Room for activations and the KV cache on top of the file.
            memoryMb = (spec.sizeBytes / (1024 * 1024) * 1.25).toInt(),
            tokensPerSecond = spec.tokensPerSecond,
            quality = QUALITY[spec.id] ?: 2,
            available = true,
        )

        override suspend fun load() {
            llm.ensureLoaded(spec = spec)
        }

        override suspend fun infer(request: ModelRequest): String {
            val schemaText = request.schema?.let { "\nAnswer with one JSON object only, matching this JSON schema:\n" + JSONObject(it).toString() } ?: ""
            val prompt = listOf(request.system + schemaText, request.prompt).filter { it.isNotBlank() }.joinToString("\n\n")
            return llm.ask(
                prompt,
                imageFile = request.imagePath?.let(::File),
                audioFile = request.audioPath?.let(::File),
                maxOutputTokens = request.maxTokens,
                reasoning = spec.reasoning,
                spec = spec,
            )
        }

        override suspend fun unload() = llm.unload()
    }

    /** The user's connected cloud service: text only (media never goes through this adapter). */
    private inner class CloudModel(label: String) : CineMindModel {
        override val descriptor = ModelDescriptor(label, RuntimeKind.CLOUD, setOf(ModelSkill.TEXT_REASONING, ModelSkill.STRUCTURED_OUTPUT), 0, 50f, 5, true)
        override suspend fun load() = Unit
        override suspend fun infer(request: ModelRequest): String {
            require(request.imagePath == null && request.audioPath == null) { "Media is never sent to the cloud" }
            val client = keys.client() ?: error("Cloud AI isn't set up")
            return try {
                client.ask(request.system, request.prompt, request.schema ?: mapOf("type" to "object"), request.maxTokens.toLong(), request.deep)
            } finally {
                client.close()
            }
        }
        override suspend fun unload() = Unit
    }

    private companion object {
        /** Expected plan quality for this app (1..5); revisit with the on-phone benchmark results. */
        // The owner chose Spark X2.5 4B as the planner: ranked high enough that its slower speed
        // doesn't hand planning back to Gemma. Only the chosen planner is ever offered.
        val QUALITY = mapOf("gemma-4-e2b" to 2, "gemma-4-e4b" to 3, "spark-x2.6-1.7b" to 4, "spark-x2.5-4b" to 6)
    }
}
