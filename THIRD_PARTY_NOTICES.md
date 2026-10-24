# Third-party notices

**Zara AI — by NSHD** is built with:

| Component | Use | Licence / terms |
|---|---|---|
| Android SDK platform APIs | UI, audio, speech, storage, accessibility | Android Open Source Project (Apache-2.0) |
| Kotlin standard library | Language runtime | Apache-2.0 |
| **Thinking Orbs** geometry engine (ported to Kotlin) | Nine orb designs | MIT — © 2026 Jakub Antalik (full text below) |
| JUnit 4.13.2, org.json 20240303 (tests only, not in the APK) | Unit tests | EPL-1.0 / JSON licence |
| Open-Meteo.com free weather API | Home weather card (runtime service) | Data CC BY 4.0 — attribution to Open-Meteo.com |
| Google Gemini API (incl. Live API) | AI provider (runtime service) | Google's current API terms |

No other libraries are bundled in the APK.

## Thinking Orbs (MIT)

`ThinkingOrbs.kt` is a Kotlin port of the geometry engine from https://github.com/Jakubantalik/thinking-orbs (v0.3.1): the nine
orb modes, the per-state presets and the radius/count scaling. The port is verified against the library's own golden test
vectors (`app/src/test/resources/orbs-golden.txt`, converted from `spec/orbs-golden.json`: 72 frames, 11,288 dots, tolerance 1e-4).
The paint colours are Zara's theme colours instead of the library's monochrome ink.

```
MIT License

Copyright (c) 2026 Jakub Antalik

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Inspiration only — no code copied

- **ShaderGradient** — https://github.com/ruucm/shadergradient (MIT per its package metadata). The *fluid sphere* orb follows the same
  mechanism (a sphere displaced and twisted by 3D noise, a three-colour gradient and glossy lighting) but is an independent
  implementation written as an AGSL fragment shader; none of its source was copied.
- Android JARVIS tutorial project — https://github.com/techgptx/Playlist (custom licence; not needed — none of its code or assets were used).

The launcher icon, avatars and icons are drawn in code / vector XML and are original to this project.

If you later copy code or assets from any project, keep that project's required notices here and in the in-app *About & Credits* screen.
