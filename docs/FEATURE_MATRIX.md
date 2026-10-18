# Feature matrix (honest status)

**Verification key.** *Compiled* = Kotlin sources compile with kotlinc 2.0.20 against the android-34 `android.jar` (not a full Gradle/AGP build). *JVM-tested* = executed on a JVM test harness. *CI* = GitHub Actions build — **not yet run for this change**. *Device* = tested on a real phone — **nothing in this table has been tested on a device yet**.

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
| Wake phrase / hands-free foreground mic | Planned | Foreground service | – | Sessions stop when the app leaves the screen |
| Owner-voice lock | Not planned | – | – | Voice matching is not secure authentication |
| Languages incl. Manglish / Hinglish | Implemented | Device speech engines | Compiled | Quality depends on installed engines/voices |
| Persona presets, tone dials, custom text, names | Implemented | – | Compiled | Proactivity/reminders Planned |
| Installed TTS voice, rate, pitch | Implemented | – | Compiled | Voices come from the phone, not the AI |
| Memory Core: add/search/filter/edit/forget/clear/export | Implemented | – | Compiled | Auto-suggested memories Planned |
| Secrets refused as memory | Implemented | – | JVM-tested | Pattern-based, not perfect |
| Typed, allow-listed device actions with risk policy + confirmations | Implemented | App-control switch | JVM-tested (parser) / Compiled | – |
| Open any installed app by name | Implemented | QUERY_ALL_PACKAGES | Compiled | Ambiguous names ask which app |
| YouTube search | Implemented | – | Compiled | Does not auto-pick a video (reported as NEEDS_USER_ACTION) |
| Open Android settings pages | Implemented | – | Compiled | You change settings yourself |
| WhatsApp draft | Partial | WhatsApp installed | Compiled | Recipient chosen and Send pressed by you; no contact lookup |
| Back/Home/Recents/scroll/tap/type | Implemented | Accessibility service | Compiled | Apps may hide controls; password fields and system dialogs refused |
| Verified outcomes + task journal (6 statuses) | Implemented | – | Compiled | Verification relies on Accessibility |
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
