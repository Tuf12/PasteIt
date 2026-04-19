**Plan**

We should treat this as a new foundation feature: `text-time mapping`, not as a small tweak to chunk highlighting.

Right now the app is built around:
- `textChunks: List<String>` in [ClipboardMonitorService.kt](/home/tuf/AndroidStudioProjects/PasteIt/app/src/main/java/com/example/pasteit/ClipboardMonitorService.kt:29)
- chunk-only manifests in [ChunkManifestStore.kt](/home/tuf/AndroidStudioProjects/PasteIt/app/src/main/java/com/example/pasteit/ChunkManifestStore.kt:24)
- chunk-based resume in [PlaybackResumeStore.kt](/home/tuf/AndroidStudioProjects/PasteIt/app/src/main/java/com/example/pasteit/PlaybackResumeStore.kt:9)
- chunk-start timestamps for exported merged audio in [AudioExportManager.kt](/home/tuf/AndroidStudioProjects/PasteIt/app/src/main/java/com/example/pasteit/AudioExportManager.kt:45)

That means the current system knows:
- which chunk is active
- where a chunk starts in merged audio
- where playback is inside a chunk for xAI/ExoPlayer

It does **not** yet know:
- which text range inside the full document corresponds to a playback timestamp
- which playback timestamp corresponds to a tapped word or sentence
- how to highlight precise text ranges during playback across engines

**Recommended target model**

I’d make the new source of truth a `TextTimingMap` stored per document/session alongside the chunk manifest.

It should contain:
- document source hash/version
- normalized text used for playback
- ordered chunk entries
- for each chunk:
    - `chunkIndex`
    - `text`
    - `startChar`
    - `endChar`
    - `audioStartMs`
    - `audioEndMs`
    - optional finer-grain timing segments
- for each timing segment:
    - `startChar`
    - `endChar`
    - `startMs`
    - `endMs`
    - granularity label like `word`, `phrase`, or `sentence`

That gives us both directions:
- playback time -> text range
- text range -> playback time

**Important architecture decision**

We should not require word-level timing on day 1.

Safer rollout:
1. `Level 1`: chunk char offsets + chunk audio times
2. `Level 2`: sentence/phrase timing inside chunks
3. `Level 3`: word-level timing where supported

That keeps the project realistic while still moving toward follow-along behavior.

**Engine reality**

This part matters a lot:

- `XaiTts` currently plays MP3s through ExoPlayer, so time-based playback is available, but there is no text timing metadata yet in [XaiTts.kt](/home/tuf/AndroidStudioProjects/PasteIt/app/src/main/java/com/example/pasteit/XaiTts.kt:241).
- `AndroidBuiltInTts` currently only gives start/done/error callbacks in [AndroidBuiltInTts.kt](/home/tuf/AndroidStudioProjects/PasteIt/app/src/main/java/com/example/pasteit/AndroidBuiltInTts.kt:31). No timing map is being captured today.

So the cleanest migration is:
- make xAI / saved audio the first-class path for precise mapping
- treat Android TTS as reduced-capability unless we later add `onRangeStart` support or synthetic timing estimates

**Phased migration**

1. **Data model phase**
- Add a new `TextTimingMapStore`
- Keep `ChunkManifestStore` for backward compatibility at first
- Extend saved/session document metadata with `timingMapVersion`
- Define schema migration rules so old documents still play

2. **Chunk offset phase**
- Update chunk creation so each chunk stores `startChar` and `endChar` in the normalized playback text
- Stop thinking of chunks as just strings; they become structured entries
- Update code/docs that describe chunk manifests as only “list of strings”

3. **Audio timing phase**
- When xAI chunk audio is cached or stitched, compute `audioStartMs/audioEndMs` for each chunk
- For merged audio, preserve the full per-chunk timing map instead of only storing `chunkStartMs`
- Make resume capable of storing absolute document time in addition to chunk position

4. **Fine-grain timing phase**
- Decide the first practical granularity:
    - sentence/phrase timing recommended first
    - word timing later if provider support is solid
- Generate and save segment timing metadata per chunk
- Add lookup APIs:
    - `findRangeForTime(ms)`
    - `findTimeForCharOffset(offset)`
    - `findSegmentForSelection(start, end)`

5. **Playback integration phase**
- Update `ClipboardMonitorService` to track active document time/range, not only `currentPosition`
- Keep chunk index as a convenience/UI concept, but not the only playback state
- Add “start from here” by resolving text offset -> mapped time or temporary slice

6. **UI phase**
- Replace `Now Reading` excerpt logic in [MainActivity.kt](/home/tuf/AndroidStudioProjects/PasteIt/app/src/main/java/com/example/pasteit/MainActivity.kt:631) with mapping-driven highlight
- Add follow-along highlight/cursor driven by current playback time
- Add precise scrubbing and text-tap start points where supported by the map

7. **Docs/wording cleanup**
- Update docs that currently imply chunk-only position:
    - [docs/TEXTVIEW_MODES.md](/home/tuf/AndroidStudioProjects/PasteIt/docs/TEXTVIEW_MODES.md)
    - [docs/BUILD_PLAN.md](/home/tuf/AndroidStudioProjects/PasteIt/docs/BUILD_PLAN.md)
    - [docs/AUDIO_EXPORT.md](/home/tuf/AndroidStudioProjects/PasteIt/docs/AUDIO_EXPORT.md)
    - [docs/LIBRARY.md](/home/tuf/AndroidStudioProjects/PasteIt/docs/LIBRARY.md)
    - [docs/STORAGE.md](/home/tuf/AndroidStudioProjects/PasteIt/docs/STORAGE.md)
    - [docs/README.md](/home/tuf/AndroidStudioProjects/PasteIt/docs/README.md)

**Recommended scope decision**

I recommend this exact order:

1. Add structured chunk metadata with char offsets
2. Add per-chunk audio timing persistence
3. Add a new timing map file/schema
4. Ship chunk-accurate highlight based on offsets
5. Add phrase/sentence timing
6. Then revisit follow-along and precise `Start From Here`

That gives us a stable ramp instead of betting everything on true word-level timing immediately.

**What this changes in the old text-view plan**

If we take this direction:
- Phase 1 becomes mapping-driven highlight, not substring matching
- Phase 2 still stands, but can eventually search any mapped text range
- Phase 3 becomes much stronger and more precise
- Phase 4 still stays separate as a copy/select mode

If you want, next I can draft a replacement planning doc in chat called something like `TEXT_TIME_MAPPING_PLAN.md`, with concrete phases, schema ideas, and a list of exact files/docs that need wording changes.