# CineMind multi-agent AI: decision engine, agents, models

First built on branch `agents` (2026-11-19). Extended on branch `agent-framework` (2026-11-19, master specification) with the workflow engine, execution traces, the voice clean-up and Whisper speech agents, and developer diagnostics (sections 10–13).

## 1. Audit: what already existed, and how the brief maps onto it

| Brief asks for | Already in CineMind | Done here |
|---|---|---|
| Versioned, validated `CineMindEditPlan` | `core/domain/aiedit`: `EditPlan` (versioned), `EditPlanValidator` (checks every id, time and name against the real media and refuses unsupported operations), `EditPlanExecutor` (turns a plan into real timeline objects with a step log) | Reused as the shared plan contract. AI never supplies media ids or paths: shots refer to sources by index, and times are clamped to the measured durations. |
| Gemma visual intelligence | `OnDeviceLlm` (LiteRT-LM 0.19.1), Gemma 4 E2B/E4B, frame descriptions (`AiFrameDescriber`), brief reading | Kept as the vision and audio model. Unchanged. |
| Whisper | Speech-to-text ran on Gemma 4's audio encoder (`SpeechTranscriber`), with Android's recogniser as fallback | **Added (PR #42)** as the dedicated speech agent: Whisper tiny (41 MB) or base (77 MB), int8 LiteRT builds from `litert-community`, run with the LiteRT interpreter. It produces real segment timestamps, detects the language, and can translate to English. Gemma stays the fallback. |
| "Google audio enhancement agent" | No Google audio model exists in the repository. Voice clean-up is CineMind's own DSP `VoiceEnhancer` (`core/domain/audio/VoiceEnhance.kt`): spectral noise reduction, a speech-gated leveller and a presence boost, applied at preview and export by `VoiceEnhanceAudioProcessor` | Wrapped as the **Voice clean-up** agent (`AUDIO_ENHANCEMENT`). It is deterministic and needs no model. Nothing was invented. |
| OpenCV, FFmpeg | `FrameAnalyzer` / `FrameMetrics` (sharpness, motion, exposure, faces) and Media3 for decoding, effects and export. FFmpeg is used only in CI. | Not added. Both are large native dependencies that would duplicate working code. |
| Cloud gateway | `CloudKeyStore` + `CloudLlm`: Claude, ChatGPT, Gemini, OpenRouter. Each uses the user's own key, encrypted in the Android Keystore, with consent, AI mode, and text only. | Wrapped behind the model adapter. See section 7 about keys. |
| Background work, cancellation | `BackgroundWork` foreground service and coroutine cancellation | Reused. |

## 2. Model and runtime compatibility (checked 2026-11-19 on Hugging Face)

| Model | Artifact for our runtime | Status |
|---|---|---|
| Gemma 4 E2B / E4B | `litert-community/gemma-4-E*-it-litert-lm`, `.litertlm` | In use (picture and speech). |
| **Spark X2.6 1.7B** | `litert-community/Spark-X2.6-1.7B`, `Spark-X2.6-1.7B_int4-mlp.litertlm`, 1.71 GB, Apache-2.0, built for LiteRT-LM 0.19.0 | **Added** as the faster planning option. The publisher measured 27 tok/s on a Galaxy S26 GPU and GSM8K 82 (bf16 84). |
| **Spark X2.5 4B** | `litert-community/Spark-X2.5-4B`, `Spark-X2.5-4B_int4.litertlm`, 2.48 GB, Apache-2.0, tested on LiteRT-LM 0.17 | **The chosen planning model** (owner's decision, 2026-11-19): picked automatically when installed, and ranked above Gemma for planning. About 5 tok/s on phones, so its thinking plus answer is capped at 2,400 tokens; a Smart Edit plan takes a few minutes. |
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
| Speech (transcription) v2 | audio analysis, transcription | media → transcript | transcription | Android recogniser | never | `SpeechTranscriber` → `WhisperEngine` (Whisper), else Gemma audio |
| Voice clean-up v1 | audio enhancement | media → clean audio | none | always | no | `VoiceEnhancer` DSP, `VoiceEnhanceStore` |
| Music director | beat analysis, music direction | media (+ brief) → music plan | none | always | no | `Beats`, `CreativeDirector` audio |
| Editing planner | edit planning | footage report (+ brief, shots, transcript, music) → edit plan | text + JSON | built-in director | yes (text) | `EditPlanJson`, `CreativeDirector` |
| Captions | captioning | transcript → captions | none | always | no | captions module |
| Quality check | quality check | edit plan → QA report | none | always | no | `EditPlanValidator` |
| Timeline and rendering | rendering, timeline operations | plan (+ QA, captions, clean audio) → timeline | none | always | no | `EditPlanExecutor`, `TimelineRenderer` |

Which model serves each skill, when installed:

| Skill | First choice | Then | Last resort |
|---|---|---|---|
| Planning (text + JSON) | Spark X2.5 4B | Gemma 4 E4B / E2B, cloud (with consent) | built-in rules |
| Vision | Gemma 4 E4B | Gemma 4 E2B | skipped |
| Transcription | Whisper base / tiny | Gemma 4 audio | Android recogniser |
| Audio enhancement | CineMind DSP (no model) | — | — |

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
* **Spark X2.6 4B is not integrated.** It has no build for our runtime (section 2). The owner chose Spark X2.5 4B instead (2026-11-19). Spark X2.6 1.7B stays available as a faster option.
* **Intent is structured but still basic.** Smart Edit builds `RequestIntent` from the brief (NEW_EDIT, creative or not, target length). Reading revision intent ("make it 30 s, keep the music") from free text with the planning model, and persisting earlier results between runs, is the next step. `RevisionPlanner` and the engine already handle such an intent: only the affected agents run, and kept aspects are reused (tested).

## 8. Configuration

* **On-device:** AI models → On this phone.
  * Download a Gemma (pictures and speech).
  * Optionally download Spark X2.5 4B (planning; picked automatically when installed) and Whisper (speech).
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

`WorkflowTest` covers a cinematic travel film with clear voices and subtitles (10 agents; Whisper transcribes, Spark plans, Gemma sees), switched-off agents, and the runner itself (parallel groups, retries, timeouts, skips and dynamically added agents). `WhisperTest` covers the mel front end, the tokenizer and timestamp parsing.

## 10. Workflow engine and tasks (`core/domain/agents/Workflow.kt`)

The decision engine decides *what* runs. `WorkflowRunner` decides *how*:

* Each planned step becomes an `AgentTask` with an id, a parent (the request), a priority, its input and output contracts (`DataKind`s), a status (`PENDING → RUNNING → SUCCEEDED / SKIPPED / FAILED / TIMED_OUT / CANCELLED`), a timeout and an error.
* **Pipeline:** stages run in order, because each needs the data the one before produced.
* **Parallel:** the agents of one stage run side by side (`async`). There is never more than one on-device model per group.
* **Conditional:** an agent may return `Skip(reason)`, for example "no subtitles asked for", or "kept your captions".
* **Dynamic:** an agent may return `Done(addAgents = …)`, and the engine plans those agents into the next stage.
* **Failures:** a timeout or a failure goes to `DecisionEngine.recover`, which picks the next fallback runtime and retries. Cancelling the job cancels every task (structured concurrency).

Smart Edit records its main pipeline (analysis, director, vision, each planner attempt, QA, rendering) and runs its audio sub-workflow through the runner: voice clean-up and transcription in parallel, then captions.

## 11. Observability

Every Smart Edit saves an `ExecutionTrace`: for each agent, the runtime (rules / phone / cloud), the model id, the status, the duration, the attempt number, the free memory and a note. The last 20 traces are kept in `files/ai-traces.tsv` on the phone. They hold text only: no media, and the request is cut to 120 characters.

**AI models → Diagnostics** (for developers) shows:

* every agent with its version, capabilities and the model skills it needs, with a switch to turn it off (`AgentSwitches`; the decision engine skips it with "switched off in developer settings");
* the recent runs with every task's outcome.

Model names appear only here and in the log. The user-facing report stays model-free.

## 12. Adding a model later

1. Add a `ModelSpec` to `ModelCatalog`: file URL, size and SHA-256, and its skills (vision, audio, reasoning).
2. If it is a new kind (like Whisper), give it an engine behind the same boundary and a `ModelDescriptor` in `AgentRuntime.models()` with its skills, memory, speed and quality.
3. Run the benchmark on the phone and set its quality score from the numbers.
4. The decision engine picks it up through skills. No agent changes.

A model with no LiteRT build (GGUF only, for example) needs a second runtime first. See section 2.

## 13. Not done, and why

* **Hybrid cloud for audio or frames:** never. Media stays on the phone. Only text goes to the cloud, with consent.
* **Benchmarks on the Vivo:** they need the owner's phone (Spark X2.5 4B, Whisper tiny and base, Gemma). CI can't download multi-GB models or use a phone GPU.
* **Per-agent memory limits enforced by the OS:** Android gives one process. The engine instead keeps one model loaded at a time and checks the budget before every model stage.
