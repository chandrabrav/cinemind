# AI editing engine

The plan for turning Smart Edit from a clip trimmer into a creative editor, the audit that
started it, and the honest state of each milestone. The redesign brief is the owner's
"Complete AI editing engine overhaul" (2026-11-16).

## M1: why Smart Edit only sliced clips (audit, 2026-11-16)

I traced the path from prompt to timeline:
`EditBriefScreen` → `EditBriefViewModel.createSmartEdit` → `SmartEditor.create` →
`BriefInterpreter` / `AiAssist` → `SmartEditPlanner.plan` → `SmartEditPlanner.toTimeline` →
`ProjectRepository.createProjectWithTimeline`.

**Root cause: the plan could only express cuts.** The only plan type was
`SmartEditPlan(shots, intent, decisions)`. `EditIntent` held a style, a pacing, a length, a
shot order, one transition type and fade lengths. `toTimeline` wrote a single video track:
trimmed clips and one transition at each change of source. No path in the pipeline could add a
grade, a LUT, a title, a caption style, a speed change or ramp, a sound effect or a music level.
Those engines all exist in the editor (`ColorGrade` and the built-in looks, `TextLayer` with
presets and animations, `CaptionPresets`, `SpeedRampPreset`, `SfxSynth`, `MusicSettings`). They
were reachable only by hand, or one at a time from the editor's assistant.

Secondary findings:

| # | Finding | Effect |
|---|---|---|
| A1 | Shot choice cuts every clip into windows of one length, scored on sharpness, exposure and motion. | It reads as a trimmer: uniform shot lengths and no narrative logic beyond "hook first". |
| A2 | The on-device model only re-weights those windows: one short description per keyframe, at most 24. | The AI could not add anything, only change which windows were chosen. |
| A3 | The edit brief prompt was capped at 1 000 characters. | Detailed creative briefs were cut off. |
| A4 | Captions from the source project were not carried into the Smart Edit project. | Speech work was lost. |
| A5 | The result was a toast with the first decision. | The user couldn't see what had been done, or what had not. |
| A6 | Cloud AI (Claude) was wired only into the editor's assistant, not into Smart Edit. | The strongest planner wasn't used for the biggest job. |
| A7 | No validation layer sat between the model's output and the timeline. | Any richer output would have been unsafe to execute. |

## Architecture (M2, this change)

```
footage ─► analysis (FrameAnalyzer: sharpness, brightness, motion across each clip)
        └► optional on-device descriptions (AiFrameDescriber)
brief ───► BriefInterpreter (+ on-device model)  ─► EditIntent
                     │
SmartEditPlanner (shot candidates) ─► MeasuredShot[]
                     │
      ┌──────────────┴────────────────┐
CreativeDirector (on-device,       Cloud planner (Claude, user key + consent;
 always runs, rule-based)           text footage report only, strict JSON schema)
      └──────────────┬────────────────┘
                 EditPlan (versioned, structured)
                     │
         EditPlanValidator ─► ValidatedPlan + steps (adjusted / skipped, with reasons)
                     │
         EditPlanExecutor ─► BuiltEdit: clips (trim, speed, ramp, grade, transitions),
                     │        text layers (role, preset, animation), captions re-timed
                     │        to the new cut, caption style, music levels, sound effects
                     ▼
     new project (the original is untouched = revert) + report dialog
```

All of this lives in `core/domain/aiedit/` (pure, unit-tested) and
`feature/ai/generation/smartedit/SmartEditor.kt`.

- **EditPlan:** shots (source, range, speed, ramp, transition, per-shot exposure), opening and
  ending fades, one grade (look + adjustments), texts (role, preset, animations, `claim` flag),
  caption preset, audio (music volume and fades, original volume, music mood), sound effects,
  questions and decisions. `EDIT_PLAN_VERSION` = 1.
- **Validator:** unknown sources are dropped; ranges are clamped to the footage; speeds are
  limited to 0.25–4×; unknown names are removed; colour moves are softened (no clipped
  highlights, crushed shadows or over-saturation); there are caps on counts. **Facts:** text
  marked as a claim must be grounded in what the user wrote, the caption text or file metadata.
  Otherwise it is not added and a question is asked (acceptance test 7).
- **Executor:** builds real objects through the existing editing code (`TimelineEditing`,
  `TransitionEditing`, `SpeedMath`, `ColorGrade`, `TextStyle`). Each step is logged as applied,
  adjusted or skipped. Saving the extras (titles, captions, music, sounds) reports any failure
  as a skipped step; nothing is claimed that didn't happen.
- **Creative director (on-device):**
  - a style look, refined by the brief's words and how bright the footage is, with per-shot
    exposure matching;
  - one or two speed ramps on the strongest or most active moments;
  - transitions only where the scene changes;
  - a title and an end card with style-matched typography and animation, plus the filming date
    from file metadata;
  - a caption preset per genre;
  - gym impact and whoosh accents;
  - music ducked under speech, with a mood suggestion when the project has no music.
  - It asks for place names and exercises instead of guessing them.
- **Cloud planner:**
  - `EditAssistant.planEdit` runs only with the user's own key and consent.
  - It sends the brief, per-source measured moments, on-device descriptions, caption text and
    the director's plan as a starting point. It never sends pictures, video or sound.
  - The reply is structured output against `EditPlanJson.schema`, at medium effort.
  - If the reply fails or is unusable, the on-device plan is used and the report says so.

## Milestones

| | Milestone | State |
|---|---|---|
| M1 | Pipeline audit | Done (above). |
| M2 | Structured plan, validation, execution, reporting | Code done on branch `ai-edit-engine`; unit tests written; CI and phone check pending. |
| M3 | Multimodal understanding | Next. Scene and shot boundaries from the frame-difference curve; send downscaled keyframes to vision-capable cloud models (with consent, showing what is sent); speech-aware cuts using auto-caption timings. |
| M4 | Complete creative editing | Partly in M2 (grade + LUT, ramps, titles, captions, transitions, sound, music). Still to do: per-shot section titles from speech, callouts and arrows (new graphic kinds), keyframed motion graphics, cutting on musical phrases. |
| M5 | Providers | Claude works today. OpenAI and OpenRouter need a provider interface over `ClaudeClient`, a capability registry (vision, JSON, context) and a provider and model picker. |
| M6 | Prompt library | Genre templates with full briefs; search, favourites, custom prompts. The 1 000-character prompt cap goes. |
| M7 | Conversational editing | Per-project chat history in Room; follow-ups produce *modification* plans against the current project (with manual-edit protection), executed as undoable edits in the editor. |
| M8 | Device validation | Real footage on the Vivo X200 Pro: genres, follow-ups, preview/export parity, memory and heat. |

### Not yet true (do not claim)

- No vision model sees the footage yet. Planners work from measurements and on-device text
  descriptions.
- There are no maps, route graphics, arrows, callouts or charts. Text layers are the only
  graphics kind.
- Captions are carried over and re-styled. They are not generated during Smart Edit: speech
  must first be transcribed in the editor (Auto captions).
- Follow-up prompts in Smart Edit are not built yet. The editor's assistant handles single
  follow-up requests on an open project.
- OpenAI and OpenRouter are not integrated.
