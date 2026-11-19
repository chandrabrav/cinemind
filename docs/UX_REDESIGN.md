# CineMind AI — UX audit & redesign plan (2026-11-16)

Owner brief: "complete UX/UI redesign & editor experience" (Parts 1–14) plus a mock-up
(home with New Project / AI Edit, editor with a large preview, a minimal timeline, a swipeable
category toolbar). Inspiration: VN (timeline clarity), CapCut (contextual tools), Instagram Edits
(focus). **Principles only — no layouts, names, icons or assets are copied.** The current colour
theme, logo and gradients stay; only how they are applied changes.

Rule for every milestone: *a simple project looks simple; the editor grows only as content is added*,
and nothing that works today is removed or replaced by a mock-up.

## 1. Audit of the current app (branch `studio-extras`, build after #143)

### What makes it cluttered
| # | Problem | Where |
|---|---|---|
| A1 | The timeline always shows **7 lanes** (Video, Overlay, Music, Voice, Sounds, Captions, Text), five of them empty "Add …" placeholders, in a fixed 208 dp box — a new project looks busy and the real content is small. | `MultiTrackTimeline` |
| A2 | A **row of 5 buttons** above the timeline (start, end, fit, zoom −, zoom +) duplicates gestures (pinch, drag playhead). | `MultiTrackTimeline` header |
| A3 | Lane order doesn't match how people think: Video at the top, Music in the middle, titles at the bottom — the *visual* stack (what's in front) is unrelated to where things sit. | `MultiTrackTimeline` |
| A4 | The main tool bar mixes **categories** (Audio, Text) with **single operations** (Layers, Smart edit) and opens a different kind of UI for each (sheet, picker, navigation). | `TimelineScreen` main `EditorAction`s |
| A5 | AI features are scattered: Smart edit (tool bar), AI titles (Graphics sheet), Assistant (header ✦), Music ideas (Audio sheet), captions AI (captions sheet). | several |

### Duplicated / unnecessarily visible
- Empty lane placeholders (A1) duplicate the tool bar's Overlay / Audio / Text / Captions buttons.
- "Move left / Move right" on a selected clip duplicate long-press drag in the timeline.
- Zoom buttons (A2) duplicate pinch.

### Too many steps / hard to discover
- **Front/back order** needs: Layers (tool bar) → sheet → arrow buttons, one step at a time. It isn't visible in the timeline at all.
- Colour has two entry points (main bar and clip bar) that open the same sheet with different scope; scope isn't obvious.
- Speed, Crop & move, Freeze, Reverse are only reachable after selecting a clip; nothing tells a new user that.

### Confusing interactions
- Long-press + drag on a block moves it in time; there is **no** gesture for front/back order.
- The playhead line height is computed from a fixed lane list, so it is wrong when lanes change.

### Can be simplified without losing anything
- Hide empty lanes; add them automatically when content exists (all adders already exist in the tool bar).
- Collapse the 5-button zoom row into one "fit" button next to the time; keep pinch.
- Make the tool bar categories-only; each category opens one consistent row of its tools.

### Architecture needed
- The engine already is multi-track and non-destructive: Room tracks (MAIN, OVERLAY×n, MUSIC, VOICEOVER, SFX×4), clips with source in/out + timeline in/out, text layers (Room v9) with `lane`, `belowOverlays`; per-project `LayerOrder` for captions; preview and export share `TimelineRenderer` compositing order (emulator pixel tests prove dissolve/overlay/caption order).
- **Gap 1:** the visual stack (`LayerStack`) is only editable from a sheet → expose it in the timeline (one lane per stack entry, vertical drag = `LayerStack.move`).
- **Gap 2:** caption-order changes are saved immediately but are not part of undo → move `LayerOrder` into the text history (milestone 4b).
- **Gap 3:** all overlays share one stack slot (PiP group). Per-overlay order (an overlay above one title but below another) needs overlays to join the `LayerStack` individually and the renderer to interleave overlay sequences with text bitmaps (milestone 4c — real compositing change, needs new emulator tests).

## 2. Screen hierarchy (proposal)

