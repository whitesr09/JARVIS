# Feature matrix (honest status)

**Verification key.** *Compiled* = Kotlin sources compile with kotlinc 2.0.20 against the android-34 `android.jar` (not a full Gradle/AGP build). *JVM-tested* = executed on a JVM test harness. *CI* = GitHub Actions build — **not yet run for this change**. *Device* = tested on a real phone — **nothing in this table has been tested on a device yet**.

**v0.3.0 note.** Features added in v0.3.0 (media playback, orb styles, voice names, wake phrase) are compiled but not device-tested.

**v0.2.1 note.** The first real-device screenshots exposed a layout bug (rows collapsed into tall blank cards because of a match-parent chevron). It is fixed and every call site was audited, but the fix itself is still unverified on a device.

| Feature | Status | Permissions / API needed | Verified | Known limitation |
|---|---|---|---|---|
| Home / Chat / Settings / Updates navigation + sub-screens | Implemented | – | Compiled | Not visually reviewed on a device |
| First-run flow, chat-only start | Implemented | – | Compiled | – |
| Particle orb with idle/listening/thinking/speaking/working/connected/error | Implemented | – | Compiled | Static frame with reduce-motion or battery saver |
| Themes: Sunset, AMOLED, Dark, Light, System + accent | Implemented | – | Compiled | Dialogs follow dark/light of the chosen theme |
| Streaming Gemini chat, stop, retry (send again) | Implemented | Internet, Gemini key | Compiled | Never run against the live API |
| Conversation history: new, switch, search, delete, clear | Implemented | – | Compiled | Search matches message text only |
| Markdown rendering | Partial | – | Compiled | Fenced code, headings, bullets, bold, inline code only |
| Provider adapter: Gemini + OpenAI-compatible | Implemented | Internet, key | Compiled | OpenAI-compatible is text-only (no device actions) |
| Model list refresh / editable model | Implemented | Gemini key | Compiled | – |
| Test connection (latency) | Implemented | Key | Compiled | – |
| Fallback provider chain | Planned | – | – | Intentionally off: no silent provider switching |
| Usage / cost labels | Planned | – | – | – |
| Keys encrypted via Android Keystore | Implemented | – | Compiled | Not tamper-proof on rooted phones |
| Voice session (SpeechRecognizer + TTS), tap-to-interrupt | Implemented | Microphone | Compiled | Endpointing hints may be ignored by some recognizers |
| Turn-taking driven by TTS completion events | Implemented | – | Compiled | – |
| Low-confidence speech → edit before sending | Implemented | – | Compiled | Only when the recognizer reports confidence |
| Voice barge-in (talk over Zara) | Planned | – | – | Tap the orb/mic to interrupt instead |
| Gemini Live realtime audio | Planned | – | – | Needs a WebSocket client dependency |
| Dedicated low-power wake-word engine | Planned | Extra dependency / model | – | Would replace the recogniser loop; needs a library and (for some engines) a licence key |
| Owner-voice lock | Not planned | – | – | Voice matching is not secure authentication |
| Languages incl. Manglish / Hinglish | Implemented | Device speech engines | Compiled | Quality depends on installed engines/voices |
| Persona presets, tone dials, custom text, names | Implemented | – | Compiled | Proactivity/reminders Planned |
| Installed TTS voice, rate, pitch | Implemented | – | Compiled | Voices come from the phone, not the AI |
| Memory Core: add/search/filter/edit/forget/clear/export | Implemented | – | Compiled | Auto-suggested memories Planned |
| Secrets refused as memory | Implemented | – | JVM-tested | Pattern-based, not perfect |
| Typed, allow-listed device actions with risk policy + confirmations | Implemented | App-control switch | JVM-tested (parser) / Compiled | – |
| Open any installed app by name | Implemented | QUERY_ALL_PACKAGES | Compiled | Ambiguous names ask which app |
| Play media in a named app (`play_media`): Spotify, YouTube Music, any player app | Implemented | App installed | JVM-tested (parsing) / Compiled | Uses Android "play from search"; apps may ask you to sign in. Playback is only reported as COMPLETED if Accessibility sees a Pause control |
| Play on YouTube (taps the top real result, skips ads) | Implemented | Accessibility service | Compiled | Relies on YouTube's on-screen labels; falls back to NEEDS_USER_ACTION if it can't find a result |
| Each request judged on its own (no reusing the previous app) | Implemented | – | JVM-tested | Regression test for "open Spotify and play…" |
| YouTube search only | Implemented | – | Compiled | `search youtube for …` opens results without playing |
| Timer, alarm, web search, maps, dial, SMS draft | Implemented | SET_ALARM (normal) | JVM-tested (parsing) / Compiled | Clock apps don't report back (COULD_NOT_VERIFY); dial/SMS are drafts you send |
| Open Android settings pages | Implemented | – | Compiled | You change settings yourself |
| WhatsApp draft | Partial | WhatsApp installed | Compiled | Recipient chosen and Send pressed by you; no contact lookup |
| Back/Home/Recents/scroll/tap/type | Implemented | Accessibility service | Compiled | Apps may hide controls; password fields and system dialogs refused |
| Verified outcomes + task journal (6 statuses) | Implemented | – | Compiled | Verification relies on Accessibility |
| Orb styles: Particles, Fluid, Aurora, Pulse, Waveform | Implemented | – | Compiled | Pure Canvas drawing; still frame with reduce-motion/battery saver |
| Friendly, renameable voice names | Implemented | – | Compiled | Names are assigned by us (Aria, Nova, …); the phone supplies the actual voices |
| Hands-free "Hey Zara" (foreground service) | Experimental | Microphone, foreground notification; Display over apps and battery exemption recommended | JVM-tested (phrase matcher) / Compiled | Recogniser loop, not a low-power wake engine: battery use, possible restart beeps, no lock-screen use, restarts only when the app is opened |
| Floating avatar overlay (8 avatars, edge, opacity, visibility) | Implemented | Display over other apps | Compiled | Foreground-service notification is required |
| Code Workspace: edit, copy, share, save (SAF), open, open-with, explain | Partial | – | Compiled | No syntax highlighting or diff view yet |
| Local weather card | Implemented | Coarse location, Internet | Compiled | Appears only after you allow location |
| Screen-sharing vision | Planned | MediaProjection | – | – |
| Phone calls / contacts | Planned | Contacts, Phone | – | – |
| Instagram comment / story / post flows | Planned | Accessibility | – | Highly app-dependent; will need previews and confirmation |
| Sound effects | Planned | – | – | – |
| Unit tests | Present | – | CI not yet run | `CommandsTest` mirrors the JVM harness that passed 32 checks |
| Lint | Not run | – | – | Not part of the workflow yet |
| Gradle wrapper | Not present | – | – | Workflow uses pinned Gradle 8.7 |
| Signed release | Not configured | Secrets | – | Debug APK only |
