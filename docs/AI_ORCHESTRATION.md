# CineMind multi-agent AI: decision engine, agents, models

Branch `agents`, 2026-11-19. It is stacked on `voice-and-cloud`.

## 1. Audit: what already existed, and how the brief maps onto it

| Brief asks for | Already in CineMind | Done here |
|---|---|---|
| Versioned, validated `CineMindEditPlan` | `core/domain/aiedit`: `EditPlan` (versioned), `EditPlanValidator` (checks every id, time and name against the real media and refuses unsupported operations), `EditPlanExecutor` (turns a plan into real timeline objects with a step log) | Reused as the shared plan contract. AI never supplies media ids or paths: shots refer to sources by index, and times are clamped to the measured durations. |
| Gemma visual intelligence | `OnDeviceLlm` (LiteRT-LM 0.19.1), Gemma 4 E2B/E4B, frame descriptions (`AiFrameDescriber`), brief reading | Kept as the vision and audio model. Unchanged. |
| Whisper | Speech-to-text already runs on Gemma 4's audio encoder (`SpeechTranscriber`), with Android's recogniser as fallback | Not added. A second speech model would cost about 1 GB more for no gain. |
| OpenCV, FFmpeg | `FrameAnalyzer` / `FrameMetrics` (sharpness, motion, exposure, faces) and Media3 for decoding, effects and export. FFmpeg is used only in CI. | Not added. Both are large native dependencies that would duplicate working code. |
| Cloud gateway | `CloudKeyStore` + `CloudLlm`: Claude, ChatGPT, Gemini, OpenRouter. Each uses the user's own key, encrypted in the Android Keystore, with consent, AI mode, and text only. | Wrapped behind the model adapter. See section 7 about keys. |
| Background work, cancellation | `BackgroundWork` foreground service and coroutine cancellation | Reused. |

## 2. Model and runtime compatibility (checked 2026-11-19 on Hugging Face)

| Model | Artifact for our runtime | Status |
|---|---|---|
| Gemma 4 E2B / E4B | `litert-community/gemma-4-E*-it-litert-lm`, `.litertlm` | In use (picture and speech). |
| **Spark X2.6 1.7B** | `litert-community/Spark-X2.6-1.7B`, `Spark-X2.6-1.7B_int4-mlp.litertlm`, 1.71 GB, Apache-2.0, built for LiteRT-LM 0.19.0 | **Added** as the recommended planning model. The publisher measured 27 tok/s on a Galaxy S26 GPU and GSM8K 82 (bf16 84). |
| **Spark X2.5 4B** | `litert-community/Spark-X2.5-4B`, `Spark-X2.5-4B_int4.litertlm`, 2.48 GB, Apache-2.0, tested on LiteRT-LM 0.17 | **Added** for comparison. The publisher measured about 5 tok/s on phones, so a 1,500-token plan takes about 5 minutes. The engine's speed scoring keeps it from being chosen over the 1.7B for long plans. |
| Spark X2.6 **4B** | Only GGUF, MLX, FP8/INT8/INT4 safetensors and AWQ builds. There is **no `.litertlm` build.** | **Not integrated.** Running GGUF would need a second runtime (llama.cpp through JNI and the NDK). The adapter boundary (`CineMindModel`) is ready for one. When a LiteRT-LM build appears, it is one `ModelSpec` entry. |
| Qwen3.5 4B | Not checked for a LiteRT build in this pass | Same adapter path if a `.litertlm` exists. |

Both Spark files are reasoning models: they think inside `<think>…</think>` and declare a thought channel. `OnDeviceLlm` gives them a larger window (8,192 tokens) and a thinking budget (3× the answer budget), and strips any thinking that reaches the answer (`OnDeviceLlm.stripThinking`). Downloads are checked against the published SHA-256 like every other model.

**Not yet verified on a phone:** the publisher tested these files on LiteRT-LM 0.19.0, and CineMind uses 0.19.1. Run the benchmark (section 6) on the Vivo X200 Pro before relying on Spark.

## 3. Architecture

```
request ─▶ RequestIntent (structured) + ProjectContext (measured)
              │
              ▼
     CineMind Decision Engine (core/domain/agents/DecisionEngine.kt, pure, unit-tested)
       • capabilities the request needs, given the project
       • agents that provide them (AgentRegistry) and their data dependencies
       • runtime per agent: deterministic / on-device model / cloud, using
         ModelDescriptor (skills, memory, speed, quality, availability)
         ResourceBudget (memory, heat, battery)
         Policy (AI mode, cloud consent, "use AI" switch)
       • stages from the data graph; at most one on-device model per parallel group
       • ordered fallbacks per agent (recover() on failure)
              │
              ▼
     ExecutionPlan ─▶ Smart Edit runs only the planned agents, tries fallbacks in order,
                      validates (QA), executes into a new project, and reports what ran
```

Agents talk only through typed `DataKind`s, and use only allow-listed `Tool`s: no shell and no free file access.

