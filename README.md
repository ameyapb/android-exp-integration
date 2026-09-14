# phone-app-exp

`ask-gemini.js` is a minimal Termux CLI for asking Google's Gemini a question from a spare Android phone and getting the answer back as both terminal output and a native Android notification: install Termux and Node.js (`pkg install nodejs`), run `npm install` in this directory to pull in `@google/genai` and `dotenv`, install the Termux:API app plus `pkg install termux-api` so `termux-notification` works, then create a `.env` file in this directory with `GEMINI_API_KEY=your-google-ai-studio-key-here` (get a free-tier key at https://aistudio.google.com/apikey) — after that, run the script with `node ask-gemini.js "what's 2+2"` and Gemini's reply will print to stdout and pop up as a notification (if `termux-notification` isn't installed, the script just logs a warning and still prints the answer).

To ask Gemini by voice instead of typing, run `node ask-gemini.js --voice` — it uses Termux:API's `termux-speech-to-text` (already installed above) to record and transcribe what you say, prints the transcript and asks you to confirm before sending it to Gemini. For a one-tap home-screen shortcut: install the Termux:Widget app, then copy (not symlink — Termux:Widget does not reliably list symlinked scripts) the shortcut script into Termux's widget directory with `mkdir -p ~/.shortcuts && cp ~/android-exp-integration/shortcuts/ask-gemini-voice.sh ~/.shortcuts/ && chmod +x ~/.shortcuts/ask-gemini-voice.sh` (adjust the source path if you cloned this repo somewhere other than `~/android-exp-integration`, and re-run the `cp` after every `git pull` that touches the script), then add a Termux:Widget widget to your home screen and pick `ask-gemini-voice.sh` from the list — tapping it opens Termux and starts listening immediately.

## Native Android app (`android-app/`)

A native Kotlin/Jetpack Compose app is replacing the Termux CLI as the delivery mechanism (see `CLAUDE.md` for why, and `roadmap.md` for what's next). Phase 1 has feature parity with `ask-gemini.js` minus voice: a single screen with a prompt field, a send button, and a reply area, showing Gemini's answer on-screen and as a notification.

To build and run it: open `android-app/` in Android Studio (or use the command line below), copy `android-app/local.properties.example` to `android-app/local.properties`, and set `GEMINI_API_KEY` to a Google AI Studio key. Then:

```
cd android-app
./gradlew build lint testDebugUnitTest   # build, lint, and run unit tests
./gradlew installDebug                   # install the debug build on a connected device/emulator
```

The app requires `minSdk` 31 (Android 12) or newer, matching the target spare phone.
