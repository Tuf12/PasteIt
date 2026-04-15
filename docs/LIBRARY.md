# PasteIt — Library

## What It Is

The Library is a persistent store of text items the user has chosen to save.
Each saved item keeps its source text and, once listened to with XAI, retains
the generated audio cache so repeat listens do not require another API call.
Library storage should follow the public storage contract in [STORAGE.md](/home/tuf/AndroidStudioProjects/PasteIt/docs/STORAGE.md).

---

## Data Model

### ActiveContentSource

An enum that tracks whether the currently loaded text came from the
clipboard/manual paste (STANDARD) or was opened from the library (LIBRARY).

```kotlin
enum class ActiveContentSource { STANDARD, LIBRARY }
```

This distinction matters for cache behavior. Library items keep document-owned
text, manifests, resume state, and audio. Unsaved clipboard text should use a
session document under the same public storage model.

### Saved Item

Each item should be stored under the public PasteIt storage root:

```
PasteIt/Library/<item-id>/metadata.json
PasteIt/Library/<item-id>/source.md
```

`metadata.json` fields:

| Field | Type | Notes |
|---|---|---|
| `id` | String | UUID generated at save time |
| `title` | String | User-entered or auto-filled from first line |
| `createdAt` | Long | Unix timestamp |
| `updatedAt` | Long | Unix timestamp |
| `textFileName` | String | Always `source.md` |
| `activeProvider` | String | TTS engine active when item was saved |
| `xaiVoice` | String | XAI voice ID if applicable |
| `language` | String | Language code |

Source text lives in `source.md` rather than inside the JSON so large
documents do not bloat the metadata file and can be read/replaced independently.

### Audio Cache

XAI audio is cached per saved item:

```
PasteIt/Library/<item-id>/audio/<cache-key>.mp3
```

Cache key is derived from:
- Source text hash (SHA of exact text content)
- Provider name
- Voice ID
- Language
- Output codec / sample rate / bitrate

If any of these change, the existing cache is not reused — a new API call
is made and the result saved under a new key.

Managed by `SessionAudioCacheStore`.

### Chunk Manifest

`ChunkManifestStore` saves the chunk layout for a given document:

- Records how the text was split into chunks
- Preserves chunk order
- Allows the app to reload the exact same chunk plan on subsequent listens
  instead of recalculating — important because chunk boundaries affect
  which cached audio file maps to which position

If the source text changes, the manifest is invalidated and rebuilt.

---

## User Flow

### Saving

1. User pastes text and optionally listens to it
2. Taps **Save** button in `MainActivity`
3. Alert dialog prompts for a title (pre-filled with first line of text)
4. `SavedContentStore.save()` writes metadata.json and source.md
5. `ClipboardMonitorService.setSavedText()` is called — sets `activeSavedItemId`
   and switches `ActiveContentSource` to LIBRARY
6. Subsequent XAI audio is now cached permanently under that item's directory

### Opening from Library

1. User taps **Library** button → `LibraryActivity` launches via `libraryLauncher`
2. User selects an item
3. `LibraryActivity` returns the item ID via `EXTRA_SAVED_ITEM_ID`
4. `MainActivity` calls `SavedContentStore.load(itemId)`
5. Text is passed to `ClipboardMonitorService.setSavedText()`
6. Cached audio is available immediately — no API call needed for cached chunks

### Cache Status Display

`XaiCacheInspector.buildStatusText()` reads the current source text and
saved item ID and returns a human-readable cache status string shown in
the main UI (e.g. "3 of 13 chunks cached · 420 KB").

---

## Saving Standard Content — Cache Migration (Not Yet Implemented)

When a user pastes text and listens to it in STANDARD mode, XAI audio chunks
are generated and stored in a temporary session document. If the user then taps
**Save**, the current behavior creates a new Library item and discards the
temporary cache — meaning all audio has to be regenerated from scratch,
wasting API calls and time.

**The correct behavior:** when saving STANDARD content that already has a
temporary audio cache, migrate the existing cache files into the new Library
item's audio directory rather than deleting them. The chunk manifest should
transfer with it. No API calls should be needed if the text has not changed.

This is a known issue to fix before v1.0 ships.

Migration logic in `promptSaveCurrentText()` / `SavedContentStore.save()`:

1. After writing metadata.json and source.md, check if a session document exists
   for the current text hash
2. If yes — move (not copy) those MP3 files into the new item's audio directory
3. Transfer the chunk manifest to the new item
4. Update `activeSavedItemId` and switch `ActiveContentSource` to LIBRARY
5. No XAI API call required

---

## Storage Notes

All meaningful Library data should be user-accessible and follow [STORAGE.md](/home/tuf/AndroidStudioProjects/PasteIt/docs/STORAGE.md).
Audio cache can grow large for long documents (300+ MB for a full book).
A cache manager with per-item delete is planned for v1.1.

### Local-First by Design

PasteIt does not connect to any cloud storage service. All text and audio
lives on the device. This keeps the app simple, eliminates security risks
from cloud auth, and avoids ongoing server costs. Cloud integration can be
evaluated in a future version if user demand justifies it.

### Backup

- **Source text and metadata** — live in user-accessible storage so they can be backed up directly
- **Audio cache** — exclude from cloud backup. Audio files are large and
  fully regeneratable from source text. Including them would balloon backup
  size unnecessarily.

### Audio Export and Import

Users can export a library item's full audio as a single MP3 (see
`audio_export.md`). That file can then be stored anywhere — local PC,
Google Drive, Dropbox, USB — entirely outside the app.

**Audio import** should ship alongside export. If a user clears their device
cache and wants to listen again without paying for regeneration, they should
be able to import their exported MP3 back into the app. The imported file is
validated against the source text hash before being accepted into cache.
Do not ship export without a clear path to import.

### Google Play Store

PasteIt is intended for distribution on the Google Play Store:

- public user-owned document storage is an explicit product requirement
- The foreground service and persistent notification are allowed on Play
  but the notification must clearly state its purpose.
- Never hard-code any API key in the APK.
- Play's data safety declaration requires disclosing what data the app
  collects. PasteIt collects nothing and sends text only to XAI
  (user-configured). This should be straightforward to declare accurately.

---

## Planned Improvements (v1.1)

- Per-item cache size display in Library list
- Delete individual cached audio without deleting the saved item
- Rename saved items
- Search / filter Library by title or content
- Cache badge showing cached chunk count vs total
