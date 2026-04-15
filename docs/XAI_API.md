# PasteIt — XAI API (Cloud TTS)

## What It Is

XAI is the primary TTS engine in PasteIt. It produces natural, high-quality
speech via a paid API. The user supplies their own API key — PasteIt never
handles billing. Generated user content and audio cache should follow the
public storage contract in [STORAGE.md](/home/tuf/AndroidStudioProjects/PasteIt/docs/STORAGE.md).

---

## Getting an API Key

1. Go to **https://console.x.ai**
2. Sign in or create an account
3. Navigate to **API Keys** in the left sidebar
4. Click **Create API Key** and copy the key
5. Paste it into PasteIt → Settings → API Key

The key looks like: `xai-xxxxxxxxxxxxxxxxxxxxxxxxxxxx`

XAI charges per character of text converted to speech. For typical reading
use (articles, book chapters, notes), costs are low. A full 50-page book
at standard chunk sizes costs roughly $0.50–$2.00 depending on voice and
settings. Cached audio is reused on repeat listens so you are never charged
twice for the same content.

Current XAI TTS pricing: **https://docs.x.ai/docs/pricing**

Track your API usage and spending: **https://console.x.ai/usage**

---

## How PasteIt Uses XAI

### Request Flow

1. Text is split into chunks by `TextChunking` (default chunk size from Settings)
2. For each chunk, `XaiCacheInspector` checks whether a cached MP3 already exists
3. If cached — play the local file, no API call made
4. If not cached — `XaiTts` sends the chunk text to the XAI TTS endpoint
5. Response MP3 is saved to the item's audio cache directory
6. Audio plays from the local file

### API Endpoint

```
POST https://api.x.ai/v1/audio/speech
```

Headers:
```
Authorization: Bearer <api-key>
Content-Type: application/json
```

Body:
```json
{
  "model": "grok-2-vision-latest",
  "input": "<chunk text>",
  "voice": "<selected voice>",
  "response_format": "mp3"
}
```

Response is raw MP3 binary, saved directly to disk.

### Cache Key

Before each API call, a cache key is computed from:

- SHA hash of the exact chunk text
- Provider name (`xai`)
- Voice ID
- Language code
- Output format / sample rate / bitrate

Illustrative file path under the public storage model:
```
PasteIt/Library/<item-id>/audio/<cache-key>.mp3
```

If all parameters match an existing file, the file is played and no
API call is made. If any parameter changes (different voice, edited text),
a new file is generated under a new key. Old files are not automatically
deleted — use the cache manager in Settings to clear them.

---

## Chunk Limits

| Engine | Hard limit per chunk |
|---|---|
| XAI API | 15,000 characters |
| Android TTS (fallback) | ~4,000 characters |

XAI's 15,000 char limit is enforced in `TextChunking`. Attempting to send
more than this in a single request will result in an API error.

Standard mode keeps chunks conservative (1,000–2,000 chars recommended)
to avoid wasting API calls on content the user may not finish listening to.
Max Chunk mode uses the full 15,000 char limit for uninterrupted playback
of a known document (see `Road_map` for Max Chunk implementation details).

---

## Voices

XAI offers multiple voices selectable in Settings. Voice selection is stored
in `CloudTtsPreferences` and included in the cache key so switching voices
on the same text generates fresh audio rather than playing mismatched cache.

Current available voices vary by XAI model version — check
**https://docs.x.ai/docs/audio** for the latest list.

---

## Fallback Behavior

If XAI is unavailable (no API key, network error, quota exceeded):

1. `XaiTts` reports failure via `AppTtsCallback.onError()`
2. `PasteItTts` falls back to Android built-in TTS
3. Fallback audio is **not** saved to the XAI cache
4. A toast notifies the user that fallback is active

---

## Preferences Keys (`CloudTtsPreferences`)

| Key | Type | Default | Notes |
|---|---|---|---|
| `xai_api_key` | String | `""` | User's API key |
| `xai_voice` | String | `"nova"` | Selected voice ID |
| `xai_chunk_size` | Int | `2000` | Characters per chunk in Standard mode |
| `xai_enabled` | Boolean | `true` | Whether XAI is the active engine |

---

## Security Notes

- API key may remain in app settings
- Key is never logged, never sent to any server other than `api.x.ai`
- Users should treat their XAI key like a password — do not share it
