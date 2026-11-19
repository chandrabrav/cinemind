# AI providers: Gemma, Claude, Higgsfield and KATANA

Status: 2026-11-19, branch `ai-providers`. Phase 1 (audit) and the free handoff route are built; the paid server relay is deliberately not built (owner wants generation free for users).
Related: [AI_EDITING.md](AI_EDITING.md) (edit-plan engine, milestones M1–M8).

## 1. What the repository has today

| Area | Where | State |
|---|---|---|
| On-device LLM | `feature/ai/llm/ondevice` (`OnDeviceLlm`, `ModelCatalog`, `ModelStore`, `ModelDownloadManager/Service`) | Gemma 4 E2B / E4B on Google LiteRT-LM 0.19.1. Downloaded at runtime, never deleted on update. Used for shot insight and brief reading (`ShotInsight`, `BriefInterpreter`, `AiFrameDescriber`). |
| Cloud LLM | `feature/ai/cloud` (`ClaudeClient`, `EditAssistant`, `CloudKeyStore`) | Claude through the official Anthropic Java SDK, user's own key in an encrypted store, explicit consent toggle, model picker. Used for the cloud edit plan. |
| Edit plan | `core/domain/aiedit` (`EditPlan`, `EditPlanValidator`, `EditPlanExecutor`, `CreativeDirector`, `EditPlanJson`) | Provider-independent, versioned, validated plan, executed into real timeline objects with a per-step log. This already is the "unified editing-plan schema" the brief asks for. |
| Smart Edit pipeline | `feature/ai/generation/smartedit/SmartEditor` | Analysis, then Gemma insights, then director plan, then optional Claude plan, then validate, execute, persist. Falls back to on-device when cloud fails. |
| Editor, render | `feature/editor/timeline`, `media3`, `feature/export` | Single native timeline and Media3 Transformer export. No second engine. |
| Background work | `ModelDownloadService`, `ExportService`, `core/common/BackgroundWork` | Foreground services that survive minimising. |
| Storage | Room v9 (`core/data/local`), files under `filesDir` | Projects, assets, timeline, captions, text layers, SFX. |
| Networking | `HttpURLConnection` (model download), OkHttp inside the Anthropic SDK | No other HTTP client, no backend of our own. |
| Dead code | `feature/ai/llm/LlmService.kt` (`LlmService`, `EditingPlanGenerator`) | Unused placeholder interfaces from the scaffold. To be removed, not built on. |

Corrections to the brief: the on-device model is Gemma 4, as the brief says (Qwen2.5-VL was in the original spec but has no LiteRT build). The UI is an original design inspired by the feel of OriginOS. Builds run on GitHub Actions only, not Codex.

## 2. What Higgsfield actually offers (checked 2026-11-19)

Sources: <https://docs.higgsfield.ai/docs/llms.txt> and its linked pages. `platform.higgsfield.ai/docs` returns 405, and the docs now live at docs.higgsfield.ai.

* REST API at `https://api.higgsfield.ai`, header `Authorization: Key <key_id>:<key_secret>`.
* The docs warn that the API must not be called from mobile code, because the secret can be extracted. **The key must live on a server.**
* Every generation is asynchronous:
  * Submitting returns `request_id`, `status_url` and `cancel_url`.
  * Status moves through `queued`, then `in_progress`, then `completed`, `failed`, `nsfw` or `canceled`.
  * Cancel works only while queued. `DELETE /requests/{id}` removes the request's data.
* Webhooks: add `?hf_webhook=<https url>`. Deliveries are signed with Standard Webhooks (`whsec_` secret), retried for 2 h and may arrive twice. Polling is the fallback.
* Input media must be a public HTTPS URL. Get one with `POST /files/generate-upload-url` (a presigned PUT that lasts 1 h). Formats: jpeg, png, webp, gif, wav, mp4.
* Cost:
  * `POST /estimate/<model path>` returns `{"usd": "…"}` before submitting.
  * `failed` and `nsfw` results are not charged. A cancelled queued request is refunded.
  * Account balance is prepaid.