## 4. Agent capability matrix

| Agent | Capabilities | Requires → produces | Model skill | Without a model | Cloud? | Backed by |
|---|---|---|---|---|---|---|
| Creative director | understand request, creative direction | request → creative brief | text + JSON | rules (`BriefInterpreter`) | yes (text) | `Prompts.brief`, `AiAssist.mergeBrief` |
| Visual intelligence | visual understanding | media + footage report → shot descriptions | vision | skipped | **never** (frames stay on the phone) | `AiFrameDescriber` (Gemma) |
| Video analysis | video analysis | media → footage report | none | always | no | `FrameAnalyzer`, `FrameMetrics` |
| Audio intelligence | audio analysis, transcription | media → transcript | audio | Android recogniser | never | `SpeechTranscriber` |
| Music director | beat analysis, music direction | media (+ brief) → music plan | none | always | no | `Beats`, `CreativeDirector` audio |
| Editing planner | edit planning | footage report (+ brief, shots, transcript, music) → edit plan | text + JSON | built-in director | yes (text) | `EditPlanJson`, `CreativeDirector` |
| Captions | captioning | transcript → captions | none | always | no | captions module |
| Quality check | quality check | edit plan → QA report | none | always | no | `EditPlanValidator` |
| Timeline and rendering | rendering, timeline operations | plan (+ QA, captions) → timeline | none | always | no | `EditPlanExecutor`, `TimelineRenderer` |

## 5. Resource management

* `DeviceResources.snapshot` reads available and total RAM, the low-memory flag, `PowerManager` thermal status, battery level, charging state and power-save mode.
* `ResourcePolicy.budget` then decides:
  * It keeps 1.8 GB for the editor and Android. The largest model allowed is (available − 1.8 GB) / 1.3, and never more than 45% of RAM.
  * When warm (moderate), only models of 2.2 GB or less, and optional AI is skipped. When hot (severe), no local models. When critical, nothing optional.
  * On low battery (≤ 15%, or ≤ 30% in power-save, when not charging): small models only and no optional AI.
* One engine is in memory at a time. `OnDeviceLlm` unloads before switching between Gemma and Spark, after every Smart Edit stage, after 60 s idle and when the app goes to the background.
* All inference runs off the main thread. Smart Edit runs in the foreground service and can be cancelled.

## 6. Benchmark (to run on the phone)

AI models → On this phone → **Run benchmark** measures every installed model in turn, so only one is ever loaded. It records:

* load time and a short text answer;
* a picture description (vision models only);
* **a CineMind edit plan**: a fixed made-up three-clip travel project, the real `EditPlanJson` prompt and schema, then `EditPlanValidator`. It reports time, characters, whether the JSON was valid, and whether the validator found usable shots;
* free RAM before and after, and the thermal state.

Copy the report and send it back. The quality scores in `AgentRuntime.QUALITY` should then be set from these numbers rather than from public benchmarks.

## 7. Deviations from the brief, and why

* **Cloud keys.** The brief asks for backend-mediated provider access. The owner decided CineMind must stay free with no paid server, so each user brings their own key.
  * Keys are never in the APK or the repository.
  * They are encrypted with the Android Keystore and sent only to their provider.
  * A backend gateway can replace `CloudLlm` later without touching agents, because agents see only `CineMindModel`.
* **Spark X2.6 4B is not integrated.** It has no build for our runtime (section 2). Spark X2.6 1.7B, the same family and generation, is integrated instead.
* **Intent is structured but still basic.** Smart Edit builds `RequestIntent` from the brief (NEW_EDIT, creative or not, target length). Reading revision intent ("make it 30 s, keep the music") from free text with the planning model, and persisting earlier results between runs, is the next step. `RevisionPlanner` and the engine already handle such an intent: only the affected agents run, and kept aspects are reused (tested).

## 8. Configuration

* **On-device:** AI models → On this phone.
  * Download a Gemma (pictures and speech).
  * Optionally download Spark X2.6 1.7B (planning).
  * "Don't use a planning model" turns planning back over to Gemma or the rules.
* **Cloud:** AI models → Cloud. Pick a service, paste your key, choose a model, allow sending text, and choose an AI mode (Offline / Hybrid / Cloud-assisted).
* The Smart Edit report's **How this was made** section lists which agents ran, and where: on this phone or in the cloud, with no model names. The model ids are in the app log (`Decision engine: …`) for diagnostics.

## 9. Tests

`DecisionEngineTest` covers:

* timeline-only requests;
* agent selection for an emotional travel film (stages, and the rule of one model per parallel group);
* simple edits skipping vision;
* captions-only requests;
* Offline mode never using the cloud;
* a hot phone falling back to rules;
* cloud only with consent, and never for frames;
* the retry and fallback chain;
* revisions reusing analysis and keeping the music;
* resource budgets.

Model-catalog hashes and URLs are covered by `PromptsTest.catalogFilesMatchTheirUrls`.
