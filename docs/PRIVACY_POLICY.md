# CineMind AI — Privacy Policy

_Effective: [date of first Play release] · Developer: Gautam · Contact: [contact email]_

CineMind AI is a video editor that works on your phone. This policy explains what the app does with your information. In short: **your videos, photos and projects stay on your phone.** CineMind has no account, no analytics and no advertising, and runs no server of its own.

## 1. Your media and projects

* You choose videos, photos and music through Android's photo picker or file picker. CineMind reads only the items you pick.
* Projects, edits, captions, settings and AI models are stored in the app's private storage on your phone. They are removed when you uninstall the app.
* Exported videos are saved where you choose, or shared to the app you choose.
* **CineMind never uploads your original videos, photos or audio.**

## 2. On-device AI

Smart edit, captions from speech, voice clean-up, automatic cutouts and frame analysis run **on your phone**:

* Gemma, Spark and Whisper models are downloaded only when you ask, from Hugging Face (`huggingface.co`). That download is an ordinary file download: no media or project data is sent.
* Sound effects search (optional): if you add your own Freesound API key, your search words and the key are sent to Freesound (`freesound.org`) and the sounds you pick are downloaded from it. No media or project data is sent. The key is stored encrypted on the phone.
* Automatic cutouts use Google ML Kit, which runs on the phone. Google Play services may download the ML Kit module, and Google may collect limited technical usage data about it, as described in the [ML Kit terms](https://developers.google.com/ml-kit/terms) and the [Google Privacy Policy](https://policies.google.com/privacy).

## 3. Optional cloud AI (off unless you turn it on)

You can connect your own account with Anthropic (Claude), OpenAI (ChatGPT), Google (Gemini) or OpenRouter:

* You paste your own API key. It is encrypted with the Android Keystore and sent only to that provider.
* Only **text** is sent: your written request and a text summary of the project, such as clip lengths and shot descriptions. Frames, video and audio are never sent to a cloud service.
* Nothing is sent until you turn on "Allow sending text" for that service. You can remove the key at any time.
* What the provider does with that text is governed by its own privacy policy and your agreement with it.

## 4. Other things the app downloads, or pages it opens

* **Colour looks (LUTs):** downloaded on request from a public GitHub repository.
* **Links you choose to open** (music libraries, KATANA / Higgsfield, Instagram, YouTube) open in your browser or in that app. CineMind sends them nothing except the page address.
* **KATANA remake handoff:** CineMind prepares copies of the photos you pick and a written brief. They are passed to the app you choose through Android's share sheet. CineMind doesn't upload them itself.

## 5. Camera and microphone

* The camera is used only by the teleprompter and recording screens, and only while they are open.
* The microphone is used only for voiceovers and recording, and only while you record.
* Recordings are saved to your project on the phone.

## 6. Diagnostics and feedback

* If the app crashes, technical details are saved on your phone: the error, the app version, the phone model, Android version and memory use. Media and project content are never included.
* Nothing is sent automatically. When you tap **Send report** or **Send feedback**, Android's share sheet opens, and you choose whether and where to send it (for example email or WhatsApp).

## 7. Children

CineMind is not directed at children under 13 and does not knowingly collect information from them.

## 8. Your choices

* Delete projects in the app, or uninstall the app to remove everything it stored.
* Remove cloud keys under AI models → Cloud.
* Delete downloaded AI models under AI models → On this phone.

## 9. Changes

If this policy changes, the new version will be published at this address with a new effective date.

## 10. Contact

Questions: [contact email].
