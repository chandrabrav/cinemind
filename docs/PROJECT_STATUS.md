# CineMind AI — Project Status

> Handoff document for any Claude / Codex / ChatGPT session. Update after every milestone.
> Never put API keys, keystore passwords or tokens in this file.

_Last updated: 2026-11-11_

## Current position

| | |
|---|---|
| **Stage** | Stage 1 foundation built; Stage 3 started (transitions, music, split); **Stage 4 on-device AI integrated (owner chose on-device first, 2026-11-11)** — awaiting the Vivo benchmark |
| **On-device AI (new)** | Gemma 4 E2B / E4B via Google LiteRT-LM, downloaded in-app (✦ on the home screen), SHA-256 verified; Smart edit uses it to read the brief and describe key frames, with fallback to measurement-only. Benchmark screen produces a copyable report. **Not run on a real phone yet** (CI can't download 2.6 GB or use a phone GPU). |
| **Last completed milestone** | Smart edit + transitions + music + split (2026-11-11): the brief creates a real edit (on-device frame analysis + planner → new editable project); 7 clip transitions in preview and export; background music (offset, loop, volume, fades) + original-sound level; split at playhead; editor tools bar (Smart edit, Add clips, Transitions, Music, Export); emulator end-to-end export tests green in CI. |
| **Owner feedback (device test of build #30)** | App installs and imports. Complaints: only two bottom options; no AI editing; no transitions; "AI brief not working" (its button was intentionally disabled). Addressed in this milestone. |
| **UI redesign (new, 2026-11-11)** | Whole app moved onto a central design system (light + dark tokens, contrast-tested), persistent Light / Dark / System theme in a new Settings screen, every screen redesigned, editor rebuilt around a time-scaled multitrack timeline with drag-to-trim. See "UI redesign & design system". |
| **Current task** | Owner installs build #73: checks the redesign in both themes, then runs the AI benchmark (✦ on the home screen) and sends the report; then tries Smart edit with AI. |
| **Branch** | `dev-main` (redesign done on `redesign`, fast-forwarded) |
| **Latest verified build** | CI run 43061916979 (#73), commit `82f97fa`: build + 131 unit tests + 6 instrumented tests (2 export end-to-end, 4 screenshot tests: design system + editor × light/dark) — https://github.com/chandrabrav/cinemind/actions/runs/43061916979 |
| **Latest verified APK** | Artifact `cinemind-debug-73` (43.7 MB zipped). Screenshots: artifact `ui-screenshots-73`. Not yet device-tested. |

## Build environment (hard rule)

- All Android builds run in **GitHub Actions** (`.github/workflows/ci.yml`). Nothing Android-related is installed locally (owner's machine is Windows; no Android Studio / SDK / JDK / Gradle).
- CI provisions **Gradle 8.14.3** via `gradle/actions/setup-gradle` (no wrapper committed yet) and Temurin **JDK 17**.
- Jobs on every push:
  - `build`: `gradle testDebugUnitTest` then `gradle assembleDebug`. Artifacts: `cinemind-debug-<run>` (APK, 30 days), `test-reports-<run>`, `room-schemas-<run>`.
  - `export-e2e`: frees runner disk, boots an **API 30 x86_64 emulator** (KVM, swiftshader GL, 3 GB data disk), generates test media with **ffmpeg** into `feature/export/src/androidTest/assets/` (git-ignored), runs `ExportEndToEndTest`. ~15 min. (The first attempts failed only because the runner lacked disk for the emulator.)
  - `release`: only on `main` and only when repo secrets `CINEMIND_KEYSTORE_BASE64`, `CINEMIND_KEYSTORE_PASSWORD`, `CINEMIND_KEY_ALIAS`, `CINEMIND_KEY_PASSWORD` exist.
- Free tier for private repos: 2,000 Actions minutes/month; a push now costs ≈ 20–25 runner minutes (build + emulator).
- `gh` is authenticated inside **WSL (Ubuntu-24.04)**, not in Windows. Helper scripts (outside the repo) in `D:\projects\cinemind-local-tools\`, run as `wsl -d Ubuntu-24.04 -- bash /mnt/d/projects/cinemind-local-tools/<script> ...`:
  - `ci-watch.sh <branch>` — latest run on a branch; `ci-run-for.sh <branch> <sha-prefix>` — the run for a commit; `ci-wait.sh <run-id>` — wait + failing lines.
  - `ci-verify.sh <run-id> [schemas-dest]` — per-module test counts, artifacts, optional Room schema copy.
  - `ci-emulog.sh <run-id>` — emulator boot log lines from the e2e job.
  - `get-apk.sh <run-id> <artifact>` — downloads an APK artifact to `apk-<artifact>/`.
  - PowerShell mangles `$` in inline `bash -c` strings — always use script files.

## History recovered (2026-11-11 audit)

- Nov 2–4: ~2,300 lines of scaffold generated and merged to `main` (PRs 1–3). It never compiled; every CI run failed.
- Nov 4–11: a previous session tried to build **locally on Windows**, committing a JDK and Gradle distribution (files > 100 MB, unpushable) and repeatedly rewriting the version catalog with PowerShell scripts until it was 0 bytes.
- Nov 11 repair: those 2 unpushed commits were undone (old tip `8e44dbb` kept in local reflog); the toolchain and scripts were moved to `D:\projects\cinemind-local-tools\` (outside the repo) and are git-ignored.
- No prior Claude transcript or handoff doc was available; the updated master spec (`CineMind_AI_Master_Prompt_UPDATED(1).txt`) has **not** been provided yet — only the original `CineMind_AI_Master_Prompt.txt` was used.

## Architecture (as built)

- **Toolchain (upgraded 2026-11-11 for LiteRT-LM, which is built with Kotlin 2.4):** Kotlin 2.4.21, KSP 2.3.12 (KSP2; 2.3.13 has a parallel-execution regression), AGP 8.13.2, Gradle 8.14.3 (CI), Hilt 2.58 (2.59+ require AGP 9) with `ksp(kotlin-metadata-jvm:2.4.21)` in every Hilt module so it can read Kotlin 2.4 metadata, Room 2.8.5, coroutines 1.11.0. compileSdk/targetSdk 35, minSdk 30, Java 17. All modules use `kotlin { compilerOptions { jvmTarget } }` (the old `kotlinOptions {}` is removed in Kotlin 2.2+). App ships `arm64-v8a` + `x86_64` native code only.
  - Next toolchain step when needed: AGP 9 (built-in Kotlin, new DSL) would allow Hilt ≥2.59; not required now.
- UI: Jetpack Compose + Material 3 (BOM 2024.12.01), Navigation Compose. Dark-first theme in `core/ui`.
- DI: Hilt (KSP). Storage: Room, DataStore Preferences.
- Room schema is **v4** (v2–v4 JSON committed): `MIGRATION_1_2` (`media_assets.displayName`, `sizeBytes`), `MIGRATION_2_3` (`edit_briefs`), `MIGRATION_3_4` (`clips.transitionInType`). **No destructive migration.** Exported schema JSON is committed in `core/data/schemas/` — after any schema change, fetch the new JSON from the `room-schemas-<run>` artifact, compare its `createSql` with the hand-written migration, and commit it.
- Media: system Photo Picker for import; `MediaMetadataRetriever` + `MediaExtractor` for metadata; Coil 2.7 + `coil-video` for thumbnails.
- **Transitions** (`core/domain/editing/Transitions.kt`): `ClipTransition` = CUT, FADE (dip to black), ZOOM_IN, ZOOM_OUT, SLIDE_LEFT/RIGHT/UP; stored per clip as `transitionInType` + `transitionInMs` (opening on the first clip) and `transitionOutMs` on the last clip (ending fade). There are no overlapping clips yet, so each transition is split: the outgoing clip animates out over half, the incoming clip animates in over half. `TransitionMath.animations()` + `lookAt()` produce a `FrameLook` (scale, translate, brightness) — the **single source of truth** for both preview and export. True cross-dissolves need multi-track compositing (Stage 3).
- Preview: Media3 1.5.1 ExoPlayer; `PlayerView` inflated from `preview_player_view.xml` with **`surface_type="texture_view"`** so Compose can scale/slide it (`graphicsLayer`) and darken it (black overlay) from `FrameLook`. `PreviewPlayer` maps each clip to one playlist item (`ClippingConfiguration`; photos/missing media use `SilenceMediaSource` with the image drawn on top).
- Export: `ExportManager` (app-scoped, main thread) → `TimelineRenderer` (DI-free, testable) builds a Transformer `Composition`: per clip `FrameDropEffect` (if source fps > target) → `Presentation` letterbox → `TransitionMotion` (`MatrixTransformation`, NDC) + `TransitionBrightness` (`RgbMatrix`) evaluated with `TransitionMath`. Effects assume **frame timestamps are in composition time** (Media3 adds each item's offset before effects) — `ExportEndToEndTest` checks this by requiring a black frame exactly at a fade cut. H.264 + AAC, HDR → SDR tone map, forced silent audio track. Verified with `MediaMetadataRetriever`, saved to MediaStore `Movies/CineMind`. `ExportService` foreground service mirrors progress.
- **Smart edit** (`feature/ai/generation/smartedit/`), a deterministic on-device stand-in for the Stage 4–5 AI models with the same inputs/outputs:
  - `FrameAnalyzer` samples each source (video: ~1 frame / 0.7 s, 3–48 frames, 128 px, `getScaledFrameAtTime` CLOSEST_SYNC; photo: one downsampled decode) and measures sharpness (Laplacian variance), brightness and motion (frame difference) — `FrameMetrics`/`MomentScoring` (pure).
  - `BriefInterpreter` turns the brief into an `EditIntent`: explicit choices win; otherwise keywords in the prompt set style, pace, length ("30 seconds", "1 minute"), strict quality ("skip shaky/blurry", "best moments"), order (story → chronological; gym/hook → best shot first) and transition overrides ("only cuts", "fade", "zoom", "slide"). Each style has a transition palette and opening/ending fades.
  - `SmartEditPlanner` cuts videos into shot-length windows, scores them, greedily picks the best non-overlapping windows with a repeat penalty per source until the target length (auto: 60 % of footage, 8–45 s), orders them and trims the last shot to land exactly on the target; transitions only where the source changes. Produces human-readable `decisions`.
  - `SmartEditor` saves the result as a **new project** (“<name> · Smart edit”, same format, brief copied). The source project is never modified.

Modules compiled in CI (`settings.gradle.kts`):

| Module | Contents |
|---|---|
| `:app` | Activity, nav host (`home` → `media_library` → `editor/{id}` → `brief/{id}`, `export/{id}`), Hilt modules, Coil setup |
| `:core:common` | Logger, constants |
| `:core:domain` | Room entities, `ProjectFactory`, `ProjectFormat`, `EditBriefRules`, `ProjectNames`/`ProjectCopier`, `editing.*` (`TimelineEditing` incl. `append`, `EditHistory`, `ClipTransition`, `TransitionMath`, `TransitionEditing`), repository interfaces |
| `:core:data` | Room DB + migrations (v4), DAOs, repository implementations |
| `:core:ui` | Theme, colors, typography, `formatTimecode`, `formatDuration` |
| `:feature:media` | `MediaImporter`, `MediaLibraryViewModel`, library screen |
| `:feature:project` | Home screen: project list (open, rename, duplicate, delete) |
| `:feature:editor:timeline` | Editor: `PreviewPlayer` + `MusicPreview` + `TimelineMath`, `TimelineViewModel` (edit session, undo/redo, transitions, split, add media, music), `EditPanels` (edit toolbar, clip inspector, transitions dialog, music dialog, tools bar), clip strip |
| `:feature:export` | `ExportPlanner`, `TimelineRenderer`, `ExportManager`, `MediaStoreSaver`, `ExportService`, export screen; `androidTest/ExportEndToEndTest` |
| `:feature:settings` | Settings DataStore |
| `:feature:ai:generation` | Brief screen + `EditBriefViewModel` (autosave, Create smart edit); `smartedit/*` engine |
| `:feature:ai:{analysis,llm,cloud}` | Interfaces / data classes only |

Scaffolded but **excluded** until they get code: `:feature:editor:{preview,effects,color,audio}`, `:media3`, `:benchmark`.

## Feature status

| Feature | Status |
|---|---|
| Media import, create project, project list | Implemented; logic unit-tested; import confirmed working on the owner's Vivo (build #30) |
| Video preview | Implemented incl. transition preview; not yet device-verified with transitions |
| Editor tools | Tools bar: **Smart edit**, **Add clips** (picker → append, one undo step), **Transitions** (apply one transition to every cut + fade in/out), **Export**. Tap a clip for trim / move / remove / per-clip transition |
| Transitions | 7 types, 0.3–1.2 s, per clip or for all; preview + export share one model; `TransitionsTest` (10). Export timing verified on emulator by `ExportEndToEndTest` (when green) |
| Smart edit ("AI brief") | **Working, rule-based**: on-device analysis + planner → new editable project with decisions summary. Unit tests: `FrameMetricsTest` (5), `BriefInterpreterTest` (6), `SmartEditPlannerTest` (7). Not device-verified. No scene understanding, no beat sync |
| Trim / reorder / delete / undo | Implemented; unit-tested |
| Export | Implemented (720p–4K, 24–60 fps, H.264/AAC SDR); end-to-end emulator test |
| AI with models (VLM/LLM), cloud AI | Not started (Stage 4–6) |
| Captions / STT / Hindi-Hinglish / graphics | Not started; needs the updated spec |
| Music | Implemented: one background song (start point, loop, volume, fades) + original-sound level; preview + export; emulator-tested mixing. No beat detection, no multiple audio tracks, no voice-over yet |
| Split clip | Implemented (split at playhead), unit-tested |
| Beat sync, color/LUTs, speed, multi-track, cross-dissolves | Not started (Stage 3) |

## Architecture decisions

1. **Cloud-only builds** via GitHub Actions; no local toolchain, nothing vendored into git.
2. Keep the existing modular layout but only compile modules that contain code.
3. Media import uses the **Photo Picker** → no storage permissions.
4. Originals are **referenced by URI, never copied or modified**.
5. Clip order = tap order; images default to 3 s; project format follows the first item.
6. **API keys never go in DataStore**; Keystore-backed storage arrives with cloud AI (Stage 6).
7. No destructive Room migrations; every schema change ships a `Migration` and a committed schema JSON.
8. ktlint/detekt removed for now; configs remain in `config/`.
9. **Transitions without overlap** (split in/out halves, fade = dip to black) until multi-track compositing exists; one `TransitionMath` drives preview and export.
10. **Smart edit is honest and non-destructive**: labelled as on-device analysis (not "AI model"), always creates a new project, shows its decisions. The planner's `EditIntent` → shots → timeline contract is what Stage 4–5 models will plug into.

## Known issues / debt

- Preview of transitions is an approximation drawn in Compose over the player; export uses GPU effects. They share the same math but may differ slightly in easing at the exact cut frame.
- Smart edit quality is limited to sharpness/exposure/motion: it can't recognise people, scenery or story moments, and it doesn't sync to music. Analysis of long 4K/HEVC footage may take a while (frames decoded at sync points).
- Undo history is in memory per editor session. Reordering uses buttons, no drag-and-drop. No split.
- Photo EXIF orientation not applied to stored size; export rotation of photos unverified.
- If a provider refuses a persistable URI grant, media is readable only until restart. No missing-media relink.
- No DAO/migration instrumented tests yet (the emulator job now exists, so they can be added).
- No Gradle wrapper; CI actions on deprecated Node 20 majors.
- Updated master spec missing (captions / Hindi-Hinglish / STT / graphics requirements unknown).

## Build log

| Run | Commit | Result | Notes |
|---|---|---|---|
| 43027521557 (#38) | `88dfd70` | build ✓ | Transitions + tools checkpoint |
| 43028623813, 43030655568 | `1f4700c`, `3afebaa` | build ✓, e2e ✗ | Emulator never booted: runner had 6.9 GB free, emulator needed 7.4 GB |
| 43033497285, 43037440813 | `dcdcd74`, `3662adb` | e2e ✗ | `apt-get` couldn't resolve the Ubuntu mirror → ffmpeg install hung; fixed by `FedericoCarboni/setup-ffmpeg` (static build) |
| 43037440813 | `3662adb` | build ✗ | Room KSP: computed `isAudio` looked like a getter for `hasAudio` → renamed `isAudioFile` |
| 43039599346 | `883a9ef` | e2e: music ✓, transitions ✗ | Test read raw 1280×720: Media3 stores portrait as landscape + 90° rotation; test now compares displayed size |
| 43041216232 (#49) | `7bd69c6` | all green | 101 unit + 2 emulator tests; fade-to-black at the cut confirms effects see composition time |
| 43044100519 | `b567e69` | build ✗ | KSP 2.3.13 needs AGP ≥ 8.12 (`NoSuchMethodError addKspConfigurations`) → AGP 8.13.2 |
| 43044790769 | `c98da7a` | build ✗ | Hilt 2.60.1 requires AGP 9 → Hilt 2.58 + pinned kotlin-metadata-jvm |
| 43046403672 | `cc23e32` | **all green** | LiteRT-LM + AI smart edit; 118 unit + 2 emulator tests |
| 43047923600 | `cc23e32` | build ✗ (flaky) | KSP 2.3.13 parallel race "Cannot find required type element java.lang.Class" → KSP 2.3.12 |
| **43049749268 (#61)** | `c60b388` | **all green** | KSP 2.3.12 + arm64/x86_64 only; 118 unit + 2 emulator tests |
| 43052939565 | `85158c6` | unit ✗ | Contrast test caught light accent on backdrop (4.38:1) → light accent/highlight/success/music darkened |
| 43055014562 | `6661629` | all green | Full redesign + editor timeline; 129 unit + 2 emulator |
| 43057669226 | `92b2c59` | e2e ✗ | Dark screenshot capture timed out on the CPU-rendered emulator → retrying capture, `--continue` |
| **43061916979 (#73)** | `82f97fa` | **all green** | Screenshot-review fixes; 131 unit + 6 instrumented (export ×2, screenshots ×4) |

## Music & audio (as built)

- `core/domain/audio/Music.kt`: `MusicSettings` (song asset, start offset, volume, fade in/out), `ProjectAudio` (music + original-sound volume), `MusicLayout.segments()` (cut or loop the song to the video length) and `gainAt()` (fade envelope) — shared by preview and export. `MusicLayoutTest` (5).
- Storage without schema change: an AUDIO track (volume = music volume) with one clip (assetId = song, sourceInUs = offset, transitionIn/OutMs = fades). The VIDEO track's volume is the clips' own sound. Home-screen counts/covers use video tracks only; audio assets are hidden from the media grid. Duplicate and smart edit carry music over.
- Preview: `MusicPreview` — a second ExoPlayer following the video timeline (re-seeks on >200 ms drift, volume from `gainAt` every tick).
- Export: music is a second, audio-only `EditedMediaItemSequence` mixed by Transformer; `GainAudioProcessor` (16-bit PCM) applies the fade envelope and the original-sound volume.
- Licensing: the dialog reminds users to use music they may publish; Spotify audio can't be exported (Stage 7 Spotify = discovery only).

## UI redesign & design system (2026-11-11)

Owner request: OriginOS-7-inspired redesign of the whole app + persistent Light/Dark/System theme. Original design (no copied assets).

**Audit findings that drove it:** dark-only theme (light palette was a stub; window theme hard-wired dark); ~13 hard-coded colours in screens and fixed track colours; corner radii 6–16 dp mixed; duplicated Section/ChipRow/Card helpers per screen; mixed AlertDialogs and inline panels; nested Scaffolds; no transitions or haptics; editor clip strip not time-scaled, no playhead, music not visible.

**Design system (`core/ui`):**
- `theme/Tokens.kt`: `CineMindColors` semantic tokens (backdrop gradient, surface / glass / raised / sunken, hairline, text primary/secondary/tertiary, accent + gradient, highlight, success/warning/danger, video stage, timeline lane, track video/music, clip label, playhead) for **light and dark**; `Spacing`, `Radius`, `ThemeMode` (SYSTEM / LIGHT / DARK).
- `theme/CineMindTheme.kt`: `CineMindTheme(mode)` + `CineMindTheme(colors)`; Material 3 colour scheme derived from tokens; type scale (system font, which is Vivo's UI font on the target phone); shapes. `CineMind.colors` accessor.
- `components/Components.kt`: `AppBackground` (gradient + accent glow), `GlassCard` (translucent + hairline; true backdrop blur is not available in Compose without extra dependencies, so "glass" = layered translucency), `ScreenHeader`, `SectionHeader`, `ChoicePills`/`Pill`, `GradientButton`, `StatusPill`/`Tone`, `ToolButton`, `EmptyState`, `CineMindSheet` (bottom sheet), `SettingRow`, `pressScale` (spring press feedback) and `hapticClick` (haptic tick). All interactive pieces have ≥ 48 dp targets and semantics roles.
- **Contrast is tested:** `ThemeContrastTest` checks every text/background pairing (incl. glass composited over both backdrop stops, pills, accent containers, gradient buttons, clip labels, stage controls, playhead 3:1) in both themes against WCAG AA. It caught 4 failing light-theme colours (accent, highlight, success, music track), which were darkened.

**Theme system:** `feature/settings/appearance/AppearanceRepository` (SharedPreferences: read synchronously at start → no wrong-theme flash; observed as a Flow → whole app recomposes instantly). On Android 12+ it also calls `UiModeManager.setApplicationNightMode`, so window background / splash / system dialogs follow. `values` + `values-night` window themes. Status/navigation bar icon colours follow the app theme (`enableEdgeToEdge(SystemBarStyle.auto { dark })`). Settings screen (gear on home) shows three live mini-previews rendered with the real tokens (System = light|dark split).

**Screens redesigned (all on the shared components, no raw colours):** app shell (transparent top bar over the gradient backdrop, AI + Settings actions, fade/slide navigation transitions), Home (header, New-project card, glass project cards with cover, aspect pill, menu; empty state), Media library (header with Import, rounded tiles with press/haptic, accessible selection, floating glass selection bar), Smart edit brief (glass prompt card, idea pills, choice pills, notices, pulsing processing state), On-device AI (model cards with status pills, options, benchmark card), Export (choice pills, estimate card, progress ring, success/failed cards), Settings (new), **Editor** (see below).

**Editor:** compact header with undo/redo; large rounded black preview stage with a glass play/timecode control and the existing transition preview; themed scrubber; new **`MultiTrackTimeline`**: time ruler (tap to seek, adaptive tick spacing), video lane with time-proportional clip blocks (thumbnail, index/length, transition marker), music lane (song or "Add music"), playhead with knob that follows playback, zoom −/+ (8 levels), **drag handles on the selected clip to trim** (haptic on grab, committed on release, one undo step); `TimelineLayout` (pure geometry, `TimelineLayoutTest` 6). Clip inspector is a compact glass panel (move, split, remove, close; precise trim slider; transition pills). Transitions and Music are bottom sheets. Tools bar is a glass dock.

**Verification:** unit tests (now 131) incl. contrast (+ selection token) + timeline geometry + compact time format; Compose **screenshot tests** on the CI emulator render the design-system gallery and the editor controls in light and dark (`DesignSystemScreenshotTest`, `EditorScreenshotTest`; PNGs uploaded as `ui-screenshots-<run>`), with assertions that key controls are displayed.

**Screenshot review (CI emulator, 320 dp — narrower than the Vivo’s ~420 dp) found and fixed:** "Problem" status pill wrapping mid-word; inspector header squeezing the close button; crowded trim timecodes; muddy brown light-theme selection (new `selection` token); "Export" label clipped in the tools bar; selected clip label hidden under the trim handle. Remaining at 320 dp only: "Transitions" shortens to "Transitio…".

**Not verified / not done:** full screens with live data were not rendered in tests (they need Hilt + database); nothing has been checked on the Vivo yet; no real backdrop blur; 120 Hz is supported by keeping animations GPU-only (scale/alpha/translation) but frame timing was not measured; the timeline has two lanes (video + music) — more video/overlay lanes and cross-dissolves need the Stage 3 multi-track engine; pinch-to-zoom not implemented (buttons instead); landscape/tablet layouts are usable but not specially tuned; `launch(NonCancellable)` usages now warn under coroutines 1.11 (behaviour unchanged; refactor pending).

## On-device AI (Stage 4, as built)

- **Runtime:** Google **LiteRT-LM** `litertlm-android` 0.19.1 (MediaPipe LLM Inference is in maintenance mode). `OnDeviceLlm` (`feature/ai/llm/ondevice`): one `Engine` kept loaded until `unload()`, GPU backend with automatic CPU fallback (user can force CPU), vision backend for image input, `maxNumImages = 1`, near-greedy sampling, a fresh `Conversation` per request, one request at a time (mutex). Manifest declares optional `libOpenCL.so` / `libvndksupport.so`.
- **Models** (`ModelCatalog`): from `litert-community` on Hugging Face, Apache-2.0, ungated (no token):
  - Gemma 4 E2B `gemma-4-E2B-it.litertlm`, 2,588,147,712 B, sha256 `181938…63c` (default).
  - Gemma 4 E4B `gemma-4-E4B-it.litertlm`, 3,659,530,240 B, sha256 `0b2a89…2e0`.
  - Spec's preferred Qwen2.5-VL-7B: no LiteRT-LM build and too heavy for a phone; replaced per spec §5 ("benchmark practical alternatives"). Decision to confirm with the Vivo benchmark.
- **Model manager:** `ModelDownloadManager` + `ModelDownloadService` (foreground, dataSync): resumable HTTP Range download into `filesDir/models/*.part`, Wi-Fi-only default, free-space check (+500 MB margin), 5 retries with back-off, pause/resume/discard/delete, **SHA-256 verified before install** (mismatch deletes the file). `ModelStore` tracks verified files; `AiPreferences` (use AI in Smart edit, Wi-Fi only, prefer GPU, selected model).
- **Benchmark** (`ModelBenchmark`): cold load, short text answer, one image analysis of a synthetic frame (no user media), free RAM before/after, thermal status; copyable report for the owner to send back.
- **Smart edit with AI** (`feature/ai/generation/smartedit`): if a model is installed and enabled → load → `Prompts.brief` turns the prompt into JSON (style, pacing, target_seconds, order, transition, strict_quality), merged by `AiAssist.mergeBrief` (explicit brief settings win, then model, then keywords) → up to 3 key frames per source (best measured sample per third; max 24 total) sent to `Prompts.FRAME` → `ShotInsight` (subject, scene, shot size, people, quality, interest; tolerant JSON parsing) → planner multiplies window scores by `AiAssist.weight` and penalises the same scene from different clips → decisions list what the AI saw. Model is unloaded afterwards; any failure falls back to the measurement-only edit with a note. Results are named "… · AI edit".
- Tests: `PromptsTest` (9), `AiAssistTest` (8). Not testable in CI: actual inference (needs the 2.6 GB model and a phone GPU).
- Privacy: inference is fully on-device; only the model file is downloaded (from huggingface.co). No user media leaves the phone.

## Split

- `TimelineEditing.split()` (pure, `SplitTest` 3): splits the selected clip at the playhead into two clips that play identically; the incoming transition stays on the first part, an ending fade moves to the second. Parts shorter than 0.2 s are refused.

## Next recommended task

1. **Owner runs the AI benchmark on the Vivo X200 Pro** (✦ on the home screen → download Gemma 4 E2B on Wi-Fi → Run benchmark → Copy report) and sends the report. Decide E2B vs E4B and GPU vs CPU from it.
2. Owner tries Smart edit with the model installed (named "… · AI edit"); report quality, time and heat.
3. Tune from real numbers: frames per source, image size (768 px), prompt wording; consider keeping the engine warm between runs if load time is high.
4. Stage 3: multi-track timeline (real cross-dissolves), speed changes, beat markers / beat sync, color + LUTs, voice-over.
5. Captions / Hindi-Hinglish speech-to-text: needs the updated master spec; Gemma 4 E2B/E4B also accept audio, so on-device transcription can reuse this runtime.
