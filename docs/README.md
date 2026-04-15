# PasteIt

A lightweight Android text-to-speech reader built for real workflows.
Copy anything, paste it in, listen. No clutter, no accounts, no cloud lock-in.
Your text, your API key, your device.

---

## What It Does

PasteIt is a foreground TTS service that accepts text from the clipboard or
the Android text-selection menu, chunks it, converts it to speech, and plays
it back — including while the app is minimized as a floating PiP window.

The core loop is intentionally simple:

1. Copy text in any app (or long-press → Send to PasteIt)
2. Paste into PasteIt
3. Hit play
4. Minimize to PiP and keep working

---

## Architecture Overview

### Service Layer — `ClipboardMonitorService`

The heart of the app. Runs as a foreground service so it survives
backgrounding and PiP mode. Owns:

- Clipboard monitoring and text ingestion
- Text chunking and chunk position tracking
- TTS engine lifecycle (init, play, pause, resume, stop)
- PiP remote action handling (`ACTION_TOGGLE_PLAYBACK`, `ACTION_PASTE_CLIPBOARD`)
- Saved item session state (`activeSavedItemId`, `activeContentSource`)
- Text persistence across restarts via `CurrentTextPersistence`

### TTS Engine Stack — `PasteItTts`

`PasteItTts` is an abstraction layer that routes to one of two engines:

| Engine | Class | Use case |
|---|---|---|
| XAI API | `XaiTts` | High-quality cloud TTS, paid per character |
| Android TTS | system | On-device, offline, free — built-in fallback, no bundled model |

Engine selection is driven by current app preferences and helpers such as `CloudTtsPreferences`.
Both engines report back through `AppTtsCallback`.

XAI audio is downloaded as MP3 and cached locally before playback.
Cache is keyed by text hash + voice + provider settings so the same chunk
is never requested twice. See `SessionAudioCacheStore` and `ChunkManifestStore`.

### Text Processing

- `TextChunking` — splits raw text into chunks at sentence or word boundaries.
  Chunk size is read from preferences per engine.
- `SpeechFormatting` — strips or transforms markdown, symbols, and patterns
  that sound bad when read aloud (URLs, code fences, bullets, etc.)
- `MarkdownFormatter` — converts markdown to `Spanned` for on-screen display
  in the `TextView` (separate from TTS path — UI renders markdown, TTS strips it)

### Storage

| Store | What it holds |
|---|---|
| `SavedContentStore` | User-saved text items and their document-owned files |
| `SessionAudioCacheStore` | Temporary session audio cache for unsaved current text |
| `ChunkManifestStore` | Saves and preserves the chunk layout for a given document so the same chunk plan is reused on repeat listens rather than recalculated when settings change |
| `CurrentTextPersistence` | Lightweight relaunch state until public document storage fully replaces it |
| `SharedPreferences` | All user settings (engine, voice, rate, pitch, chunk size, etc.) |

Meaningful user document data should follow the public storage contract in [STORAGE.md](/home/tuf/AndroidStudioProjects/PasteIt/docs/STORAGE.md), not hidden app-private storage.

### UI Layer

| Screen | Class |
|---|---|
| Player | `MainActivity` |
| Library | `LibraryActivity` |
| Settings | `SettingsActivity` |

`MainActivity` binds to `ClipboardMonitorService` via `LocalBinder` and
communicates back through two listener interfaces:
- `clipboardListener` — fires when new text is loaded
- `TTSListener` — fires on playback state changes (buffering / started / paused / finished / error)

PiP mode is entered automatically via `onUserLeaveHint`. PiP actions
(play/pause toggle, paste) are `RemoteAction` intents handled by
`onStartCommand` in the service.

---

## TTS Engines

### XAI (Cloud)

- Requires user-supplied XAI API key stored in `SharedPreferences`
- Audio returned as MP3, saved to cache before playback
- Hard chunk limit: **15,000 characters**
- Cached audio is reused on repeat listens — no duplicate API calls

### Android Built-In TTS (Fallback)

- Uses Android's system `TextToSpeech` engine — no bundled model, no extra APK size
- Runs fully offline, no key required
- Voice quality depends on the device's installed TTS engine (Google TTS recommended)
- Used as fallback when XAI is unavailable, not configured, or device is offline
- Hard chunk limit: **4,000 characters** (Android TTS utterance limit)

---

## Key Features

### Picture-in-Picture (PiP)
- Enters automatically when user leaves the app (`onUserLeaveHint`)
- Floating 16:9 overlay with Play/Pause and Paste remote action buttons
- All non-essential UI elements hidden in PiP mode
- Paste action sends `ACTION_PASTE_CLIPBOARD` intent to service

### Library
- User can save any loaded text as a named library item
- Library items persist indefinitely with their XAI audio cache
- Loading a library item restores text and resumes from saved position
- Cache badge shows cached/uncached status per item

### Speech Formatting
- Markdown stripped from TTS path (headings, bullets, bold, code fences, URLs)
- Stock format toggle available for plain-text content
- Configurable via `SpeechFormattingPreferences`

### Settings
- TTS engine selection (XAI / Android built-in)
- Voice selection per engine
- Speech rate and pitch sliders
- Chunk size (per engine)
- API key entry
- Cache management (size display, clear all)

---

## File Map

```
ClipboardMonitorService.kt   — foreground service, owns TTS lifecycle
MainActivity.kt              — player UI, PiP setup, service binding
LibraryActivity.kt           — saved items list
SettingsActivity.kt          — all user preferences
PasteItTts.kt                — TTS router (XAI or Android built-in)
XaiTts.kt                    — XAI API TTS implementation
AppTtsCallback.kt            — shared callback interface for both engines
TextChunking.kt              — splits text into speakable chunks
SpeechFormatting.kt          — strips TTS-unfriendly content
MarkdownFormatter.kt         — markdown → Spanned for UI display
SavedContentStore.kt         — saved item persistence
SessionAudioCacheStore.kt    — temporary XAI session cache
ChunkManifestStore.kt        — saves and preserves chunk layout per document
CurrentTextPersistence.kt    — restores last active text on relaunch
CloudTtsPreferences.kt       — XAI preference keys and helpers
XaiCacheInspector.kt         — builds cache status display string for UI
ActiveContentSource.kt       — enum: STANDARD (clipboard/manual) or LIBRARY (saved item)
ProcessTextActivity.kt       — receives Process Text intent, forwards to service, finishes immediately
                               (Path A only — not present if using Path B, see quick_paste.md)
```

---

## What Is Not In The App Yet

See `Road_map` for the full list. Key items:

- **Process Text intent** — "Send to PasteIt" in Android text selection menu
- **Playback position persistence** — resume from last chunk after force-close using per-document state
- **Text position highlighter** — scroll and highlight the chunk being read
- **Chunk mode redesign** — Standard mode + Max Chunk button with prefetch
- **UI redesign** — dark theme (see `UI_dark_theme`), bottom nav bar
- **Audio export** — stitch full cached document into a single MP3 (v2.0)

---

## Design Principles

- **Local first** — text and audio stay on device. User owns their data.
- **No account required** — user supplies their own API key if they want cloud TTS.
- **Lightweight** — no database required. File-based document storage plus lightweight settings.
- **Offline capable** — Android built-in TTS provides full offline playback with no
  bundled model and no internet connection required.
- **PiP friendly** — the app is designed to be used while doing other things.
  Minimal UI, quick interactions, stays out of the way.
- **User-owned files** — saved text and generated audio should remain accessible for backup and cleanup.
