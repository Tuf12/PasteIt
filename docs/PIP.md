# PasteIt — Picture-in-Picture (PiP)

## What It Is

PiP mode shrinks the app into a small floating overlay that sits on top of
other apps. The user can keep reading or browsing while PasteIt reads aloud
in the background, with basic playback controls always visible.

---

## How It Works

### Entry

PiP is entered automatically via `onUserLeaveHint` in `MainActivity` —
any time the user navigates away from the app (home button, recent apps,
switching to another app), PiP activates without any manual step.

```kotlin
override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    enterPipMode()
}
```

### PiP Parameters

- Aspect ratio: **16:9**
- Remote actions: up to 5 allowed by Android, currently using 2
- Minimum API: Android 8.0 (API 26)

---

## PiP Action Buttons

Two `RemoteAction` buttons appear on the PiP overlay:

| Button | Action | Intent |
|---|---|---|
| Play / Pause toggle | Toggle playback | `ACTION_TOGGLE_PLAYBACK` |
| Paste | Load clipboard text | `ACTION_PASTE_CLIPBOARD` |

Actions are sent as `PendingIntent` to `ClipboardMonitorService.onStartCommand`.
The service handles them without needing the UI to be active.

Play/Pause icon updates dynamically on every playback state change via `updatePipActions()`.

---

## UI Behavior in PiP Mode

When PiP is active the following elements are hidden:

- Settings button, stock format button, save button, library button, paste button
- Playback status text and progress bar
- Text panel (collapsed if expanded)

All elements restore when the user returns to full screen.

---

## Known Limitation — Text Input in PiP

Android only allows `RemoteAction` buttons in a PiP window. There is no way
to interact with UI elements or trigger a clipboard paste from inside the overlay.

**Solution:** The Process Text intent (`quick_paste.md`) lets the user select
text in any app and send it directly to PasteIt via the Android text selection
menu — no clipboard required, works while PiP is active.

---

## Manifest Requirement

```xml
<activity
    android:name=".MainActivity"
    android:supportsPictureInPicture="true"
    android:configChanges="orientation|keyboardHidden|keyboard|screenSize|screenLayout|smallestScreenSize|uiMode" />
```

`configChanges` is required — without it Android destroys and recreates the
activity on every PiP resize, breaking service binding and playback state.

---

## Future Considerations

- Custom PiP layout with progress indicator (requires API 31+)
- Now-playing title encoded into remote action content description