# Motion graphics, captions and AI cutout

Branch `motion-graphics`, 2026-11-19. It is stacked on `ai-providers` (PR #34).

## Creator review: what was missing

I went through the editor code as someone who wants quick, eye-catching reels. These gaps stood out:

| Expected by a social creator | Before | Now |
|---|---|---|
| Display fonts (condensed headline, comic, script, neon), Hindi fonts | 5 system families only | 15 bundled fonts under SIL OFL or Apache 2.0, including Mukta and Kalam for Devanagari. They are used everywhere: titles, captions, export and font pickers. |
| Kinetic text (word by word, letter drop, stamp, glitch) | Fade, pop, slide, zoom, typewriter and bounce only | 7 more entrances: letter drop, word by word, wipe, tracking, spin, stamp, glitch |
| Text that keeps moving | none | Loops: pulse, shake, wave, float, neon flicker, glitch |
| Gradient and 3D text | none | Gradient fill and 3D extrusion with colour |
| Ready-made "veed-style" looks | 9 plain looks | 12 more title looks: Behind me, Hype, Kinetic, Gradient pop, Retro 3D, Glitch, Neon sign, Wave, Comic, Signature, Editorial, Hindi bold. Plus 6 caption looks: Hype, Bold box, Comic, Statement, Elegant, Hindi bold. |
| Text behind the person | impossible | **Behind the person** switch on any title, plus a "Behind me" look |
| Background removal, blur, colour pop | none | **AI cutout** on the phone. Background looks: remove, blur, colour pop, spotlight, outline. They work on the main track and on overlays. |
| "Do it for me" masking | none | The assistant understands commands such as "remove the background", "blur the background of clip 2", "colour pop", "mask me out" and "put GOA behind me". These work instantly and offline; any other request still goes to Gemma or Claude. |

## How the cutout works

1. `MatteMaker` decodes the clip about 12 times a second.
   * It runs Google ML Kit **Subject Segmentation**, which handles any subject (people, pets, products). Google Play services downloads the model at install time.
   * If that model can't run, it falls back to the bundled people-only **Selfie Segmentation** model.
   * Both run on the phone, and nothing is uploaded.
2. The masks are stored at 256 px on the short side, deflate-compressed, in `files/mattes/<assetId>.cmm` (`MatteStore`). Every clip of the same file reuses it.
3. `SubjectEffect` is a Media3 GPU effect shared by preview and export.
   * It maps each output pixel back to the source picture: it undoes fit or fill, the 90° turns, flips and crop. This mapping is `SubjectMapping` and it has unit tests.
   * It blends the two nearest mask samples.
   * It applies the background look, and draws "behind the person" text only where the subject isn't.
4. Behind text on a clip that has no cutout falls back to ordinary text under the overlays, so nothing disappears.

Known limits:

* Behind text is part of the clip's picture, so a clip's motion keyframes and transitions move it too.
* Captions can't go behind the person yet, because that needs a schema change.
* Remove background on the main track leaves black, so the person must be on an overlay to sit over other footage.
* Cutout speed depends on the phone: on a flagship, roughly real time for 1080p.

## Still worth adding (not built)

* **Auto-reframe** from 16:9 to 9:16 that follows the subject (the cutout already gives its position each frame).
* **Silence and filler-word cuts** for talking-head videos.
* **Auto punch-in zooms** on emphasised words.
* **Captions behind the person.**
* **Keyframed text position.**
* **Animated stickers and progress bars.**
