# CineMind AI — Project Status

> Handoff document for any Claude / Codex / ChatGPT session. Update after every milestone.
> Never put API keys, keystore passwords or tokens in this file.

_Last updated: 2026-11-11_

## Current position

| | |
|---|---|
| **Stage** | Stage 1 — Project foundation (in progress) |
| **Last completed milestone** | Timeline editing (2026-11-11): select a clip, trim (video in/out, photo length), move earlier/later, remove, with undo/redo; every edit saved to Room. Built and unit-tested in CI; **not yet verified on a device**. |
| **Previous milestones** | AI edit brief (run 43019053443); project list (run 43017020090); editor preview (run 43015369603); media import (run 43013511655); foundation repair (run 43011809441). |
| **Current task** | Device check of the whole flow; then Media3 Transformer export (last item of the foundation milestone). |
| **Branch** | `dev-main` |
| **Latest verified build** | CI run 43020346309 (#27), commit `5a254cb`, 60/60 unit tests passed — https://github.com/chandrabrav/cinemind/actions/runs/43020346309 |
| **Latest verified APK** | Artifact `cinemind-debug-27` (19.4 MB, expires 2026-12-11). Built, not yet device-tested. |

## Build environment (hard rule)

- All Android builds run in **GitHub Actions** (`.github/workflows/ci.yml`). Nothing Android-related is installed locally (owner's machine is Windows; no Android Studio / SDK / JDK / Gradle).
- CI provisions **Gradle 8.11.1** via `gradle/actions/setup-gradle` (no wrapper committed yet) and Temurin **JDK 17**.
- Every push builds: `gradle testDebugUnitTest` then `gradle assembleDebug`. Artifacts: `cinemind-debug-<run>` (APK, 30 days), `test-reports-<run>`, `room-schemas-<run>` (14 days).
- Release APK: only on `main` and only when repo secrets `CINEMIND_KEYSTORE_BASE64`, `CINEMIND_KEYSTORE_PASSWORD`, `CINEMIND_KEY_ALIAS`, `CINEMIND_KEY_PASSWORD` exist.
- Free tier for private repos: 2,000 Actions minutes/month; one build ≈ 5–10 min.
- `gh` is authenticated inside **WSL (Ubuntu-24.04)**, not in Windows. Helper scripts (outside the repo) in `D:\projects\cinemind-local-tools\`:
  - `ci-watch.sh <branch>` — waits for the latest run on a branch, prints result and compile errors.
  - `ci-verify.sh <run-id> [schemas-dest]` — per-module test counts, artifact sizes, optionally copies Room schemas.
  - Run as `wsl -d Ubuntu-24.04 -- bash /mnt/d/projects/cinemind-local-tools/<script> ...`

## History recovered (2026-11-11 audit)

- Nov 2–4: ~2,300 lines of scaffold generated and merged to `main` (PRs 1–3). It never compiled; every CI run failed.
- Nov 4–11: a previous session tried to build **locally on Windows**, committing a JDK and Gradle distribution (files > 100 MB, unpushable) and repeatedly rewriting the version catalog with PowerShell scripts until it was 0 bytes.
- Nov 11 repair: those 2 unpushed commits were undone (old tip `8e44dbb` kept in local reflog); the toolchain and scripts were moved to `D:\projects\cinemind-local-tools\` (outside the repo) and are git-ignored.
- No prior Claude transcript or handoff doc was available; the updated master spec (`CineMind_AI_Master_Prompt_UPDATED(1).txt`) has **not** been provided yet — only the original `CineMind_AI_Master_Prompt.txt` was used.

## Architecture (as built)

- Kotlin 2.1.0, AGP 8.7.3, compileSdk/targetSdk 35, minSdk 30, Java 17.
- UI: Jetpack Compose + Material 3 (BOM 2024.12.01), Navigation Compose. Dark-first theme in `core/ui`.
- DI: Hilt 2.54 (KSP). Storage: Room 2.6.1, DataStore Preferences.
- Room schema is **v3**: `MIGRATION_1_2` adds `media_assets.displayName`, `sizeBytes`; `MIGRATION_2_3` creates `edit_briefs`. **No destructive migration.** Exported schema JSON (v2, v3) is committed in `core/data/schemas/` — after any schema change, fetch the new JSON from the `room-schemas-<run>` artifact (`ci-verify.sh <run> /mnt/d/projects/cinemind/core/data/schemas`), compare its `createSql` with the hand-written migration, and commit it.
- Media: system Photo Picker for import; `MediaMetadataRetriever` + `MediaExtractor` for metadata; Coil 2.7 + `coil-video` for thumbnails (app-wide `ImageLoader` in `CineMindApplication`).
- Preview: Media3 1.5.1 ExoPlayer + `PlayerView` (via `AndroidView`). `PreviewPlayer` maps each clip to one playlist item — video clips use `ClippingConfiguration` (source in/out), stills and missing media use `SilenceMediaSource` of the clip duration with the image drawn on top in Compose. `TimelineMath` converts timeline positions ↔ (clip index, offset). Preview is playback only; it does not render effects/transitions or write files.

Modules compiled in CI (`settings.gradle.kts`):

| Module | Contents |
|---|---|
| `:app` | Activity, nav host (`media_library` → `editor/{projectId}`), Hilt modules, Coil setup |
| `:core:common` | Logger, constants |
| `:core:domain` | Room entities (Project/Track/Clip/MediaAsset/EditBrief), `ProjectFactory`, `ProjectFormat`, `EditBriefRules`, `ProjectNames`/`ProjectCopier`, `editing.TimelineEditing` (trim/move/delete, ripple relayout) + `editing.EditHistory` (undo/redo), repository interfaces, AI analysis models |
| `:core:data` | Room DB + migrations, DAOs, repository implementations (`createProjectWithTimeline` is one transaction) |
| `:core:ui` | Theme, colors, typography, `formatTimecode` |
| `:feature:media` | `MediaImporter` (picker URI → `MediaAsset`), `MediaLibraryViewModel`, library screen (grid, ordered multi-select, create project) |
| `:feature:project` | Home screen: `ProjectListViewModel`, project list (open, rename, duplicate, delete with confirmation) |
| `:feature:editor:timeline` | Editor screen: `PreviewPlayer` + `TimelineMath` (preview), `TimelineViewModel` (owns the player; holds the edit session/undo history; writes each edit to Room), `EditPanels` (edit toolbar, clip inspector), transport controls, clip strip, "AI brief" entry |
| `:feature:export` | Export foreground service (stub) |
| `:feature:settings` | Settings DataStore |
| `:feature:ai:generation` | AI edit brief screen + `EditBriefViewModel` (autosave); legacy generation model classes |
| `:feature:ai:{analysis,llm,cloud}` | Interfaces / data classes only |

Scaffolded but **excluded** until they get code: `:feature:editor:{preview,effects,color,audio}`, `:media3`, `:benchmark`.

## Feature status

| Feature | Status |
|---|---|
| Branding + dark theme | Partial (colors/typography, adaptive launcher icon; no full design system yet) |
| Media import (Photo Picker, multi-select, thumbnails, metadata) | Implemented; pure logic unit-tested; **not device-verified** |
| Create project from selection | Implemented; `ProjectFactory` unit-tested; **not device-verified** |
| Video preview | Implemented (play/pause, scrub, timecode, current-clip highlight, tap clip to jump, pause on background); `TimelineMath` unit-tested; **not device-verified** |
| Project list (home) | Implemented: cover, clip count, length, aspect, last edited; open / rename / duplicate / delete. `ProjectNames` + `ProjectCopier` unit-tested; **not device-verified** |
| AI prompt screen | Implemented: prompt (1000 chars, suggestions), style, length, pacing, format; autosave + save on leave; guidance notices. `EditBriefRules`/`ProjectFormat` unit-tested. "Create AI edit" is disabled until Stage 4–5. **Not device-verified** |
| Editor workspace / timeline | Partial: preview, clip strip with selection, inspector, undo/redo. Single video track only; no zoomable multi-track timeline, playhead on tracks, drag-to-reorder, split or snapping yet |
| Trim / reorder / delete | Implemented with undo/redo (100 steps, in memory per editor session); ripple edit keeps clips back to back; `TimelineEditing` + `EditHistory` unit-tested (14 tests); **not device-verified** |
| Export (Media3 Transformer) | Stub service only; editor shows "Export (soon)" disabled |
| Project storage | Partial: Room v3 with migrations; edit brief per project (copied on duplicate, deleted with project); atomic create and duplicate; rename; delete (cascades tracks/clips, keeps media); no autosave, crash recovery or versioned project file |
| AI (all) | Interfaces only |
| Tests | 60 unit tests: core/domain 40 (`ProjectFactoryTest` 9, `ProjectNamesTest` 4, `ProjectCopierTest` 4, `ProjectFormatTest` 3, `EditBriefRulesTest` 6, `TimelineEditingTest` 10, `EditHistoryTest` 4), core/ui 6, feature/media 7, feature/editor/timeline 7. No instrumented/UI, Room DAO or migration tests yet. |

## Architecture decisions

1. **Cloud-only builds** via GitHub Actions; no local toolchain, nothing vendored into git.
2. Keep the existing modular layout but only compile modules that contain code.
3. Media import uses the **Photo Picker** → no storage permissions; removed CAMERA, MEDIA_PROJECTION, legacy storage, READ_MEDIA_*, RECORD_AUDIO until a feature needs them.
4. Originals are **referenced by URI, never copied or modified**; a persistable read grant is requested at import. Re-importing the same URI reuses the existing asset.
5. Clip order = the order the user taps items; images default to 3 s on the timeline. Project orientation/resolution follow the first item (9:16 1080×1920 or 16:9 1920×1080); frame rate snaps to 24/25/30/60 from the first video.
6. **API keys never go in DataStore**; Keystore-backed storage arrives with cloud AI (Stage 6).
7. No destructive Room migrations; every schema change ships a `Migration` and a committed schema JSON.
8. ktlint/detekt plugins removed for now to get a first green build; configs remain in `config/` to re-enable later.

## Known issues / debt

- Photo EXIF orientation is not applied to stored width/height (thumbnails are still correct). Matters only when a rotated photo is the first item of a project.
- If a provider refuses a persistable URI grant, that media is readable only until the app restarts (logged as a warning). Missing-media handling is not built yet.
- Project list query, rename/duplicate/delete are not covered by automated DAO tests (would need Robolectric or instrumented tests); verify on device.
- Undo history lives in memory: it is lost when you leave the editor (the edits themselves are saved). Trims commit when the finger lifts; the preview does not show the frame under the trim handle while dragging.
- Reordering uses move-earlier/later buttons, not drag-and-drop. No split yet.
- Preview: switching between clips goes through ExoPlayer's playlist, so there may be a brief stall at cuts (not gapless-guaranteed). If a clip fails to decode, playback stops with an error label; pressing Play retries. No preview of transitions, speed, color or audio mixing yet.
- Preview duration per clip comes from stored metadata; if a file's real duration is slightly shorter, the playhead may jump a few ms at that cut.
- Assets can't be removed from the library yet (clips reference them with `RESTRICT`).
- No v1 schema JSON exists (v1 was never exported; no device ran it). v2 and v3 JSON exist, so a v2→v3 `MigrationTestHelper` test is now possible but needs instrumented tests (emulator in CI) — not set up yet.
- The brief does not yet capture caption language or Hindi/Hinglish options: the updated master spec that defines those is still missing.
- Domain module carries Room annotations (`room-common`) — split entities from domain models when the project format is versioned.
- No Gradle wrapper committed.
- CI actions are on deprecated Node 20 majors (`setup-java@v4` etc.); bump soon.
- Updated master spec missing (captions / Hindi-Hinglish / STT / graphics requirements unknown).

## Next recommended task

Foundation milestone, each verified on the Vivo X200 Pro:
1. ~~Photo Picker multi-select import with metadata + thumbnails~~ (done; needs device check)
2. ~~Create project from selected clips~~ (done; needs device check)
3. ~~ExoPlayer preview of the clip sequence~~ (done; needs device check)
4. ~~Project list on the home screen~~ (done; needs device check)
5. ~~AI prompt input screen~~ (done; needs device check)
6. ~~Timeline: trim + reorder with undo~~ (done; needs device check)
7. **Next:** Media3 Transformer export of the edited sequence to an MP4 in `Movies/CineMind`, playable in the gallery (completes the foundation milestone).
