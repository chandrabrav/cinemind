# Releasing CineMind AI on Google Play

Everything builds in GitHub Actions. There's no local Android Studio, SDK or JDK.

## 1. Create the upload key (once, by the owner)

The release key is yours. It is never committed, and nobody else should hold it. With Play App Signing, Google keeps the real app-signing key. This **upload key** only proves to Google that an upload came from you, and Google can reset it if it's ever lost.

Run this in Windows PowerShell on your own computer. It needs no JDK.

```powershell
$pw = Read-Host -AsSecureString "Choose a strong password for the upload key"
$cert = New-SelfSignedCertificate -Subject "CN=CineMind AI Upload, O=Gautam" -KeyAlgorithm RSA -KeyLength 4096 -HashAlgorithm SHA256 -KeyExportPolicy Exportable -NotAfter (Get-Date).AddYears(30) -CertStoreLocation Cert:\CurrentUser\My -FriendlyName "cinemind-upload"
Export-PfxCertificate -Cert $cert -FilePath "$HOME\cinemind-upload.p12" -Password $pw
Remove-Item "Cert:\CurrentUser\My\$($cert.Thumbprint)"
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\cinemind-upload.p12")) | Set-Clipboard
```

Then:

1. Keep `cinemind-upload.p12` and its password somewhere safe, such as a password manager and an offline backup. Never put them in the repository or in chat.
2. GitHub → repository → Settings → Secrets and variables → Actions. Add these secrets:

| Secret | Value |
|---|---|
| `CINEMIND_KEYSTORE_BASE64` | paste the clipboard (the base64 of the .p12) |
| `CINEMIND_KEYSTORE_PASSWORD` | the password |
| `CINEMIND_KEY_PASSWORD` | the same password |
| `CINEMIND_KEY_ALIAS` | optional: leave it unset and CI uses the only key in the file |

From then on, every build of `main` (or a manual run of the CI workflow on `main`) also produces:

* `cinemind-play-bundle-<n>` → `app-release.aab`. **This is the file you upload to Play.**
* `cinemind-release-<n>` → a signed release APK, for checking on a phone before uploading.

The release app is `com.cinemind.app`. The beta builds are `com.cinemind.app.debug`, so both can be installed side by side. Projects don't move between them.

## 2. Play Console checklist

1. Create the app: name **CineMind AI**, free, category Video Players & Editors.
2. **App signing:** choose Play App Signing (the default). Google generates the app-signing key, and your upload key is the one above.
3. **Privacy policy URL:** publish `docs/PRIVACY_POLICY.md` and paste its address.
   * The simplest way: the GitHub page for the file, `https://github.com/chandrabrav/cinemind/blob/main/docs/PRIVACY_POLICY.md`.
   * First fill in the effective date and a contact email in that file.
4. **Data safety:** see section 3.
5. **Content rating:** complete the questionnaire. There's no user-to-user content, no violence, and no purchases.
6. **Target audience:** 13 and over.
7. **Ads:** none.
8. **Permissions declarations:**
   * camera and microphone: teleprompter, recording, voiceover;
   * foreground service "media processing" / "data sync": export and AI jobs that keep running when the app is minimised.
9. Store listing: icon, feature graphic, screenshots (CI saves screenshots under `test-reports`), short and full description.
10. Release to **Internal testing** first, then **Closed testing** with a few users, then Production.

Size: the bundle lets Play deliver only the native libraries for each phone, so downloads are smaller than the beta APK. AI models are never inside the app. They're downloaded in the app on request.

## 3. Data Safety form answers

| Question | Answer |
|---|---|
| Does the app collect or share any of the required user data types? | **No.** Media, projects and recordings stay on the phone. Feedback and crash reports are sent only by the user, through the share sheet, to a destination the user picks (Play counts this as user-initiated sharing, not collection). |
| Is data encrypted in transit? | Yes. All network traffic is HTTPS (model downloads, cloud AI requests). |
| Can users request deletion? | Yes. Everything is stored on the phone: delete projects or uninstall. Cloud keys can be removed in the app. |
| Cloud AI (optional) | Text the user chooses to send goes **directly from the phone to the user's own provider account**, with the user's key, after the user turns it on. CineMind's developer never receives it. Declare it as "user-initiated, sent to a service the user connects" if the form asks. Revisit this answer if a CineMind server is ever added. |
| Third-party SDKs | Google ML Kit (on-device segmentation). Per Google's ML Kit Data Safety guidance, declare what the ML Kit page currently lists for the segmentation APIs (diagnostic and performance data, collected by Google and not shared). Check `developers.google.com/ml-kit/android-data-disclosure` when filling in the form. |

## 4. Before each release

* CI on `main` is green: unit tests, optimised APK, and export end-to-end.
* Install the signed release APK on the Vivo. Check import, edit, export, Smart edit and captions.
* Bump `versionName` in `app/build.gradle.kts`. `versionCode` is the CI run number, so it always increases.
