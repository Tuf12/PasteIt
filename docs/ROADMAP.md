# PasteIt — Feature Roadmap & To-Do

---

## Version 1.0 — Ship Ready



### Core Features (Not Yet Implemented)
- [ ] **Process Text intent** — "Send to PasteIt" in Android text selection menu
  See quick_paste.md for full implementation. Two paths documented:
  - Path A (preferred): PiP-gated via separate `ProcessTextActivity` component,
  disabled by default, toggled by `setComponentEnabledSetting` in
  `onPictureInPictureModeChanged`. Option only visible when PiP is active.
  - Path B (fallback): filter on `MainActivity`, always available.
  Use if Path A is unreliable on device testing. Do not attempt to re-introduce
  PiP gating if falling back — just ship Path B.
- [ ] **Playback position persistence** — save current chunk index in per-document resume state
  so relaunch resumes only the matching document
- [ ] **Start Over button** — resets chunk position to 0 for current text,
  needed because position now persists automatically
- [ ] **Text highlighter** — scroll TextVew to currently playing chunk and
  highlight it, so the user can follow along visually

### UI
- [ ] Implement dark theme redesign (see pasteit_concept.html)
- [ ] Replace bookmark ribbon icon with Material bookmark_border / bookmark
  (unfilled = unsaved, filled = saved — toggles on tap)
- [ ] Add settings shortcut icon to top-right header
- [ ] Add light/dark theme toggle icon to top-right header (sun/moon)
- [ ] Bottom navigation bar — Player, Library, Settings

---

## Version 1.1 — Polish

- [ ] **Chunk mode system** — replace the broken slider with two explicit modes:
  - **Standard mode** — user sets a default chunk size in Settings once and forgets it.
  Recommended range ~1,000–2,000 chars for XAI, up to 4,000 for Android TTS.
  This is always the active mode unless the user triggers Max.
  - **Max Chunk button** — one tap on the player screen pre-generates the entire
  current document at the engine's hard limit in one pass
  (XAI max: 15,000 chars per chunk, Android TTS max: ~4,000 chars per chunk).
  Useful when user wants uninterrupted playback of a known document.
  Auto-resets back to Standard mode after the current document finishes
  or when new text is loaded, so the user is never stuck in Max mode.
  - **Prefetch / read-ahead in Max mode** — do not wait for a chunk to finish
  playing before requesting the next one. At roughly 75% through current
  chunk playback, fire the API call for the next chunk in the background.
  By the time playback reaches the end, the next chunk is already cached
  and plays immediately with no perceptible gap. Standard mode does not
  prefetch — no point burning API calls on content the user may never reach.
  If a prefetch call fails, fall back gracefully to on-demand load rather
  than halting playback entirely.
  - Engine chunk limits enforced in code, not user-configurable:
  XAI hard cap 15,000 chars, Android TTS hard cap ~4,000 chars.
  - Chunk size setting must be read fresh at document-load time,
  not cached at app start, so Settings changes apply immediately.
- [ ] **Skip code blocks toggle** — when reading MD files, optionally skip
  fenced code blocks so they don't get read aloud as gibberish
- [ ] **Cache manager** — show per-item cache size in Library,
  allow deleting individual cached items not just full clear
- [ ] **Resume indicator** — show "Resumed from chunk X" toast on load
  so user knows the position was restored
- [ ] **Search in Library** — filter saved items by title or content snippet

---

## Version 2.0 — Audio Export

- [ ] **Export to single audio file** — once a document is fully cached
  (all chunks have audio files), stitch them in order into one MP3 or M4A
- [ ] **Export UI** — progress indicator while stitching, share sheet on completion
  so user can AirDrop, email, or send via any app
- [ ] **Partial export warning** — if not all chunks are cached yet,
  warn user and offer to generate missing chunks before exporting
- [ ] Consider: chapter markers in exported audio for long documents

---

## Ideas / Backlog (No Commitment)

- Sleep timer — stop playback after X minutes
- Playback speed presets — tap to cycle Slow / Normal / Fast / Turbo
  instead of only the slider
- Widget — home screen widget showing now-playing + play/pause
- Share to PasteIt from share sheet (alternative to Process Text for apps
  that use share instead of text selection)
- iCloud / Google Drive import — load a .txt or .md file directly
  without copy-paste

---

## Notes

**Cache file naming convention (fix for ordering bug):**
Name each file by `{documentHash}_{chunkIndex}.mp3` so files are
deterministic, never duplicated, and always sort correctly.
Check for existence before making an API call.

**Position persistence note:**
Do not use one global resume key for user content. Store resume state per document
under the public storage model so different pasted texts and Library items do not
fight each other.

**Audio stitching library for 2.0:**
Look at FFmpeg for Android (via FFmpegKit) — handles MP3/M4A concat
cleanly without re-encoding, so quality is preserved and it's fast.
