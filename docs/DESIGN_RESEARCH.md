# CineMind AI — Design research & direction (2026-11-12)

Purpose: a "futuristic but calm" editor that feels faster than it is, stays out of the way of the
video, and is easy one-handed on a phone. Inspiration is taken from how leading mobile editors
*organise* work (owner asked for Instagram Edits as a reference); nothing is copied (no layouts
pixel-for-pixel, no names, icons, colours or assets of other apps).

## What the owner reported (device test, build #113)
- The editor felt cluttered; panels stacked on top of each other and pushed the timeline off screen.
- Positioning with sliders is slow; wants pinch/drag directly on the video.
- Jobs stopped when the app was minimised.
- The app didn't feel smooth.

## Principles adopted
1. **The video is the hero.** Largest area is the preview; chrome is thin, translucent ("glass"
   surfaces from the design system) and never covers the picture unless asked.
2. **One bottom bar, context-aware.** Nothing selected → the main tools. Something selected → only
   that item's actions, with ✕ to go back. Never two toolbars at once. *(Built in 4fd7e4c.)*
3. **Direct manipulation first, numbers second.** Drag / pinch / twist on the preview for titles,
   stickers and picture-in-picture; sliders remain in "Adjust" sheets for precision. *(Built.)*
4. **Sheets for depth.** Detailed controls open as bottom sheets over the editor, so the layout
   below never jumps. *(Built.)*
5. **Fixed geometry.** Timeline height is constant; lanes scroll inside it; zoom −/+ always reachable.
6. **Motion with meaning.** Short (150–250 ms) eased transitions for bars and sheets; haptic tick on
   selection, snapping (0°/±90°, beats) and split. No decorative animation while playing.
7. **Work never stops silently.** Long jobs show progress in place *and* in a quiet notification,
   keep running in the background, and report results when you come back.
8. **AI as a suggestion, never a surprise.** AI proposes (looks, titles, music moods, beat cuts)
   with a short reason; the user taps to apply; everything is one undo step and stays editable.

## Visual language (next steps)
- **Depth:** keep the glass surfaces but add a subtle backdrop blur behind sheets (Android 12+
  `RenderEffect`), falling back to the current tint.
- **Accent:** one luminous accent for "AI" actions (✦) distinct from the selection colour, so users
  learn where suggestions come from.
- **Type:** compact labels (11–12 sp) on bars; numbers in tabular figures on the timeline.
- **Dark-first:** editing UIs are used in dim light; the dark theme gets priority polish; light
  theme stays fully supported and contrast-tested (existing tests).
- **Timeline:** clip thumbnails strip on the video lane; waveform on audio lanes (done); beat ticks
  (done); selected item glow instead of thick borders.

## Interaction backlog (ordered)
1. Drag clips and titles along the timeline (long-press to lift, edge auto-scroll).
2. Pinch/drag the main clip on the preview (uses Crop & move keyframes).
3. Two-finger scrub on the preview; double-tap to toggle full screen.
4. Swipe the bottom bar between tool groups; long-press a tool for a tip.
5. Smoothness: measure on device (frame timing while playing/scrubbing) before optimising; isolate
   the playhead so only it redraws during playback.

## Accessibility
- Every control has a content description (tests already query them).
- Touch targets ≥ 48 dp; gestures always have a button/slider alternative.
- Respect system font size; no information by colour alone (selection also uses outline + label).
