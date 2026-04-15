# PasteIt — Quick Paste (Process Text Intent)

## What It Is

Quick Paste adds a **"Send to PasteIt"** option to Android's native
text-selection menu. When a user long-presses and selects text in any app,
PasteIt appears alongside Cut / Copy / Share. Tapping it sends the selected
text directly into PasteIt without touching the clipboard.

---

## Availability — Intended Behavior

**The goal is for "Send to PasteIt" to appear only when PiP is active.**

There is no reason to expose this option when the user is inside the full
app — they can paste directly. Showing it at all times would be noise.

Two implementation paths are documented below. Attempt Path A first.
If it proves unreliable, fall back to Path B and do not attempt to
re-introduce PiP gating — just ship Path B and move on.

---

## Path A — PiP-Gated (Preferred)

### How It Works

The `PROCESS_TEXT` intent filter is placed on a **dedicated separate activity**
(`ProcessTextActivity`) rather than on `MainActivity`. This activity is set to
`DISABLED` by default in the manifest so it does not appear anywhere in the
system at install time.

When PiP activates, the component is enabled via `PackageManager` — Android
registers it and it appears in the text selection menu. When PiP exits, the
component is disabled again and vanishes from the menu entirely. It is not
just non-functional when disabled — it is completely invisible to other apps.

### 1. AndroidManifest.xml

Add `ProcessTextActivity` as a separate entry, disabled by default:

```xml
<activity
    android:name=".ProcessTextActivity"
    android:exported="true"
    android:enabled="false"
    android:theme="@style/Theme.PasteIt.Transparent">

    <intent-filter>
        <action android:name="android.intent.action.PROCESS_TEXT" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>

</activity>
```

`android:enabled="false"` is the default-off state. Do not add this filter
to `MainActivity` — keep them fully separate.

### 2. ProcessTextActivity.kt

A minimal transparent activity that receives the text, forwards it to the
service, then finishes immediately with no UI shown:

```kotlin
class ProcessTextActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()

        if (!selectedText.isNullOrBlank()) {
            val serviceIntent = Intent(this, ClipboardMonitorService::class.java).apply {
                action = ClipboardMonitorService.ACTION_LOAD_TEXT_DIRECTLY
                putExtra(ClipboardMonitorService.EXTRA_DIRECT_TEXT, selectedText)
            }
            startService(serviceIntent)
        }

        finish()
    }
}
```

### 3. ClipboardMonitorService.kt — Handle ACTION_LOAD_TEXT_DIRECTLY

Add constants and handle in `onStartCommand`:

```kotlin
companion object {
    const val ACTION_LOAD_TEXT_DIRECTLY = "com.example.pasteit.LOAD_TEXT_DIRECTLY"
    const val EXTRA_DIRECT_TEXT = "extra_direct_text"
}

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
        ACTION_TOGGLE_PLAYBACK -> togglePlayback()
        ACTION_PASTE_CLIPBOARD -> pasteClipboardContent()
        ACTION_LOAD_TEXT_DIRECTLY -> {
            val text = intent.getStringExtra(EXTRA_DIRECT_TEXT)
            if (!text.isNullOrBlank()) loadTextDirectly(text)
        }
    }
    return START_STICKY
}
```

### 4. MainActivity.kt — Toggle Component on PiP State Change

```kotlin
private fun setProcessTextEnabled(enabled: Boolean) {
    val component = ComponentName(this, ProcessTextActivity::class.java)
    val state = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    packageManager.setComponentEnabledSetting(
        component,
        state,
        PackageManager.DONT_KILL_APP
    )
}

override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    isInPipMode = isInPictureInPictureMode
    updateLayoutForPipMode(isInPictureInPictureMode)
    setProcessTextEnabled(isInPictureInPictureMode)
}
```

### 5. loadTextDirectly() in ClipboardMonitorService

```kotlin
fun loadTextDirectly(text: String) {
    if (isPlaying) stopPlayback()
    currentText = text
    currentPosition = 0
    textChunks = splitTextIntoChunks(text)
    clipboardListener?.invoke(text)
}
```

### Known Risk

Android caches component state and the text selection menu may not update
instantaneously after `setComponentEnabledSetting`. In practice the delay
is negligible, but if testing reveals the option persists visibly in the
menu after PiP exits, fall back to Path B.

---

## Path B — Always Available (Fallback)

If Path A proves unreliable — component toggle is too slow, causes crashes,
or behaves inconsistently across devices — switch to this approach and do
not attempt to re-introduce PiP gating. Ship it and move on.

The difference: the `PROCESS_TEXT` intent filter goes directly on `MainActivity`
and is always enabled. "Send to PasteIt" appears in the text selection menu
at all times regardless of PiP state.

### 1. AndroidManifest.xml

Add a second intent filter to the existing `MainActivity` entry:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:supportsPictureInPicture="true"
    android:configChanges="orientation|keyboardHidden|keyboard|screenSize|screenLayout|smallestScreenSize|uiMode">

    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

    <intent-filter>
        <action android:name="android.intent.action.PROCESS_TEXT" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>

</activity>
```

### 2. MainActivity.kt — Handle in onCreate and onNewIntent

```kotlin
private var pendingProcessText: String? = null

private fun handleProcessTextIntent() {
    if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
        val selectedText = intent
            .getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
        if (!selectedText.isNullOrBlank()) {
            if (serviceBound) {
                clipboardService?.loadTextDirectly(selectedText)
            } else {
                pendingProcessText = selectedText
            }
        }
    }
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ... existing setup ...
    handleProcessTextIntent()
}

override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    setIntent(intent)           // critical — updates getIntent()
    handleProcessTextIntent()
}
```

`onNewIntent` is critical — if the app is already running (which it will be
when PiP is active), Android calls `onNewIntent` instead of `onCreate`.
Without this the selected text is silently dropped.

### 3. Pick Up Pending Text After Service Binds

Inside `serviceConnection.onServiceConnected`:

```kotlin
pendingProcessText?.let { text ->
    clipboardService?.loadTextDirectly(text)
    pendingProcessText = null
}
```

### 4. loadTextDirectly() — same as Path A

```kotlin
fun loadTextDirectly(text: String) {
    if (isPlaying) stopPlayback()
    currentText = text
    currentPosition = 0
    textChunks = splitTextIntoChunks(text)
    clipboardListener?.invoke(text)
}
```

---

## Behavior Summary

| Scenario | Path A | Path B |
|---|---|---|
| PiP active, user selects text | "PasteIt" appears in menu | "PasteIt" appears in menu |
| PiP not active, user selects text | Menu option not visible | Menu option visible |
| App not running when text selected | App launches, loads on bind | App launches, loads on bind |
| Clipboard untouched | ✓ | ✓ |

---

## Implementation Order

1. Implement Path A
2. Test on at least two devices and API levels (API 26 and API 33 recommended)
3. If component toggle is reliable — ship Path A
4. If any issues — remove `ProcessTextActivity`, switch to Path B, do not revisit