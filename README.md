# Deck Bridge (Android)

Forwards notifications from Instagram, WhatsApp, and Discord into a Notion
database, so they show up alongside the rest of your Deck. This only works
on Android — iOS doesn't allow third-party apps to read other apps'
notifications.

## What it does

- Runs quietly in the background using Android's Notification Listener API.
- When a notification arrives from a watched app, it grabs the title and
  text and creates one new row in a Notion database you choose.
- Nothing is stored on the phone and nothing is sent anywhere except that
  one Notion database. Unwatched apps are ignored entirely.

## Setup

**1. Get an APK — two ways**

*Easiest, no Android Studio needed:* this project includes a GitHub Actions
workflow that builds the APK for you.
1. Create a new repo on GitHub (private is fine) and push this folder to it.
2. Go to the repo's **Actions** tab — a "Build APK" run should start
   automatically (or click "Run workflow" if it doesn't).
3. When it finishes (~3-5 min), open the run and download the
   `deck-bridge-debug-apk` artifact from the bottom of the page. That zip
   contains `app-debug.apk`.
4. Copy the APK to your phone (email it to yourself, or use a file-share
   app), tap it to install. You'll need to allow "install from unknown
   sources" for whatever app you install it from — Android will prompt you.

*Alternative:* open this folder in Android Studio (Giraffe or newer),
let Gradle sync, then Run directly onto your phone over USB. Requires
Android 8.0+.

**2. Create the Notion database**
In Notion, create a new database with exactly these properties:

| Property | Type      |
|----------|-----------|
| Name     | Title     |
| App      | Text      |
| Sender   | Text      |
| Message  | Text      |
| Time     | Date      |

**3. Create a Notion integration**
Go to notion.so/my-integrations, create a new internal integration, and
copy its secret token. Then, on the database page, click **"..."** →
**Connections** → add your integration, so it's allowed to write there.

**4. Get the database ID**
It's the 32-character string in the database's URL:
`notion.so/yourspace/<DATABASE_ID>?v=...`

**5. Configure the app**
Open Deck Bridge on your phone:
- Tap "Open notification access settings" and enable it for Deck Bridge.
- Paste in your Notion token and database ID.
- Check off which apps to watch.
- Tap Save.

That's it — new messages from the apps you checked will start appearing
as rows in your Notion database within a few seconds of arriving.

## AI brain setup (Gemini + on-device fallback)

The app's "4. AI brain" section has a test box so you can try this without
building the wake-word/voice piece first.

**Gemini (online, free tier):**
1. Go to aistudio.google.com, sign in, and generate a free API key —
   no payment info needed.
2. Paste it into "Gemini API key" in the app and save.
3. That's it — questions get answered via Gemini's `gemini-3.1-flash-lite`
   model whenever the phone has a connection.

**On-device fallback (offline, free, more limited):**
This part is genuinely more involved and is left partly scaffolded rather
than faked — MediaPipe's on-device API changes across versions, and Gemma's
model files require you to personally accept Google's license (can't be
automated). Steps:
1. Add `implementation 'com.google.mediapipe:tasks-genai:<latest>'` to
   `app/build.gradle` (check for the current version).
2. Go to ai.google.dev/gemma/docs/integrations/mobile for the current
   Android integration steps.
3. Download a small Gemma `.task` model (Gemma 3 1B-it is a reasonable
   size for a phone) from Kaggle Models or Hugging Face — you'll need to
   accept Google's license there.
4. Push it to the phone (`adb push gemma-3-1b-it.task /data/local/tmp/`)
   and put that path into "Local model file path" in the app.
5. Finish wiring `askLocal()` in `AiBrain.kt` to actually call MediaPipe's
   `LlmInference` class per the docs from step 2.

Until that last step is done, offline questions will just tell you a local
model isn't wired up yet — it won't silently fail or make something up.

**Important:** this uses Gemini, not Claude. That's the trade-off of
going the free route.

## Zenon — wake word, speaker lock, and voice

This is the "acts like Siri" piece: say "Zenon," it checks the voice
matches you, listens for what you say, thinks about it, and answers out
loud. Real trade-off up front: **this costs noticeably more battery than
Siri does.** Apple's "Hey Siri" runs on a dedicated low-power chip built
into Apple hardware. Android has no equivalent hook open to third-party
apps, so this runs as an ordinary background service, listening with the
regular CPU the whole time. It works, but it's not free on battery the
way Siri is.

**1. Picovoice account (powers wake word + speaker verification)**
1. Sign up free at console.picovoice.ai.
2. Copy your AccessKey from the console home page.
3. Paste it into "Picovoice AccessKey" in the app (section 5) and save.

**2. Train the wake word**
1. In Picovoice Console, go to Porcupine → Create Wake Word.
2. Type `Zenon` (pronounced "Zee-non" — the console usually gets this
   right from spelling alone; if it mispronounces it in the preview,
   look for a custom pronunciation/phonetic option in the console).
3. Select Android as the target platform, train it, and download the
   resulting `.ppn` file.
4. Rename it to exactly `zenon.ppn` and place it in
   `app/src/main/assets/zenon.ppn` (replacing the placeholder there).
   This step can't be automated — the console training is tied to your
   account.

**3. Enroll your voice (the "only me" part)**
1. Build and install the app (see section 1 for the GitHub Actions route).
2. Grant microphone permission when asked.
3. In the app, tap "Enroll my voice" and talk naturally (read anything
   out loud) until it reports done — usually 15-30 seconds of speech.
4. This builds a voiceprint stored only on your phone. Nothing about
   your voice leaves the device for this step.

**4. ElevenLabs (the actual voice you hear back)**
1. Sign up at elevenlabs.io, grab an API key from your profile settings.
2. Pick a voice at elevenlabs.io/app/voice-library and copy its Voice ID.
3. Paste both into the app (section 5) and save.

**5. Go live**
Tap "Start listening for Zenon." A persistent notification confirms it's
running (Android requires this for background mic use — it's not hidden
from you, and it shouldn't be from anyone else picking up your phone
either). Say "Zenon," wait for it to check your voice, then ask your
question.

**Tuning notes:**
- The wake-word sensitivity and the speaker-match threshold (both `0.6f`
  in `WakeWordService.kt`) are starting points, not final numbers. If
  it's missing you, lower the match threshold slightly. If it's
  triggering for other people, raise it. This needs real testing on your
  actual phone in your actual environment — I can't tune this from here.
- Speaker verification is not perfect. A cold, background noise, or a
  tired voice can lower the match score. Don't rely on it for anything
  where a false accept or false reject would be a real problem.
- Two spots in the code (`VoiceEnroll.kt` and `WakeWordService.kt`) are
  marked with NOTE comments where I couldn't fully verify one Eagle SDK
  method name against the current version — check those against
  picovoice.ai/docs/api/eagle-android if the build fails on them.




- Android may kill background services aggressively on some phones
  (especially Xiaomi, Huawei, OnePlus). If forwarding stops after a while,
  check your phone's battery-optimization settings and exclude Deck Bridge.
- This reads notification *content as shown on screen* — if an app hides
  message previews (e.g. WhatsApp's privacy setting), there won't be
  anything to forward.
- The Notion token lives only in the app's local settings storage on your
  phone — it's never sent anywhere except directly to api.notion.com.