* Outputs: `video.url`, `images[].url` or `audio.url`. They are kept for 7 days (`expires_at`) and must be downloaded.
* Limits: per-account concurrency. When the queue is full the API returns `429` with `Retry-After`. Send an `Idempotency-Key` so retries are safe.
* Catalogue: 84 model endpoints. Video includes Genjutsu, Seedance, Kling, Wan, MiniMax, LTX, PixVerse and others. Image includes SOUL, Recraft and others. Each model has its own request schema page. We only call models whose schema page we have read.
* **Genjutsu motion transfer** (`/higgsfield/genjutsu/motion-transfer/v1.0`, `video_url` plus `image_urls`, source at least 4 s) is the documented API route to "recreate this reference with my subject".

## 3. What KATANA is

* KATANA is a Higgsfield **skill for Claude**. It runs through Higgsfield's hosted MCP connector (`https://mcp.higgsfield.ai/mcp`) after the user signs in to their Higgsfield account. It does not use an API key.
* Its launch page is <https://higgsfield.ai/katana>. Results appear in the Claude chat and in the user's Higgsfield Assets.
* It needs a paid Higgsfield plan. Inputs are a reference edit (an upload or a preset), up to 5 photos, and instructions.
* **There is no public REST API for KATANA.** A third-party Android app cannot submit jobs to it or fetch its results.
* So CineMind supports KATANA as a **handoff**:
  1. Prepare the brief.
  2. Export the reference clip and photos to a shareable folder.
  3. Open the KATANA page or the Claude app.
  4. The user generates there.
  5. The user imports the downloaded result back into the project.
* CineMind never reports a KATANA result itself. It only sees the file the user imports.
* For an in-app reference recreation, the official API route is Genjutsu motion transfer (section 2).

## 4. Target architecture

```
                 ┌──────────────── Android app ────────────────┐
 prompt ───────▶ │ AiRouter (mode: Offline / Hybrid / Cloud)    │
                 │   ├─ plan/understand ─▶ Gemma 4 (on device)  │
                 │   │                    └▶ Claude (opt-in)    │
                 │   ├─ edit plan ─▶ EditPlanValidator ─▶ Executor ─▶ Timeline (native)
                 │   └─ generate media ─▶ GenerationProvider     │
                 │          ├─ HiggsfieldProvider ──HTTPS──┐     │
                 │          └─ KatanaHandoff (share/open)  │     │
                 │ GenerationJobs (Room) + GenerationService │     │
                 │   (foreground, polls, downloads, imports) │     │
                 └───────────────────────────────────────────┼─────┘
                                                             ▼
                         CineMind relay (serverless, owner's account)
                         holds HF key + webhook secret, device tokens,
                         daily spend cap, estimate-before-submit,
                         presigned uploads, job status cache
                                                             ▼
                                              api.higgsfield.ai
```

Design decisions:

* **Gemma stays the brain.** The router never replaces it. In Offline mode nothing leaves the phone.
* **Two kinds of provider, not one forced interface.** Planning (Gemma, Claude, the heuristic director) is synchronous and already returns an `EditPlan`. Media generation (Higgsfield, KATANA) is a long asynchronous job. The brief's `AIProvider { process; getStatus }` fits only the second kind, so that is where it is used: `GenerationProvider { capabilities; estimate; submit; status; cancel }`.
* **No duplicate engine.** Generated clips become ordinary `MediaAsset`s with provenance (`provider`, `model`, `requestId`). They go into the existing timeline, and the edit plan can reference them like any other source.
* **No open HTTP calls during generation.** Jobs live in a Room table. A foreground service polls the relay with backoff (webhooks reach the relay, not the phone) and downloads the result, so the job survives minimising and app restarts.
* **Paid actions need confirmation.** The relay estimates the cost, the app shows it, the user confirms, then the relay submits. The relay also enforces a daily USD cap.

## 5. Phases