```
Home ─┬─ New project (import)            ─┐
      ├─ AI Edit (Smart edit brief)       ├─▶ Editor ─┬─ Export
      ├─ Templates                        │           ├─ (sheets: category tools, Assistant)
      ├─ Teleprompter                    ─┘           └─ Full-screen preview (same view)
      ├─ Recent projects (+ library)
      └─ Settings ─┬─ Appearance (Light/Dark/System)
                   ├─ On-device AI · Cloud AI (optional key)
                   └─ About · Diagnostics · Send feedback
```

### Editor (five areas)
1. **Top bar:** Back · project name (+ format) · ✦ Assistant · Undo · Redo · Export. Nothing else.
2. **Preview:** largest area, project aspect ratio, gestures (move/pinch/twist), full-screen button.
3. **Playback row:** time / total · play · full screen.
4. **Timeline (adaptive):**
   ```
   ruler + playhead                                   [fit]
   Music            ▬▬▬ waveform / "Add music" ▬▬▬    ← always
   Voice / Sounds   (only when they exist)
   ── visual layers, front at the top (only when they exist) ──
   Title "Goa"      ▭        ⇕ long-press, drag up/down = in front / behind
   Captions         ▭ ▭ ▭    ⇔ long-press, drag sideways = move in time
   Overlays (PiP)   ▭
   Video            ▣▣▣▣▣▣                               ← always
   ```
   New project = two lanes: **Music on top, Video at the bottom**.
5. **Bottom bar:** categories (Edit · Audio · Text · Captions · Overlay · Effects · Filters · Adjust · Speed · Transitions · AI); a category shows its tools in a second row; selecting an item swaps in that item's own tools (✕ back).

### Gestures in the timeline
| Gesture | Effect |
|---|---|
| Tap | select (tap empty ruler = seek) |
| Drag playhead / ruler | scrub |
| Pinch | zoom |
| Edge handles (selected clip) | trim |
| Long-press, then drag **sideways** | move in time (overlays, sounds, voice, titles) / reorder (video clips) |
| Long-press, then drag **up/down** | change front/back order (visual layers only) |

The direction is locked after the first 12 dp of movement, so a sideways move never reorders
layers and a vertical move never shifts time.

## 3. Milestones

| # | Milestone | Status |
|---|---|---|
| 1 | Audit, hierarchy, design rules (this doc) | done |
| 2 | Editor workspace: top bar, preview, playback. Top bar is now exactly Back · name + format · ✦ · Undo · Redo · Export | done (2026-11-19) |
| 3 | Adaptive two-track timeline (Music top, Video bottom, lanes only for content, compact zoom) | done (PR #29) |
| 4a | Vertical drag = front/back order in the timeline (titles, stickers, captions, PiP group) via `LayerStack` | done (PR #29) |
| 4b | Caption order inside undo/redo (a drag that changes titles and caption order is one undo step) | done (PR #31) |
| 4c | Per-overlay stacking | done (2026-11-19): each title can sit in front of or behind each picture-in-picture clip it overlaps (Text → Words & look → Picture-in-picture). The editor has one overlay track, so overlays never overlap in time. The renderer draws each title under or over the picture-in-picture frame by frame. That needed no compositor redesign. Moving a title past the PiP in the layer stack resets its per-overlay choice. |
| 5 | Category tool bar: Edit · Audio · Text · Captions · Overlay · Colour · Speed · Transitions · AI. Edit/Audio/Text/Overlay/AI open a row of their tools; the others open their panel. Clip tools act on the clip under the playhead when nothing is selected. Effects/Filters/Adjust are folded into Colour, Edit and Transitions until there are real effects to put there (no empty buttons). | done (PR #31) |
| 6 | Home: New Project / AI Edit first, recent projects with thumbnail · duration · resolution · date | done (2026-11-19) |
| 7 | Preview polish: Fit · Fill · Reset under the preview for the selected clip; opacity keyframes (Framing panel) | done (2026-11-19) |
| 8 | Tests on the Vivo X200 Pro (120 Hz scrolling, memory, thermals) | owner |

## 4. Visual rules (theme kept)
- Surfaces: `surface` / `surfaceRaised` with a 1 dp `hairline`; gradients only on the primary action (Export, New Project) and the selected category.
- One accent at a time per area; lanes use their own muted tint (music = accent, voice/sounds = secondary, titles = highlight, captions = neutral).
- Touch targets ≥ 48 dp; lane rows 30–64 dp with blocks the full lane height.
- No decorative cards in the editor.
