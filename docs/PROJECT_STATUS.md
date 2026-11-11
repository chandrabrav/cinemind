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
| **Captions & speech (new, 2026-11-11)** | Editable caption layer (timeline lane, preview overlay, burned into export by the same painter), 7 style presets + full styling, sentence/phrase/word modes, karaoke highlight, animations; on-device speech-to-text with Gemma 4's audio encoder: Hinglish / Roman / native script / English translation as separate operations; Devanagari→Roman transliterator. See "Captions & speech-to-text". **Speech recognition itself is not yet run on a phone.** |
| **Branding (new)** | "CineMind AI" on launch (wordmark after the system splash), home title and Settings → About CineMind AI (logo, version + build, "Developed by Gautam", description, privacy facts, open-source licences). |
| **Current task** | Owner tests build #82 on the Vivo (captions by hand, export with captions, About; speech-to-text with Gemma 4 E2B installed) and sends the AI benchmark report. Next milestone: see "Audit for the professional-editor brief". |
| **Branch** | `captions` (from `dev-main` = `main` at `110e809`; PR #16 merged dev-main → main and fixed main's red CI) |
| **Latest verified build** | CI run 43071502601 (#82), commit `baa29f9`: **all green** — 164 unit tests + 12 instrumented (export e2e ×3 incl. caption burn-in, speech audio ×3, screenshots ×6) — https://github.com/chandrabrav/cinemind/actions/runs/43071502601 — artifact `cinemind-debug-82` |
| **Previous fully verified build** | CI run 43061916979 (#73), commit `82f97fa`: build + 131 unit + 6 instrumented tests. |

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
- Room schema is **v5**: `MIGRATION_1_2` (`media_assets.displayName`, `sizeBytes`), `MIGRATION_2_3` (`edit_briefs`), `MIGRATION_3_4` (`clips.transitionInType`), `MIGRATION_4_5` (`captions`, `caption_styles`). v5 JSON: fetch from `room-schemas-<run>` and commit. **No destructive migration.** Exported schema JSON is committed in `core/data/schemas/` — after any schema change, fetch the new JSON from the `room-schemas-<run>` artifact, compare its `createSql` with the hand-written migration, and commit it.
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
| AI with models (VLM/LLM) | On-device Gemma 4 via LiteRT-LM: Smart edit (brief + key frames) and speech-to-text. Not run on a phone yet |
| Cloud AI | Not started (module has data classes only) |
| Captions | **Implemented**: caption layer (Room v5), manual add/edit/retime/split/delete, undo/redo, autosave, styles + 7 presets, preview overlay, export burn-in. Unit-tested; export burn-in emulator test added |
| Speech-to-text / Hindi-Hinglish | **Implemented, not device-tested**: audio decode + speech detection (emulator-tested), Gemma 4 audio transcription, language tag, 4 output modes, transliteration fallback, translation as a separate step. Word timings are estimated |
| Contextual graphics / text layers | Not started |
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
| 43057701381 | `3e628b1` (main) | e2e ✗ | main had the pre-fix screenshot test; fixed by PR #16 (dev-main → main, `110e809`) |
| 43070384841 | `d705d99` | unit ✗ | Captions: class-init cycle `CaptionStyle` default ↔ `CaptionPresets` (NPE) → literal default id |
| **43071502601 (#82)** | `baa29f9` | **all green** | Captions + branding: 164 unit tests; emulator: export ×3 (incl. caption burn-in, frame reviewed), speech audio ×3, screenshots ×6 (design system, editor with caption lane, About; light + dark) |

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

## Captions & speech-to-text (2026-11-11, branch `captions`)

- **Model (`core/domain/captions`)**: `Caption` (timeline start/end, text, per-word times, language, script, source SPEECH/MANUAL/TRANSLATION, `uncertain`) and `CaptionStyle` (one per project: preset, font, size as fraction of the short side, weight, caps, text/highlight colours, outline, shadow, box colour/opacity, position inside the 8–92 % safe area, alignment, mode, karaoke, animation). `CaptionPresets`: Reels & Shorts, Travel, Cinematic, Education, Podcast & interview, Vlog, Music video. `CaptionLayout.frameAt()` decides what is on screen at any time (sentence / phrase ≤4 words, breaks at punctuation and pauses / word-by-word, karaoke word, fade/pop/slide/typewriter) — **one source of truth for preview and export**. `CaptionEditing`: retime (clamped, no overlap, rescales word times), edit text, insert at playhead, split. `WordTimes`: storage + length-weighted estimate.
- **Transliteration** (`Transliterator`): Devanagari → Latin letters with Hindi schwa deletion (करना → karna, जगह → jagah, ज़िंदगी → zindagi) and common chat spellings (में → mein, है → hai). Never translates.
- **Speech-to-text** (`feature/captions/speech`): `AudioExtractor` (MediaCodec decode of only the trimmed range → 16 kHz mono via `StreamingResampler`) → `SpeechSegmenter` (loudness with adaptive noise floor; gives each caption real start/end; splits >12 s) → each stretch as WAV to **Gemma 4 E2B/E4B's audio encoder** (`OnDeviceLlm.ask(audioFile=…)`; the audio encoder loads only when needed) with `SpeechPrompts.transcribe(language, script)` → `parse()` (LANG + TEXT, `[?]` → uncertain, NO_SPEECH, loop collapse, Devanagari → Roman fallback when Roman was asked) → optional **separate** English translation → captions at timeline time.
  - Output modes: original script, Roman, Hinglish (keeps "Bhai, yeh jagah sach mein bahut beautiful hai!" as spoken), English translation. Languages offered: auto, English, Hindi, Hinglish, Punjabi, Bengali, Tamil, Telugu, Urdu, Marathi.
  - **Evaluation status:** Gemma 4 is used because its audio encoder already ships in the downloaded model (no extra download). Its accuracy for Hindi/Hinglish, the other languages and lyrics is **unmeasured**; word timings are estimated, not recognised; no speaker diarization. Dedicated ASR alternatives to benchmark next: Whisper (whisper.cpp, small/medium multilingual; needs NDK build in CI) and IndicConformer-class models for Indic languages. Lyrics/heavy music are flagged as unreliable in the UI.
- **Editor**: Captions tool (and caption lane tap) opens a sheet: speech-to-text (language, script, progress, cancel, summary), add at playhead, edit text, ±0.1 s start/end, split, delete, translate all, clear, undo/redo; Style tab with live animated sample and safe-area guides. Captions draw over the preview with `CaptionPainter` (`core/ui`). Export is also in the editor header; the tools bar scrolls when 6 tools don't fit.
- **Export**: `CaptionBitmapOverlay` (Media3 `BitmapOverlay`, lazily allocated frame-sized bitmap, redrawn only when the frame changes) added last to each clip's effects.
- **Tests**: `TransliteratorTest`, `WordTimesTest`, `CaptionLayoutTest`, `CaptionEditingTest` (domain); `PcmTest`, `SpeechSegmenterTest`, `SpeechPromptsTest` (captions); emulator: `SpeechAudioTest` (real AAC decode, trimmed range, burst timing, unreadable file) and `ExportEndToEndTest.burnsInCaptionsAtTheirTimes` (pixels in the caption band only while the caption is on; frame saved as `export_caption_frame.png`).

## Branding

- Text exactly: **CineMind AI** / **Developed by Gautam** (`feature/settings/ui/AboutScreen.kt` → `Branding`). About screen: launcher logo, name, version + build number (CI run number), credit (smaller than the brand), description, factual privacy notes (no invented policy, contacts or company), open-source licences of the actual dependencies. Launch: system splash (icon) then a 1 s wordmark with the credit, tap to skip, cold start only. `AboutScreenshotTest` (light + dark).

## Audit for the professional-editor brief (2026-11-11)

Labels: **T** implemented and tested · **I** implemented, not fully tested · **P** partial · **D** data field only (stored, not used) · **N** not implemented.

| Area | State | Notes |
|---|---|---|
| Import, projects, autosave, duplicate, safe delete | T (emulator/unit), import confirmed on Vivo | No missing-media relink (N), no snapshots (N) |
| Timeline model | P | Tables support many tracks (`TrackType` VIDEO/AUDIO/EFFECT/OVERLAY) but the editor uses **one video track + one music clip**; ripple-only, no overlap, no gaps |
| Clip speed / position / scale / rotation / opacity | **D** | `Clip.speed`, `transformX/Y`, `scale`, `rotation`, `opacity` exist in the DB but neither preview nor export applies them |
| Manual edit: trim, split, delete, reorder, undo/redo | T | Duplicate, ripple-delete toggle, replace, freeze, crop, flip, detach audio: N |
| Timeline zoom | P | 8 button levels; no pinch, no frame-level ruler |
| Preview | I | ExoPlayer playlist of clipped items + Compose transition approximation. **Cuts can stutter**: each clipped item is a separate media period (decoder flush/seek at every boundary). Preview ≠ export pipeline |
| Transitions | T (7 types) | No overlap → no true dissolve/crossfade |
| Speed / ramps / reverse / freeze | N | Media3 1.5.1 only has experimental speed effects; ramps need a time-remapping model |
| Colour / LUT | N | |
| Text layers | N | Captions exist (T/I); free text titles not yet |
| Audio | P | One music track + original volume, fades; no multiple tracks, voiceover, waveforms |
| Beat detection | N | |
| Cutout / masks / chroma | N | |
| Smart edit | I | Single prompt (1,000-char limit), creates a new project; not conversational |
| Model manager | T/I | Resume, Wi-Fi only, SHA-256, storage check, delete exist; versioning info minimal |
| Export | T | 720p–4K, 24–60 fps, **H.264 only**, SDR; captions burn-in |
| Spotify / cloud AI | N | |

**Architectural limits:** (1) one-video-track ripple model can't express overlaps, B-roll, PiP, text layers or dissolves; (2) preview and export are different pipelines, so every new effect must be written twice; (3) Media3 1.5.1 lacks the mature `CompositionPlayer`, multi-sequence video compositing and speed APIs that newer Media3 releases have.

**Proposed order (adjusted from the brief, with reasons):**
1. **M2 timeline interaction first (low risk, UI-only):** pinch-to-zoom anchored at the fingers, continuous zoom, adaptive ruler down to frames, zoom-to-fit/selection, jump to start/end. Doesn't touch the data model, so it can ship while M1 is designed.
2. **M1 core architecture:** upgrade Media3 (1.5.1 → current stable) in its own PR with the export e2e tests as the safety net; new versioned timeline model (multiple tracks, items with start/in/out, lanes, z-order, hidden/locked/muted, per-item transform + keyframes + speed map) with a migration from v5; **preview moved to Media3 `CompositionPlayer`** so preview and export share one renderer (fixes cut stutter and doubles as the consistency guarantee).
3. M3 transforms + speed (constant, ramps, reverse, freeze) on the new model; M4 transitions library with overlap; M5 multi-audio + beat detection; M6 colour/LUT + text layers; M7 overlays/PiP/masks/cutout; M8 conversational assistant + AI planning on the new model; M9–M10 advanced + release.
Acceptance for each: unit tests for the maths, emulator export test that inspects the rendered MP4, screenshot test in both themes, PROJECT_STATUS update.

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

0. **Owner:** test build #82 (captions, About, speech-to-text with Gemma 4 E2B) and send the AI benchmark report.
0. **Dev:** M2 timeline interaction (pinch zoom, adaptive frame ruler, zoom-to-fit/selection), then M1 (Media3 upgrade → CompositionPlayer preview → multi-track model). See "Audit for the professional-editor brief".
1. **Owner runs the AI benchmark on the Vivo X200 Pro** (✦ on the home screen → download Gemma 4 E2B on Wi-Fi → Run benchmark → Copy report) and sends the report. Decide E2B vs E4B and GPU vs CPU from it.
2. Owner tries Smart edit with the model installed (named "… · AI edit"); report quality, time and heat.
3. Tune from real numbers: frames per source, image size (768 px), prompt wording; consider keeping the engine warm between runs if load time is high.
4. Stage 3: multi-track timeline (real cross-dissolves), speed changes, beat markers / beat sync, color + LUTs, voice-over.
5. Captions / Hindi-Hinglish speech-to-text: needs the updated master spec; Gemma 4 E2B/E4B also accept audio, so on-device transcription can reuse this runtime.