| Phase | Content | Needs |
|---|---|---|
| 1 | This audit and plan | done |
| 2 | `AiMode` setting (Offline / Hybrid / Cloud-assisted), `AiRouter`, `GenerationProvider` abstraction, `GenerationJob` Room table (migration 9 to 10, nothing dropped), asset provenance, removal of dead `LlmService` | nothing external |
| 3 | Relay (Cloudflare Worker, code in `relay/`, deployed by the owner) and `HiggsfieldProvider` (estimate, upload, submit, poll, cancel, download, import). Unit tests against recorded responses. | the owner's Higgsfield key and a relay host |
| 4 | KATANA handoff screen: pick reference and up to 5 photos, write instructions, export the bundle, open the KATANA page or Claude, then import the result. Genjutsu motion transfer as the in-app alternative. | Phase 3 for Genjutsu |
| 5 | "Generate" sheet in the editor: prompt, references, provider or Auto, cost confirmation, progress, cancel, retry, preview, add to timeline | 2 to 4 |
| 6 | Tests (router, validator, job state machine, relay signature check), emulator test for import, docs, setup guide | |

Security rules that hold throughout:

* No Higgsfield secret in the APK, the repository or the docs.
* Relay secrets live in Cloudflare encrypted secrets.
* Uploads require per-action consent.
* Generated outputs are copied to app storage and the remote copy is deleted on request.

## 6. What is built (handoff route, free for users)

The owner decided users must not pay through CineMind. So there is **no relay and no Higgsfield key anywhere**. Generation runs in the user's own Higgsfield account, through the official KATANA workflow.

| Piece | File |
|---|---|
| `AiMode` (Offline / Hybrid / Cloud-assisted), `AiRouter`, `AiTask`, `AiEngine` | `core/domain/aigen/AiRouting.kt` |
| `GenerationProvider` registry (KATANA), `HandoffBriefs`, `GenerationJob` entity and `JobStatus` rules | `core/domain/aigen/Generation.kt` |
| `generation_jobs` table, DAO, repository, migration 9 to 10 (additive) | `core/data` |
| AI mode picker, persisted in the Cloud AI settings | `feature/ai/cloud/CloudKeyStore.kt`, `ui/CloudAiScreen.kt` |
| Cloud planning obeys the mode. Offline means never. Cloud-assisted means higher reasoning effort. | `feature/ai/cloud/EditAssistant.kt` |
| Remake sheet (Editor, then AI, then "Remake (KATANA)") | `feature/editor/timeline/handoff/` |
| FileProvider that shares only the `cache/handoff/` copies | `app/AndroidManifest.xml`, `res/xml/handoff_paths.xml` |
| Removed dead scaffold `LlmService`, `LlmModels`, `GenerationModels` | |

How a remake works:

1. Pick a reference video, add up to 5 subject photos, and write what to keep or change. The brief starts with `/katana`.
2. Tap **Send to Claude**.
   * CineMind copies only the picked files to a private share folder (video up to 500 MB, photos up to 30 MB each).
   * It opens the Claude app with the brief and files. If Claude isn't installed, it opens the share sheet.
   * The brief also goes on the clipboard.
3. The job is stored as `HANDED_OFF`, with a "Waiting for your result" label.
4. The user generates in Claude, where Higgsfield shows the cost, then downloads the video.
5. In the sheet, the user taps **Import result** on that job and picks the video. It is imported, linked to the job (`outputAssetId`, provenance) and appended to the timeline.
6. The share copies are deleted after import or discard, and anything older than 2 days is deleted automatically.

Honesty rules, enforced by `GenerationJob.moveTo` and tested:

* A job can only be `IMPORTED` with a real asset.
* A finished job never changes.
* CineMind never claims KATANA succeeded. It only knows a file was imported.

Offline mode disables sending. Gemma and the native editor are untouched.

Not built, and why:

* **Higgsfield API relay.** It would charge the owner's balance for every user. The design for it is in section 4, if that changes.
* **Genjutsu motion transfer in-app.** It needs the relay, for the same reason.
