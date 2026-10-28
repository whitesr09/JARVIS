# Privacy — what the app actually does

This describes the code in this repository. It is not a legal privacy policy and the app has not been independently audited.

## Stays on the phone
- Chat history, saved memories, task journal and settings: app-private storage (SQLite + SharedPreferences).
- API keys: encrypted with an AES-GCM key held in the Android Keystore (`SecureStore`). They are never written to logs and never put in URLs (the Gemini key is sent in the `x-goog-api-key` header).
- There is no Zara/NSHD server, account, sign-up, analytics or tracking code.

## Sent over the network
| When | What | To |
|---|---|---|
| You send a message (typed, or spoken and recognised) | The message, recent messages (default 16), persona text, up to 30 saved memories if Memory is on | **Only** the provider set as Primary (Gemini by default, or your OpenAI-compatible base URL) |
| You tap Test connection / Refresh models | A tiny test prompt / a models-list request | The Primary Gemini (or OpenAI-compatible) endpoint |
| You allow location and open Home | Coordinates rounded to 2 decimals, at most every 20 minutes | open-meteo.com (weather) |

There is **no silent fallback** to another provider. Screen contents are never collected or sent.

## Live conversation and agent mode
Live streams the microphone to Google's Live API for as long as the conversation is on (a notification is always shown, and you can
stop it from there). In always-listening mode that includes everything the microphone hears. In agent mode the visible text of
your screen (never password fields; never protected apps) is sent to your AI provider so it can decide what to tap. Actions that
send, post, pay or delete ask first (by dialog, or by notification when the app is closed); payment, banking and password apps are
off-limits. Transcripts of Live turns are stored locally like normal chat.

## Task agent
When you ask for a multi-step phone job, a text model (your Gemini key, the model set under Settings → App Control → Task model) receives the goal and the visible text of the current screen after each step — never password fields and never protected apps — and decides the next tap. Only the newest screen is kept in the conversation. Nothing is sent to anyone else.

## Speech
Speech recognition and text-to-speech use the phone's installed Android engines. Depending on the device, the recognizer may process audio on its provider's servers — this is controlled by Android/the engine vendor, not by this app. The microphone is used only while a voice session is running; the session stops when the app leaves the screen.

## Hands-free wake phrase (optional, off by default)
When on, a foreground service with a permanent notification keeps Android's speech recogniser running to hear the wake phrase. The recogniser is the phone's, and depending on the device it may process audio on its provider's servers; the app asks it to prefer offline recognition. Audio is not stored by this app and nothing is sent to your AI provider until after the phrase is heard. Turn it off in Settings → App Control & Hands-Free or from the notification.

## Accessibility service
Optional, user-enabled. It acts only on an explicit request (tap, scroll, type, check that the screen changed). It keeps one timestamp of the last window change for verification and does not record, store or upload screen content.

## Deleting data
Settings → Privacy & Data Shield: export, clear chats, clear memories, clear activity history, delete API keys. Uninstalling the app removes everything.

## Limits
A key stored on a phone cannot be guaranteed impossible to extract from a rooted/modified device. Use keys with spending limits. Voice matching is not authentication and is not implemented.
