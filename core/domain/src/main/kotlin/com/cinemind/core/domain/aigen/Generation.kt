package com.cinemind.core.domain.aigen

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cinemind.core.domain.model.Project
import java.util.UUID

/** How a provider is reached. Only HANDOFF exists today; API is reserved for a server-side relay. */
enum class ProviderAccess { HANDOFF, API }

/**
 * An outside service that can make media for a project. Describes facts from the provider's own
 * documentation only; nothing here is a guess about undocumented features.
 */
data class GenerationProvider(
    val id: String,
    val name: String,
    val tasks: Set<AiTask>,
    val access: ProviderAccess,
    /** Most subject photos the provider takes with a reference. */
    val maxPhotos: Int,
    val needsReference: Boolean,
    /** Android app the brief is sent to, if the provider runs inside one. */
    val appPackage: String?,
    val appName: String?,
    /** Official page that starts the provider's own workflow. */
    val launchUrl: String,
    /** Shown before anything is sent, so cost and sign-in are never a surprise. */
    val costNote: String,
)

object GenerationProviders {

    /**
     * Higgsfield KATANA: a Claude skill reached through Higgsfield's official MCP connector after
     * the user signs in. It has no public API, so CineMind prepares the brief and media, hands them
     * to Claude, and the user imports the finished video. Docs: higgsfield.ai/mcp/katana and
     * higgsfield.ai/creator-hub/help-center/integrations/how-do-i-use-katana.
     */
    val KATANA = GenerationProvider(
        id = "higgsfield-katana",
        name = "Higgsfield KATANA",
        tasks = setOf(AiTask.REFERENCE_REMAKE),
        access = ProviderAccess.HANDOFF,
        maxPhotos = 5,
        needsReference = true,
        appPackage = "com.anthropic.claude",
        appName = "Claude",
        launchUrl = "https://higgsfield.ai/katana",
        costNote = "Runs in Claude with your own Higgsfield account. KATANA needs a paid Higgsfield plan and uses its credits; Claude shows the cost before each generation. CineMind charges nothing.",
    )

    val ALL = listOf(KATANA)

    fun byId(id: String?): GenerationProvider? = ALL.firstOrNull { it.id == id }
}

/** A brief ready to hand over, or the reasons it can't be sent yet. */
data class HandoffBrief(val text: String, val problems: List<String>) {
    val ready: Boolean get() = problems.isEmpty()
}

object HandoffBriefs {

    const val MAX_INSTRUCTIONS = 1_500
    /** A reference must carry some motion to recreate; anything under a second is a mis-pick. */
    const val MIN_REFERENCE_US = 1_000_000L

    /**
     * Builds the text sent with the media. For KATANA it starts with the documented `/katana`
     * command so Claude runs the skill. Never claims anything about the result.
     */
    fun compose(
        provider: GenerationProvider,
        instructions: String,
        referenceDurationUs: Long?,
        photoCount: Int,
        aspect: String?,
    ): HandoffBrief {
        val problems = buildList {
            if (provider.needsReference && referenceDurationUs == null) add("Pick a reference video")
            if (referenceDurationUs != null && referenceDurationUs < MIN_REFERENCE_US) add("The reference is too short")
            if (photoCount > provider.maxPhotos) add("Use at most ${provider.maxPhotos} photos")
            if (instructions.length > MAX_INSTRUCTIONS) add("Keep the instructions under $MAX_INSTRUCTIONS characters")
        }
        val clean = instructions.trim().replace(Regex("\\s+"), " ")
        val text = buildString {
            if (provider.id == GenerationProviders.KATANA.id) append("/katana ")
            append("Recreate the attached reference video")
            if (photoCount > 0) append(" with the subject${if (photoCount > 1) "s" else ""} in the attached photo${if (photoCount > 1) "s" else ""}")
            append(".")
            if (clean.isNotEmpty()) {
                append(' ').append(clean)
                if (clean.last() !in ".!?") append('.')
            }
            if (!aspect.isNullOrBlank()) append(" Keep it $aspect for my edit.")
            append(" I'll import the result into my CineMind project.")
        }
        return HandoffBrief(text, problems)
    }
}

/**
 * Where a generation stands. Handoff jobs only ever reach the states CineMind can see for itself:
 * it knows it sent the brief and that the user imported a file, never what happened in between.
 */
enum class JobStatus(val terminal: Boolean) {
    PREPARED(false),
    HANDED_OFF(false),
    IMPORTED(true),
    CANCELLED(true),
    FAILED(true),
    ;

    fun canMoveTo(next: JobStatus): Boolean = when (this) {
        PREPARED -> next == HANDED_OFF || next == CANCELLED || next == FAILED
        HANDED_OFF -> next == IMPORTED || next == CANCELLED || next == HANDED_OFF
        IMPORTED, CANCELLED, FAILED -> false
    }

    companion object {
        fun from(name: String): JobStatus = entries.firstOrNull { it.name == name } ?: FAILED
    }
}

/**
 * One generation request and its provenance (schema v10, table `generation_jobs`). [outputAssetId]
 * links the imported result to the media library so the app always knows which clips were made by
 * which provider. Deleted with its project.
 */
@Entity(
    tableName = "generation_jobs",
    foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["projectId"])],
)
data class GenerationJob(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val providerId: String,
    val task: String,
    val instructions: String,
    /** The brief exactly as it was sent. */
    val brief: String,
    val referenceUri: String?,
    /** Subject photo URIs, newline separated. */
    val photoUris: String,
    val status: String = JobStatus.PREPARED.name,
    val providerJobId: String? = null,
    val outputAssetId: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    val statusEnum: JobStatus get() = JobStatus.from(status)
    val photos: List<String> get() = photoUris.split('\n').filter { it.isNotBlank() }

    /** Returns the job in [next] state, or throws if that move would misreport what happened. */
    fun moveTo(next: JobStatus, now: Long, outputAssetId: String? = this.outputAssetId, error: String? = this.error): GenerationJob {
        require(statusEnum.canMoveTo(next)) { "A job can't go from $status to ${next.name}" }
        require(next != JobStatus.IMPORTED || outputAssetId != null) { "An imported job needs the imported asset" }
        return copy(status = next.name, outputAssetId = outputAssetId, error = error, updatedAt = now)
    }
}
