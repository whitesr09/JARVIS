# Zara AI — by NSHD

A native Kotlin Android app: a voice-first AI companion and assistant with an animated orb, streaming chat, local memory, personas, and an optional, permission-based phone-action layer. Built to be compiled on **GitHub Actions** so you never need a PC.

> **Status: v0.2.0, not yet device-tested.** The code compiles and the command parser is tested on a JVM, but the app has not been run on a phone and the first GitHub Actions build for this version has not been run. See `docs/FEATURE_MATRIX.md` for the honest status of every feature.

## What's in it
- **Home** – orb with states (idle, listening, thinking, speaking, working, connected, error), greeting, voice status, optional real weather, quick actions.
- **Chat** – streaming replies, stop button, send-again, conversation history (search / switch / delete), Markdown + code blocks, "Code" mode, copy / share, provider + model shown on every reply.
- **Voice** – tap the orb or the centre mic. Partial transcripts are display-only; follow-ups work without repeating a wake phrase while a session is active; tap to interrupt Zara; low-confidence speech is shown for editing instead of acted on.
- **Settings & Intelligence** – AI connection (Gemini + any OpenAI-compatible endpoint, test connection, model list refresh), language (incl. Manglish/Hinglish), persona presets + style dials + installed TTS voice, voice setup, Memory Core, permissions checklist, floating avatar, App Control & Hands-Free, Theme Studio, Privacy & Data Shield, About & Credits.
- **Updates & Activity** – verified task history (COMPLETED / PARTIALLY_COMPLETED / FAILED / BLOCKED_BY_PERMISSION / NEEDS_USER_ACTION / COULD_NOT_VERIFY), things waiting for you, version and release notes.
- **Code Workspace** – edit, copy, share, save through Android's file picker, open files, hand a saved file to any editor ("Open with…"). Zara never runs code.

## Safety model (device actions)
The AI can only request one allow-listed action (`open_app`, `search_youtube`, `open_settings`, `whatsapp_draft`, `go_back`, `go_home`, `recent_apps`, `scroll_down/up`, `tap_text`, `type_text`). Each request is validated, policy-checked, shown to you for confirmation when it is public or consequential (sending, typing, risky taps), executed, and then **verified** — success is only reported when it was observed. It is off until you enable it, never auto-taps permission/install dialogs, never fills password fields, and WhatsApp messages are drafts that you send yourself.

## Setup
1. Get a Gemini API key at https://aistudio.google.com/apikey (free tier limits and terms can change).
2. Install the app, open **Settings → AI Connection**, paste the key, **Save key**, then **Test connection**.
3. Optional: **Settings → App Control & Hands-Free** to turn on phone actions; follow the accessibility guide there.

Branding and contact rows live in `app/src/main/java/com/nshd/jarvis/Brand.kt`. Blank values are hidden.

## Build the APK with GitHub Actions (Android phone only)
1. Commit these files to `main` on GitHub (or open the workflow manually).
2. Open the repo's **Actions** tab → **Build Zara AI APK** → open the latest run (or **Run workflow**).
3. Under **Artifacts** download `zara-debug-apk`, extract the ZIP, install `app-debug.apk`. Allow installs from your browser/file manager when Android asks.
4. If a run fails, open the **Build debug APK** step and read the error; the APK is uploaded before the unit-test step runs.

This produces a **debug-signed** APK. A signed release is not configured.

## Permissions (all optional, asked only when used)
Microphone (voice sessions) · Approximate location (Home weather) · Display over other apps (floating avatar) · Accessibility service (tap/scroll/type, enabled by you in Android Settings — on Android 13+ sideloaded apps may need *App info → ⋮ → Allow restricted settings* first) · Foreground-service notification (shown while the avatar is on).

## Known limits
No wake phrase, Gemini Live audio, screen-sharing vision, calls/contacts or Instagram flows yet. Hands-free listening in the background is not implemented — sessions end when the app leaves the screen. See `docs/FEATURE_MATRIX.md`, `docs/PRIVACY.md` and `THIRD_PARTY_NOTICES.md`.
