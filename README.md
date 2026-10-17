# JARVIS Android

A beginner-friendly native Kotlin Android starter with an AMOLED-black interface, voice input, spoken replies, Gemini chat, local conversation history and explicit saved memories, plus a small allow-list of phone actions (YouTube, Chrome, Maps, Gmail, WhatsApp and Android Settings).

## Important limitations

- This is a starter project, not a finished or yet device-tested release.
- The app uses Android's speech recognition service. During an active session it reopens recognition after replies, but it does **not** yet implement a true always-on "Jarvis" wake-word detector while idle.
- Speech accuracy depends on the recognition service, device, language and network; clipped words cannot be ruled out.
- Phone actions are allow-listed; the model cannot run arbitrary shell commands.
- The microphone is used only after you tap **START SESSION**. Tap **STOP** to stop listening.

## Connect AI

1. Create a Gemini API key at https://aistudio.google.com/apikey.
2. Install and open JARVIS, tap **API KEY**, and paste the key.
3. Keep the default model `gemini-2.5-flash` unless your API account says it is unavailable.
4. Never publish your API key or commit it to this repository. AI requests are sent to Google; free quotas and data terms can change.

## Build APK with GitHub Actions

The workflow in `.github/workflows/build-apk.yml` runs on pushes to `main` and can be started manually.

1. Open the repository's **Actions** tab.
2. Select **Build JARVIS APK**, then **Run workflow** (or wait for the push-triggered run).
3. Open the completed workflow run.
4. Under **Artifacts**, download `jarvis-debug-apk`.
5. Extract the downloaded ZIP and install `app-debug.apk` on your Android phone. Android may ask you to allow installation from your browser or file manager.

The artifact is retained for 14 days. The first cloud build may take several minutes. If it fails, open the run and inspect the **Build debug APK** step logs.

## Try these phrases

- "Open YouTube"
- "Remember that I prefer short answers"
- "What do you remember?"
- Ask a question and then a follow-up without saying "Jarvis" again.

Saved memories and chat history are stored locally in the app database. Clearing app data removes them.
