# CineMind AI — Project Status

> Handoff document for any Claude / Codex / ChatGPT session. Update after every milestone.
> Never put API keys, keystore passwords or tokens in this file.

_Last updated: 2026-11-11_

## Current position

| | |
|---|---|
| **Stage** | Stage 1 — Project foundation (in progress) |
| **Last completed milestone** | Editor preview (2026-11-11): ExoPlayer playback of the clip sequence with play/pause, scrubbing, timecode, current-clip highlight. Built and unit-tested in CI; **not yet verified on a device**. |
| **Previous milestones** | Media import (run 43013511655); foundation repair, first green build (run 43011809441). |
| **Current task** | Device check of import + preview, then project list. |
| **Branch** | `dev-main` |
| **Latest verified build** | CI run 43015369603 (#14), commit `a3a732c`, 29/29 unit tests passed — https://github.com/chandrabrav/cinemind/actions/runs/43015369603 |
| **Latest verified APK** | Artifact `cinemind-debug-14` (19.3 MB, expires 2026-12-11). Built, not yet device-tested. |

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
- Room schema is **v2** (`MIGRATION_1_2` adds `media_assets.displayName`, `sizeBytes`). **No destructive migration.** Exported schema JSON is committed in `core/data/schemas/` — after any schema change, fetch the new JSON from the `room-schemas-<run>` artifact (`ci-verify.sh <run> /mnt/d/projects/cinemind/core/data/schemas`) and commit it.
- Media: system Photo Picker for import; `MediaMetadataRetriever` + `MediaExtractor` for metadata; Coil 2.7 + `coil-video` for thumbnails (app-wide `ImageLoader` in `CineMindApplication`).
- Preview: Media3 1.5.1 ExoPlayer + `PlayerView` (via `AndroidView`). `PreviewPlayer` maps each clip to one playlist item — video clips use `ClippingConfiguration` (source in/out), stills and missing media use `SilenceMediaSource` of the clip duration with the image drawn on top in Compose. `TimelineMath` converts timeline positions ↔ (clip index, offset). Preview is playback only; it does not render effects/transitions or write files.

Modules compiled in CI (`settings.gradle.kts`):

| Module | Contents |
|---|---|
| `:app` | Activity, nav host (`media_library` → `editor/{projectId}`), Hilt modules, Coil setup |
| `:core:common` | Logger, constants |
| `:core:domain` | Room entities (Project/Track/Clip/MediaAsset), `ProjectFactory` (selection → project + track + clips), repository interfaces, AI analysis models |
| `:core:data` | Room DB + migrations, DAOs, repository implementations (`createProjectWithTimeline` is one transaction) |
| `:core:ui` | Theme, colors, typography, `formatTimecode` |
| `:feature:media` | `MediaImporter` (picker URI → `MediaAsset`), `MediaLibraryViewModel`, library screen (grid, ordered multi-select, create project) |
| `:feature:editor:timeline` | Editor screen: `PreviewPlayer` + `TimelineMath` (preview), `TimelineViewModel` (owns/releases the player), transport controls, clip strip |
| `:feature:export` | Export foreground service (stub) |
| `:feature:settings` | Settings DataStore |
| `:feature:ai:{analysis,llm,generation,cloud}` | Interfaces / data classes only |

Scaffolded but **excluded** until they get code: `:feature:editor:{preview,effects,color,audio}`, `:feature:project`, `:media3`, `:benchmark`.

## Feature status

| Feature | Status |
|---|---|
| Branding + dark theme | Partial (colors/typography, adaptive launcher icon; no full design system yet) |
| Media import (Photo Picker, multi-select, thumbnails, metadata) | Implemented; pure logic unit-tested; **not device-verified** |
| Create project from selection | Implemented; `ProjectFactory` unit-tested; **not device-verified** |
| Video preview | Implemented (play/pause, scrub, timecode, current-clip highlight, tap clip to jump, pause on background); `TimelineMath` unit-tested; **not device-verified** |
| AI prompt screen | Not started |
| Editor workspace / timeline | Partial: preview + clip strip in order; no zoomable timeline, playhead on tracks, trim or reorder |
| Trim / reorder | Not started |
| Export (Media3 Transformer) | Stub service only |
| Project storage | Partial: Room v2 with migration; atomic project creation; no project list, autosave, recovery or versioned project file |
| AI (all) | Interfaces only |
| Tests | 29 unit tests: `TimecodeTest` (5), `ProjectFactoryTest` (9), `MediaMetadataParsingTest` (6), `MediaLabelsTest` (2), `TimelineMathTest` (7). No instrumented/UI tests yet. |

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
- No project list yet: after leaving the editor, a project can't be reopened from the UI.
- Preview: switching between clips goes through ExoPlayer's playlist, so there may be a brief stall at cuts (not gapless-guaranteed). If a clip fails to decode, playback stops with an error label; pressing Play retries. No preview of transitions, speed, color or audio mixing yet.
- Preview duration per clip comes from stored metadata; if a file's real duration is slightly shorter, the playhead may jump a few ms at that cut.
- Assets can't be removed from the library yet (clips reference them with `RESTRICT`).
- Only the v2 schema JSON exists (v1 was never exported), so a v1→v2 Room migration test can't be written. No device ever ran v1.
- Domain module carries Room annotations (`room-common`) — split entities from domain models when the project format is versioned.
- No Gradle wrapper committed.
- CI actions are on deprecated Node 20 majors (`setup-java@v4` etc.); bump soon.
- Updated master spec missing (captions / Hindi-Hinglish / STT / graphics requirements unknown).

## Next recommended task

Foundation milestone, each verified on the Vivo X200 Pro:
1. ~~Photo Picker multi-select import with metadata + thumbnails~~ (done; needs device check)
2. ~~Create project from selected clips~~ (done; needs device check)
3. ~~ExoPlayer preview of the clip sequence~~ (done; needs device check)
4. **Next:** Project list on the home screen (reopen / delete projects; never deletes source media).
5. AI prompt input screen (UI + stored prompt; no AI yet).
6. Timeline: trim + reorder with an undoable command model, unit-tested.
7. Media3 Transformer export of the edited sequence to an MP4 in `Movies/CineMind`, playable in the gallery.
