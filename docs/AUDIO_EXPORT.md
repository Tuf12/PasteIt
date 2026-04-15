# PasteIt — Audio Export (v2.0)

## What It Is

Audio Export lets the user convert a fully cached library item into a single
MP3 file and share it via any Android share target — Messages, email, Google
Drive, AirDrop to Apple devices, etc.

The primary use case is sharing audio versions of personal writing — books,
essays, notes — without needing a separate podcast or audiobook platform.

---

## Prerequisites

Before export is available for an item:

- The item must be saved to the Library (not just clipboard text)
- All chunks must have cached XAI audio files
- The chunk manifest must be intact so stitch order is deterministic

If any chunks are missing, the user is warned and offered the option to
generate missing audio before proceeding.

---

## How It Works

### Stitch Process

1. User taps **Export Audio** on a Library item
2. App reads the chunk manifest to get the ordered list of chunk cache keys
3. Verifies all expected MP3 files exist on disk
4. If any are missing — shows a warning dialog with chunk count and offers
   to generate them before exporting
5. If all present — stitches MP3 files in order into a single output file
6. Presents the Android share sheet with the output file attached

### Stitching Library

**FFmpegKit for Android** is the recommended stitching library:
- Handles MP3 concat without re-encoding (lossless, fast)
- Supports M4A output if preferred
- Available via Maven: `com.arthenica:ffmpeg-kit-audio`

Concat command:
```
ffmpeg -i "concat:chunk_0.mp3|chunk_1.mp3|chunk_2.mp3" -acodec copy output.mp3
```

For large documents (50+ chunks) this runs in a background coroutine with
a progress indicator in the UI.

### Output File

Suggested naming: `<item-title>-<date>.mp3`
Saved temporarily to `cacheDir` for sharing, not persisted after the share
sheet is dismissed.

---

## UI Flow

```
Library item detail
    └── [Export Audio] button
            ├── All chunks cached?
            │       YES → show progress dialog → stitch → share sheet
            │       NO  → warning: "X of Y chunks not yet generated"
            │               └── [Generate Missing] → generates → stitch → share sheet
            │               └── [Cancel]
            └── Stitch in progress
                    └── "Building audio file... (23 of 31 chunks)"
                    └── Complete → share sheet opens
```

---

## Partial Export Warning

If not all chunks are cached, the dialog shows:

> **"Audio not complete"**
> 8 of 31 chunks have not been generated yet.
> Generate missing chunks now? This will use your XAI API.
>
> [Generate & Export]  [Cancel]

Generating missing chunks follows the same XAI call flow as normal playback
and saves results to cache — they become available for future listens too.

---

## Chapter Markers

ID3 chapter markers embedded in the exported MP3 allow podcast apps and
audio players to show chapter navigation — useful for long-form documents
like books or multi-section essays.

**This is in scope for v2.0 alongside the basic export.**

Library: `mp3agic` handles ID3 tag writing on Android cleanly.
Chapter boundaries map naturally to the chunk manifest — each chunk becomes
a chapter with a start timestamp calculated from cumulative audio duration.

Implementation notes:
- Read duration of each cached MP3 chunk before stitching (MediaMetadataRetriever)
- Calculate cumulative timestamps: chunk 0 starts at 0ms, chunk 1 starts at
  duration(chunk 0), chunk 2 starts at duration(chunk 0) + duration(chunk 1), etc.
- Write ID3v2 CHAP frames with start/end time and title (e.g. "Part 1", or
  first sentence of the chunk if extractable)
- Write ID3v2 CTOC frame as the chapter table of contents

---

## In-App Audio Player (Library Content)

Library items should have a more capable playback experience than the basic
player used for clipboard content. The library player is for content the user
intends to return to repeatedly — books, long essays — so it warrants a
more refined interface.

### Planned Player Features

**Progress scrubber**
- A seekable slider showing position within the full document (not just current chunk)
- Position expressed as percentage and elapsed/remaining time
- Scrubbing jumps to the nearest chunk boundary (we cannot seek within an
  MP3 chunk without decoding it, but chunk-level seeking is acceptable)

**Current position highlight**
- As each chunk plays, the corresponding text in the text view scrolls to
  and highlights the active chunk
- Highlight uses a background tint on the active text span, not an overlay
- Scrolls smoothly to keep the active chunk in the center of the visible area

**Skip controls**
- Rewind: jumps back one chunk (not just a few seconds)
- Fast forward: jumps forward one chunk
- Long-press rewind: restart from beginning
- These already exist in the basic player — ensure they work correctly
  against the chunk manifest in library mode

**Chapter navigation (tied to audio export chapter markers)**
- If chapter markers are present (exported and re-imported, or inferred
  from document structure), show a chapter list the user can jump to

**Dependencies to evaluate**
- `MediaPlayer` or `ExoPlayer` for chunk playback — ExoPlayer handles
  gapless playback between chunks more reliably than `MediaPlayer`
- `mp3agic` for ID3/chapter marker reading on import

---

## Text Cleaning — URL and Symbol Reading (Known Issue)

The TTS engine currently reads raw URLs, HTML entities, and some markdown
symbols aloud. This is a `SpeechFormatting` concern but is worth documenting
here since it affects the quality of exported audio as much as live playback.

### Current State

- Markdown is stripped for the TTS path via `SpeechFormatting`
- HTML rendering is handled for the display path via `MarkdownFormatter`
- Some symbols still slip through — particularly URLs, HTML tags in
  pasted web content, and punctuation used as markdown syntax

### What Needs Fixing

| Content type | Current behavior | Target behavior |
|---|---|---|
| Raw URLs (`https://...`) | Read character by character or as garbled text | Strip entirely or replace with "link" |
| HTML tags (`<b>`, `<br>`, `&amp;`) | Sometimes read aloud | Strip all tags, decode entities |
| Markdown code fences (` ``` `) | Read as "backtick backtick backtick" | Strip block, optionally replace with "code block" |
| Bullet symbols (`•`, `–`, `*`) | Sometimes read as "bullet" or symbol name | Strip or replace with natural pause |
| Repeated punctuation (`---`, `===`) | Read aloud | Strip |

### Recommended Approach

Extend `SpeechFormatting` with a pre-pass that:
1. Strips all HTML tags and decodes HTML entities (use `HtmlCompat.fromHtml` then `.toString()`)
2. Removes raw URLs (regex: `https?://\S+`)
3. Removes markdown code fences and inline code backticks
4. Normalizes bullet and list markers to a short pause or nothing
5. Strips horizontal rules (`---`, `===`, `***`)

This runs on the TTS text path only — the display path continues to use
`MarkdownFormatter` which renders these elements correctly on screen.

---

## Planned v2.0 Scope

- [ ] Export button in Library item detail view
- [ ] Partial cache warning dialog with generate-missing option
- [ ] FFmpegKit integration for MP3 stitch
- [ ] Progress indicator during stitch ("Building audio... 23 of 31 chunks")
- [ ] **ID3 chapter markers** — embedded at stitch time using chunk manifest timestamps
- [ ] Share sheet on completion
- [ ] Temporary output file cleanup after share dismissed
- [ ] Audio import — validate and restore exported MP3 into item cache
- [ ] In-app library player with scrubber, chunk highlight, chapter nav

## Out of Scope for v2.0

- Batch export of multiple items at once
- M4A / AAC output format (MP3 only initially)
- Direct upload to Drive, Dropbox, etc. (handled by share sheet — user's choice)