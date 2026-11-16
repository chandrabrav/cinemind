package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.captions.Caption
import com.cinemind.core.domain.color.ColorGrade
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectTimeline
import com.cinemind.core.domain.model.Track
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.text.TextKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPlanTest {

    private val sources = listOf(
        PlanSource("a", isVideo = true, durationUs = 12_000_000),
        PlanSource("b", isVideo = true, durationUs = 8_000_000),
        PlanSource("p", isVideo = false, durationUs = 0),
    )
    private val project = Project(id = "src", name = "Goa trip")

    private fun brief(style: EditStyle, prompt: String = "", music: Boolean = false, speech: Boolean = false) = DirectorBrief(
        style = style, pacing = Pacing.BALANCED, prompt = prompt, projectName = "Goa trip",
        transition = ClipTransition.FADE, transitionMs = 500, openingMs = 500, endingMs = 800,
        hasMusic = music, hasSpeech = speech, filmedAt = null,
    )

    private fun shots() = listOf(
        MeasuredShot(PlanShot(1, 0.0, 3.0), 0.8f, brightness = 0.25f, motion = 0.3f),
        MeasuredShot(PlanShot(1, 4.0, 7.0), 0.9f, brightness = 0.5f, motion = 0.9f),
        MeasuredShot(PlanShot(2, 1.0, 4.0), 0.7f, brightness = 0.75f, motion = 0.6f),
        MeasuredShot(PlanShot(3, 0.0, 2.0), 0.6f, brightness = 0.5f, motion = 0f),
    )

    private fun run(plan: EditPlan, ground: String = "Goa trip", sourceTl: ProjectTimeline? = null, caps: List<Caption> = emptyList()): BuiltEdit {
        val v = EditPlanValidator.validate(plan, sources, ground, hasCaptions = caps.isNotEmpty())
        assertTrue(v.usable)
        return EditPlanExecutor.execute(v, sources, project, "Goa trip · AI edit", sourceTl, caps)
    }

    @Test
    fun directorPlansMoreThanCuts() {
        val plan = CreativeDirector.direct(shots(), brief(EditStyle.CINEMATIC, "cinematic travel reel"))
        val built = run(plan)
        val clips = built.timeline.clips
        // Real cuts…
        assertEquals(4, clips.size)
        // …plus a colour look on every clip, exposure matched on dark/bright shots,
        val grades = clips.map { ColorGrade.decode(it.grade) }
        assertTrue(grades.all { it.lutId?.startsWith("builtin:") == true })
        assertTrue(grades[0].exposure > grades[1].exposure)
        assertTrue(grades[2].exposure < grades[1].exposure)
        // …a speed ramp on one strong moment,
        assertEquals(1, clips.count { it.speedCurve.isNotEmpty() })
        // …transitions only where the source changes,
        assertEquals(ClipTransition.CUT.name, clips[1].transitionInType)
        assertEquals(ClipTransition.FADE.name, clips[2].transitionInType)
        // …titles with animation and an end card on the real end,
        assertTrue(built.texts.size >= 2)
        val end = built.texts.last()
        assertEquals(TimelineEditing.durationUs(clips), end.endUs)
        // …and a caption style.
        assertEquals("cinematic", built.captionPreset)
        assertTrue(built.steps.any { it.area == "Colour" && it.status == StepStatus.APPLIED })
    }

    @Test
    fun gymGetsEnergyAndAsksInsteadOfGuessingExercises() {
        val plan = CreativeDirector.direct(shots(), brief(EditStyle.GYM, "energetic gym motivation reel"))
        assertEquals("punch", plan.grade?.look)
        assertTrue(plan.sounds.any { it.name == "IMPACT" })
        assertTrue(plan.shots.any { it.ramp == "HERO" })
        assertTrue(plan.questions.any { "exercises" in it })
        assertEquals("reels", plan.captionPreset)
    }

    @Test
    fun ungroundedFactsAreNotAddedButAsked() {
        val plan = EditPlan(
            shots = listOf(PlanShot(1, 0.0, 4.0)),
            texts = listOf(
                PlanText("LOWER_THIRD", "Baga Beach, Goa", 0.5, 2.0, claim = true),
                PlanText("LABEL", "12 reps", 1.0, 2.0, claim = true),
                PlanText("TITLE", "Goa", 0.0, 2.0, claim = true),
                PlanText("TITLE", "Golden days", 0.0, 2.0),
            ),
        )
        val v = EditPlanValidator.validate(plan, sources, ground = "My Goa trip")
        assertEquals(listOf("Goa", "Golden days"), v.plan.texts.map { it.text })
        assertEquals(2, v.plan.questions.size)
        assertEquals(2, v.steps.count { it.area == "Text" && it.status == StepStatus.SKIPPED })
    }

    @Test
    fun invalidValuesAreFixedAndReported() {
        val plan = EditPlan(
            shots = listOf(
                PlanShot(9, 0.0, 3.0),                                      // no such source
                PlanShot(1, 10.0, 30.0, speed = 20.0, ramp = "WARP", transitionIn = "STAR_WIPE"), // clamp + unknowns
                PlanShot(2, 7.9, 8.2),                                       // too short after clamping
                PlanShot(3, 0.0, 60.0),                                      // photo: capped
            ),
            grade = PlanGrade(look = "unknown_look", saturation = 3.0, contrast = -2.0),
            captionPreset = "comic-sans",
            sounds = listOf(PlanSound("AIRHORN", 1.0), PlanSound("whoosh", 1.0)),
        )
        val v = EditPlanValidator.validate(plan, sources, "")
        val s = v.plan.shots
        assertEquals(2, s.size)
        assertEquals(12.0, s[0].toSeconds, 0.0)
        assertEquals(4.0, s[0].speed, 0.0)
        assertEquals("", s[0].ramp)
        assertEquals("CUT", s[0].transitionIn)
        assertEquals(6.0, s[1].toSeconds - s[1].fromSeconds, 0.0)
        assertEquals("", v.plan.grade!!.look)
        assertEquals(0.4, v.plan.grade!!.saturation, 0.0)
        assertEquals("", v.plan.captionPreset)
        assertEquals(listOf("WHOOSH"), v.plan.sounds.map { it.name })
        assertTrue(v.steps.count { it.status != StepStatus.APPLIED } >= 6)
    }

    @Test
    fun nothingUsableIsNotClaimedAsAnEdit() {
        val v = EditPlanValidator.validate(EditPlan(shots = listOf(PlanShot(7, 0.0, 2.0))), sources, "")
        assertFalse(v.usable)
        assertTrue(v.steps.any { it.status == StepStatus.SKIPPED })
    }

    @Test
    fun captionsFollowTheirFootageIntoTheNewCut() {
        val track = Track(id = "t", projectId = "src", type = TrackType.VIDEO, index = 0, name = "V")
        val old = TimelineEditing.relayout(listOf(
            Clip(id = "c1", trackId = "t", assetId = "a", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = 12_000_000),
            Clip(id = "c2", trackId = "t", assetId = "b", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = 8_000_000),
        ))
        val tl = ProjectTimeline(project, listOf(track), old)
        val caps = listOf(
            Caption(projectId = "src", startUs = 1_000_000, endUs = 2_000_000, text = "kept from a"),
            Caption(projectId = "src", startUs = 13_000_000, endUs = 14_000_000, text = "from b, played first"),
            Caption(projectId = "src", startUs = 9_000_000, endUs = 10_000_000, text = "cut out"),
        )
        // New cut: b 0.5–3 s first, then a 0.5–4 s.
        val plan = EditPlan(shots = listOf(PlanShot(2, 0.5, 3.0), PlanShot(1, 0.5, 4.0)), captionPreset = "travel")
        val built = run(plan, sourceTl = tl, caps = caps)
        assertEquals(listOf("from b, played first", "kept from a"), built.captions.map { it.text })
        assertEquals(500_000L, built.captions[0].startUs)       // 1.0 s into b, b starts at 0.5 s
        assertEquals(3_000_000L, built.captions[1].startUs)     // 2.5 s (b) + 0.5 s into a
        assertTrue(built.steps.any { it.area == "Captions" && "2 of 3" in it.text })
    }

    @Test
    fun jsonRoundTripThroughTheParser() {
        val reply = """Here you go: {"summary":"Warm travel reel","shots":[{"source":1,"fromSeconds":0.5,"toSeconds":3,"speed":1,"ramp":"","transitionIn":"CUT","transitionSeconds":0,"exposure":0.1,"note":"hook"},
            {"source":2,"fromSeconds":1,"toSeconds":4,"speed":1,"ramp":"CINEMATIC","transitionIn":"DISSOLVE","transitionSeconds":0.6,"exposure":0,"note":""}],
            "openingSeconds":0.5,"endingSeconds":1,"grade":{"look":"golden_hour","intensity":0.7,"exposure":0,"contrast":0.1,"saturation":0,"temperature":0.1,"tint":0,"highlights":-0.1,"shadows":0},
            "texts":[{"role":"TITLE","text":"Golden days","atSeconds":0.2,"seconds":2,"preset":"CINEMA","animIn":"FADE","animOut":"FADE","claim":false},
                     {"role":"STICKER","text":"☀️","atSeconds":3,"seconds":1.5,"preset":"","animIn":"","animOut":"","claim":false}],
            "captionPreset":"travel","audio":{"musicVolume":0.6,"musicFadeInSeconds":1,"musicFadeOutSeconds":2,"originalVolume":-1,"musicMood":""},
            "sounds":[{"name":"WHOOSH","atSeconds":2.5}],"questions":["Which beach is this?"],"decisions":["Opens on the brightest shot"]} thanks"""
        val plan = EditPlanJson.parse(reply)
        assertNotNull(plan)
        val built = run(plan!!)
        assertEquals(2, built.timeline.clips.size)
        assertEquals(ClipTransition.DISSOLVE.name, built.timeline.clips[1].transitionInType)
        assertTrue(built.timeline.clips[1].speedCurve.isNotEmpty())
        assertEquals(TextKind.STICKER.name, built.texts[1].kind)
        assertEquals(1, built.sounds.size)
        assertEquals("Which beach is this?", plan.questions.single())
        assertNull(EditPlanJson.parse("no json here"))
        // Schema lists every field as required (strict structured output).
        @Suppress("UNCHECKED_CAST")
        val props = EditPlanJson.schema["properties"] as Map<String, Any>
        assertEquals(props.keys.toList(), EditPlanJson.schema["required"])
    }

    @Test
    fun groundingIsStrictAboutNumbersAndNames() {
        assertTrue(EditPlanValidator.isGrounded("Day 2 in Manali", "our trip to manali, day 2"))
        assertFalse(EditPlanValidator.isGrounded("Day 3 in Manali", "our trip to manali, day 2"))
        assertFalse(EditPlanValidator.isGrounded("Solang Valley", "our trip to manali"))
    }
}
