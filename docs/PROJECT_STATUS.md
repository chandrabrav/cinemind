# CineMind AI — Project Status

> Handoff document for any Claude / Codex / ChatGPT session. Update after every milestone.
> Never put API keys, keystore passwords or tokens in this file.

_Last updated: 2026-11-11_

## Current position

| | |
|---|---|
| **Stage** | Stage 1 — Project foundation (in progress) |
| **Last completed milestone** | Foundation repair: first green cloud build (2026-11-11). Project compiles, unit tests pass, debug APK produced. Not yet installed on a device. |
| **Current task** | Foundation milestone features (import → preview → trim/reorder → export). |
| **Next task** | Foundation milestone features (see "Next recommended task"). |
| **Branch** | `dev-main` |
| **Latest verified build** | CI run 43011809441 (#6), commit `eb4fd87`, 2026-11-11 — https://github.com/chandrabrav/cinemind/actions/runs/43011809441 |
| **Latest verified APK** | Artifact `cinemind-debug-6` (15.6 MB, expires 2026-12-11). Built, not yet device-tested. |

## Build environment (hard rule)

- All Android builds run in **GitHub Actions** (`.github/workflows/ci.yml`). Nothing Android-related is installed locally (owner's machine is Windows; no Android Studio / SDK / JDK / Gradle).
- CI provisions **Gradle 8.11.1** via `gradle/actions/setup-gradle` (no wrapper committed yet) and Temurin **JDK 17**.
- Every push builds: `gradle testDebugUnitTest` then `gradle assembleDebug`; the debug APK is uploaded as artifact `cinemind-debug-<run>` (30-day retention).
- Release APK: only on `main` and only when repo secrets `CINEMIND_KEYSTORE_BASE64`, `CINEMIND_KEYSTORE_PASSWORD`, `CINEMIND_KEY_ALIAS`, `CINEMIND_KEY_PASSWORD` exist.
- Free tier for private repos: 2,000 Actions minutes/month; one build ≈ 5–10 min.
- `gh` is authenticated inside **WSL (Ubuntu-24.04)**, not in Windows. Helper scripts (outside the repo) in `D:\projects\cinemind-local-tools\`: `ci-watch.sh <branch>` waits for the latest run and prints errors; `ci-verify.sh <run-id>` shows test results and artifacts. Run with `wsl -d Ubuntu-24.04 -- bash /mnt/d/projects/cinemind-local-tools/ci-watch.sh <branch>`.

## History recovered (2026-11-11 audit)

- Nov 2–4: ~2,300 lines of scaffold generated and merged to `main` (PRs 1–3). It never compiled.
- Nov 4–11: a previous session tried to build **locally on Windows**, committing a JDK and Gradle distribution (files > 100 MB, unpushable) and repeatedly rewriting the version catalog with PowerShell scripts until it was 0 bytes.
- Nov 11 repair: those 2 unpushed commits were undone (old tip `8e44dbb` kept in local reflog); the toolchain and scripts were moved to `D:\projects\cinemind-local-tools\` (outside the repo) and are git-ignored.
- No prior Claude transcript or handoff doc was available; the updated master spec (`CineMind_AI_Master_Prompt_UPDATED(1).txt`) has **not** been provided yet — only the original `CineMind_AI_Master_Prompt.txt` was used.

## Architecture (as built)

- Kotlin 2.1.0, AGP 8.7.3, compileSdk/targetSdk 35, minSdk 30, Java 17.
- UI: Jetpack Compose + Material 3 (BOM 2024.12.01), Navigation Compose. Dark-first theme in `core/ui`.
- DI: Hilt 2.54 (KSP). Storage: Room 2.6.1 (schemas exported to `core/data/schemas`, **no destructive migration**), DataStore Preferences.
- Media: Media3 1.5.1 is in the catalog but not used yet.

Modules compiled in CI (`settings.gradle.kts`):

| Module | Contents |
|---|---|
| `:app` | Activity, nav host, Hilt modules |
| `:core:common` | Logger, constants |
| `:core:domain` | Room entities (Project/Track/Clip/MediaAsset), repository interfaces, AI analysis models |
| `:core:data` | Room DB, DAOs, repository implementations |
| `:core:ui` | Theme, colors, typography, `formatTimecode` |
| `:feature:media` | Media library screen (placeholder) |
| `:feature:editor:timeline` | Timeline screen (placeholder) |
| `:feature:export` | Export foreground service (stub) |
| `:feature:settings` | Settings DataStore |
| `:feature:ai:{analysis,llm,generation,cloud}` | Interfaces / data classes only |

Scaffolded but **excluded** until they get code: `:feature:editor:{preview,effects,color,audio}`, `:feature:project`, `:media3`, `:benchmark`.

## Feature status

| Feature | Status |
|---|---|
| Branding + dark theme | Partial (colors/typography only; no design system yet) |
| Media import (Photo Picker, multi-select, thumbnails, metadata) | Not started |
| Video preview | Not started |
| AI prompt screen | Not started |
| Editor workspace / timeline | UI placeholder |
| Trim / reorder | Not started |
| Export (Media3 Transformer) | Stub service only |
| Project storage | Partial: Room schema v1; no autosave / recovery / versioned project format |
| AI (all) | Interfaces only |
| Tests | `core/ui` `TimecodeTest` (5 cases) |

## Architecture decisions

1. **Cloud-only builds** via GitHub Actions; no local toolchain, nothing vendored into git.
2. Keep the existing modular layout but only compile modules that contain code.
3. Media import uses the **Photo Picker** → no storage permissions; removed CAMERA, MEDIA_PROJECTION, legacy storage, READ_MEDIA_*, RECORD_AUDIO until a feature needs them.
4. **API keys never go in DataStore**; Keystore-backed storage arrives with cloud AI (Stage 6).
5. No destructive Room migrations; every schema change ships a `Migration`.
6. ktlint/detekt plugins removed for now to get a first green build; configs remain in `config/` to re-enable later.

## Known issues / debt

- Domain module carries Room annotations (`room-common`) — acceptable for now; split entities from domain models when the project format is versioned.
- Media library item click navigates to the editor with an **asset** id as the project id (placeholder wiring).
- No Gradle wrapper committed.
- CI actions are on deprecated Node 20 majors (`setup-java@v4` etc.); bump soon.
- Updated master spec missing (captions / Hindi-Hinglish / STT / graphics requirements unknown).

## Next recommended task

Foundation milestone (Stage 1 → 2 bridge), each verified on the Vivo X200 Pro:
1. Photo Picker multi-select import → `MediaAsset` with real metadata (`MediaMetadataRetriever`) + thumbnails.
2. Create project from selected clips (one video track, clips in order).
3. ExoPlayer preview of the sequence.
4. AI prompt input screen (UI + stored prompt; no AI yet).
5. Timeline: clip trimming + reorder with an undoable command model, unit-tested.
6. Media3 Transformer export of the edited sequence to an MP4 in `Movies/CineMind`, playable in the gallery.
