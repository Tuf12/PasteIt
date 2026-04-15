<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
<title>PasteIt — UI Concept</title>
<link href="https://fonts.googleapis.com/css2?family=Syne:wght@400;600;700;800&family=JetBrains+Mono:wght@300;400;500&display=swap" rel="stylesheet">
<style>
  :root {
    --bg:        #0e0e0f;
    --surface:   #161618;
    --surface2:  #1e1e21;
    --border:    #2a2a2e;
    --amber:     #f5a623;
    --amber-dim: #c47d0e;
    --amber-glow:rgba(245,166,35,0.12);
    --text:      #e8e6e1;
    --text-dim:  #7a7875;
    --text-mute: #3d3c3a;
    --green:     #4caf7d;
    --red:       #e05454;
  }

* { margin:0; padding:0; box-sizing:border-box; }

body {
background: #1a1a1f;
display: flex;
align-items: center;
justify-content: center;
min-height: 100vh;
font-family: 'Syne', sans-serif;
padding: 24px 16px;
gap: 32px;
flex-wrap: wrap;
}

/* ── PHONE SHELL ─────────────────────────────── */
.phone {
width: 375px;
background: #0a0a0b;
border-radius: 48px;
padding: 12px;
box-shadow:
0 0 0 1px #2a2a2e,
0 40px 80px rgba(0,0,0,0.7),
inset 0 1px 0 rgba(255,255,255,0.06);
position: relative;
}

.phone-inner {
background: var(--bg);
border-radius: 38px;
overflow: hidden;
height: 780px;
display: flex;
flex-direction: column;
position: relative;
}

/* Status bar */
.status-bar {
padding: 14px 24px 8px;
display: flex;
justify-content: space-between;
align-items: center;
font-family: 'JetBrains Mono', monospace;
font-size: 11px;
color: var(--text-dim);
letter-spacing: 0.5px;
}

.status-icons { display: flex; gap: 6px; align-items: center; }
.signal-bar {
width: 3px; background: var(--text-dim); border-radius: 1px;
}
.signal-bar:nth-child(1) { height: 4px; }
.signal-bar:nth-child(2) { height: 7px; }
.signal-bar:nth-child(3) { height: 10px; }
.signal-bar:nth-child(4) { height: 13px; }

/* ── HEADER ──────────────────────────────────── */
.header {
padding: 8px 24px 16px;
display: flex;
align-items: center;
justify-content: space-between;
border-bottom: 1px solid var(--border);
}

.wordmark {
font-size: 22px;
font-weight: 800;
letter-spacing: -0.5px;
color: var(--text);
}
.wordmark span {
color: var(--amber);
}

.header-actions {
display: flex;
gap: 8px;
}

.icon-btn {
width: 36px; height: 36px;
border-radius: 10px;
background: var(--surface2);
border: 1px solid var(--border);
display: flex; align-items: center; justify-content: center;
cursor: pointer;
transition: all 0.15s;
color: var(--text-dim);
font-size: 15px;
}
.icon-btn:hover { border-color: var(--amber); color: var(--amber); }
.icon-btn.active { background: var(--amber-glow); border-color: var(--amber); color: var(--amber); }

/* ── NOW PLAYING CARD ────────────────────────── */
.now-playing {
margin: 16px;
background: var(--surface);
border: 1px solid var(--border);
border-radius: 20px;
padding: 20px;
position: relative;
overflow: hidden;
}

.now-playing::before {
content: '';
position: absolute;
top: -40px; right: -40px;
width: 120px; height: 120px;
background: radial-gradient(circle, var(--amber-glow) 0%, transparent 70%);
pointer-events: none;
}

.np-label {
font-family: 'JetBrains Mono', monospace;
font-size: 10px;
font-weight: 500;
letter-spacing: 2px;
color: var(--amber);
text-transform: uppercase;
margin-bottom: 10px;
display: flex;
align-items: center;
gap: 8px;
}

.np-label::before {
content: '';
display: inline-block;
width: 6px; height: 6px;
border-radius: 50%;
background: var(--green);
box-shadow: 0 0 8px var(--green);
animation: pulse 2s ease-in-out infinite;
}

@keyframes pulse {
0%, 100% { opacity: 1; }
50% { opacity: 0.4; }
}

.np-title {
font-size: 15px;
font-weight: 600;
color: var(--text);
line-height: 1.4;
margin-bottom: 16px;
/* truncate to 2 lines */
display: -webkit-box;
-webkit-line-clamp: 2;
-webkit-box-orient: vertical;
overflow: hidden;
}

/* Progress bar */
.progress-track {
height: 3px;
background: var(--border);
border-radius: 2px;
margin-bottom: 8px;
position: relative;
overflow: visible;
}

.progress-fill {
height: 100%;
width: 38%;
background: linear-gradient(90deg, var(--amber-dim), var(--amber));
border-radius: 2px;
position: relative;
}

.progress-fill::after {
content: '';
position: absolute;
right: -4px; top: -4px;
width: 11px; height: 11px;
background: var(--amber);
border-radius: 50%;
box-shadow: 0 0 8px var(--amber-glow);
}

.progress-meta {
display: flex;
justify-content: space-between;
font-family: 'JetBrains Mono', monospace;
font-size: 10px;
color: var(--text-dim);
margin-bottom: 20px;
}

.chunk-badge {
background: var(--surface2);
border: 1px solid var(--border);
border-radius: 6px;
padding: 2px 8px;
font-family: 'JetBrains Mono', monospace;
font-size: 10px;
color: var(--text-dim);
}

/* Controls */
.controls {
display: flex;
align-items: center;
justify-content: center;
gap: 16px;
}

.ctrl-btn {
width: 44px; height: 44px;
border-radius: 14px;
background: var(--surface2);
border: 1px solid var(--border);
display: flex; align-items: center; justify-content: center;
cursor: pointer;
color: var(--text-dim);
font-size: 18px;
transition: all 0.15s;
position: relative;
}
.ctrl-btn:hover { border-color: var(--text-dim); color: var(--text); }

.ctrl-btn.play {
width: 60px; height: 60px;
border-radius: 18px;
background: var(--amber);
border: none;
color: #0e0e0f;
font-size: 24px;
box-shadow: 0 4px 20px rgba(245,166,35,0.35);
}
.ctrl-btn.play:hover {
background: #f7b84a;
box-shadow: 0 6px 28px rgba(245,166,35,0.5);
}

.ctrl-btn.paste {
background: var(--amber-glow);
border-color: var(--amber-dim);
color: var(--amber);
}

/* ── TEXT PANEL ──────────────────────────────── */
.text-panel {
flex: 1;
margin: 0 16px 16px;
background: var(--surface);
border: 1px solid var(--border);
border-radius: 20px;
overflow: hidden;
display: flex;
flex-direction: column;
min-height: 0;
}

.text-panel-header {
padding: 12px 16px;
border-bottom: 1px solid var(--border);
display: flex;
align-items: center;
justify-content: space-between;
}

.text-panel-label {
font-family: 'JetBrains Mono', monospace;
font-size: 10px;
letter-spacing: 2px;
color: var(--text-dim);
text-transform: uppercase;
}

.text-meta {
display: flex;
gap: 8px;
align-items: center;
}

.tag {
font-family: 'JetBrains Mono', monospace;
font-size: 9px;
letter-spacing: 1px;
padding: 2px 7px;
border-radius: 4px;
text-transform: uppercase;
}
.tag.chars { background: var(--surface2); color: var(--text-dim); border: 1px solid var(--border); }
.tag.saved { background: rgba(76,175,125,0.1); color: var(--green); border: 1px solid rgba(76,175,125,0.2); }

.text-body {
flex: 1;
padding: 16px;
overflow-y: auto;
font-family: 'JetBrains Mono', monospace;
font-size: 12.5px;
line-height: 1.8;
color: var(--text-dim);
scrollbar-width: thin;
scrollbar-color: var(--border) transparent;
}

/* highlight the "currently reading" chunk */
.text-highlight {
color: var(--text);
background: linear-gradient(180deg, transparent 60%, rgba(245,166,35,0.12) 100%);
border-radius: 3px;
}

/* ── BOTTOM NAV ──────────────────────────────── */
.bottom-nav {
padding: 10px 24px 24px;
display: flex;
justify-content: space-around;
border-top: 1px solid var(--border);
}

.nav-item {
display: flex;
flex-direction: column;
align-items: center;
gap: 4px;
cursor: pointer;
opacity: 0.4;
transition: opacity 0.15s;
}
.nav-item.active { opacity: 1; }
.nav-item.active .nav-icon { color: var(--amber); }
.nav-item.active .nav-label { color: var(--amber); }

.nav-icon { font-size: 20px; color: var(--text-dim); }
.nav-label {
font-family: 'JetBrains Mono', monospace;
font-size: 9px;
letter-spacing: 1px;
text-transform: uppercase;
color: var(--text-dim);
}

/* ── SETTINGS SCREEN ─────────────────────────── */
.settings-screen .text-panel {
min-height: 0;
}

.settings-content {
flex: 1;
overflow-y: auto;
padding: 16px;
display: flex;
flex-direction: column;
gap: 12px;
scrollbar-width: thin;
scrollbar-color: var(--border) transparent;
}

.settings-group {
background: var(--surface);
border: 1px solid var(--border);
border-radius: 16px;
overflow: hidden;
}

.settings-group-label {
padding: 10px 16px 8px;
font-family: 'JetBrains Mono', monospace;
font-size: 9px;
letter-spacing: 2px;
text-transform: uppercase;
color: var(--amber);
border-bottom: 1px solid var(--border);
}

.settings-row {
padding: 14px 16px;
display: flex;
align-items: center;
justify-content: space-between;
border-bottom: 1px solid var(--border);
}
.settings-row:last-child { border-bottom: none; }

.settings-row-label {
font-size: 14px;
font-weight: 600;
color: var(--text);
margin-bottom: 2px;
}
.settings-row-sub {
font-family: 'JetBrains Mono', monospace;
font-size: 10px;
color: var(--text-dim);
}

.slider-row {
flex-direction: column;
align-items: stretch;
gap: 10px;
}

.slider-header {
display: flex;
justify-content: space-between;
align-items: flex-end;
}

.slider-val {
font-family: 'JetBrains Mono', monospace;
font-size: 18px;
font-weight: 500;
color: var(--amber);
}

.slider-track {
height: 4px;
background: var(--border);
border-radius: 2px;
position: relative;
}
.slider-fill {
height: 100%;
border-radius: 2px;
background: linear-gradient(90deg, var(--amber-dim), var(--amber));
position: relative;
}
.slider-fill::after {
content: '';
position: absolute;
right: -5px; top: -5px;
width: 14px; height: 14px;
background: var(--amber);
border-radius: 50%;
box-shadow: 0 0 10px rgba(245,166,35,0.4);
}

.slider-labels {
display: flex;
justify-content: space-between;
font-family: 'JetBrains Mono', monospace;
font-size: 9px;
color: var(--text-mute);
letter-spacing: 1px;
text-transform: uppercase;
margin-top: 4px;
}

/* toggle */
.toggle {
width: 44px; height: 24px;
border-radius: 12px;
background: var(--border);
position: relative;
cursor: pointer;
transition: background 0.2s;
}
.toggle.on { background: var(--amber); }
.toggle::after {
content: '';
position: absolute;
width: 18px; height: 18px;
border-radius: 9px;
background: white;
top: 3px; left: 3px;
transition: transform 0.2s;
box-shadow: 0 1px 3px rgba(0,0,0,0.3);
}
.toggle.on::after { transform: translateX(20px); }

/* select dropdown mock */
.select-mock {
background: var(--surface2);
border: 1px solid var(--border);
border-radius: 8px;
padding: 6px 12px;
font-family: 'JetBrains Mono', monospace;
font-size: 11px;
color: var(--text);
display: flex;
align-items: center;
gap: 6px;
cursor: pointer;
}
.select-mock::after { content: '▾'; color: var(--amber); }

/* API key field */
.api-field {
background: var(--surface2);
border: 1px solid var(--border);
border-radius: 8px;
padding: 8px 12px;
font-family: 'JetBrains Mono', monospace;
font-size: 11px;
color: var(--text-dim);
letter-spacing: 2px;
width: 100%;
display: flex;
align-items: center;
justify-content: space-between;
}

/* ── LIBRARY SCREEN ──────────────────────────── */
.library-content {
flex: 1;
overflow-y: auto;
padding: 12px 16px;
display: flex;
flex-direction: column;
gap: 8px;
scrollbar-width: thin;
scrollbar-color: var(--border) transparent;
}

.lib-item {
background: var(--surface);
border: 1px solid var(--border);
border-radius: 14px;
padding: 14px;
display: flex;
gap: 12px;
cursor: pointer;
transition: border-color 0.15s;
}
.lib-item:hover { border-color: var(--amber-dim); }
.lib-item.active { border-color: var(--amber); background: var(--amber-glow); }

.lib-thumb {
width: 40px; height: 40px;
border-radius: 10px;
background: var(--surface2);
border: 1px solid var(--border);
display: flex; align-items: center; justify-content: center;
font-size: 18px;
flex-shrink: 0;
}

.lib-info { flex: 1; min-width: 0; }
.lib-title {
font-size: 13px;
font-weight: 700;
color: var(--text);
margin-bottom: 3px;
white-space: nowrap;
overflow: hidden;
text-overflow: ellipsis;
}
.lib-meta {
font-family: 'JetBrains Mono', monospace;
font-size: 10px;
color: var(--text-dim);
}
.lib-cached {
font-family: 'JetBrains Mono', monospace;
font-size: 9px;
padding: 2px 6px;
border-radius: 4px;
background: rgba(76,175,125,0.1);
color: var(--green);
border: 1px solid rgba(76,175,125,0.2);
align-self: center;
white-space: nowrap;
}

/* ── LABELS ──────────────────────────────────── */
.screen-label {
text-align: center;
margin-top: 12px;
font-family: 'JetBrains Mono', monospace;
font-size: 11px;
color: #444;
letter-spacing: 2px;
text-transform: uppercase;
}
</style>
</head>
<body>

<!-- ════════════════════════════════ SCREEN 1: MAIN ═══ -->
<div>
<div class="phone">
  <div class="phone-inner">

    <!-- Status bar -->
    <div class="status-bar">
      <span>9:41</span>
      <div class="status-icons">
        <div style="display:flex;gap:2px;align-items:flex-end">
          <div class="signal-bar"></div>
          <div class="signal-bar"></div>
          <div class="signal-bar"></div>
          <div class="signal-bar"></div>
        </div>
        <span>WiFi</span>
        <span>⚡ 87%</span>
      </div>
    </div>

    <!-- Header -->
    <div class="header">
      <div class="wordmark">Paste<span>It</span></div>
      <div class="header-actions">
        <div class="icon-btn">⊕</div>
        <div class="icon-btn active">⚙</div>
      </div>
    </div>

    <!-- Now Playing -->
    <div class="now-playing">
      <div class="np-label">Now Reading</div>
      <div class="np-title">The unorganized thoughts on building habits that actually stick, from Chapter 4 of Success Framework...</div>

      <div class="progress-track">
        <div class="progress-fill"></div>
      </div>
      <div class="progress-meta">
        <span>Chunk 5 of 13</span>
        <div class="chunk-badge">XAI · TTS</div>
      </div>

      <!-- Controls -->
      <div class="controls">
        <div class="ctrl-btn" title="Rewind">⏮</div>
        <div class="ctrl-btn paste" title="Paste from clipboard">📋</div>
        <div class="ctrl-btn play">⏸</div>
        <div class="ctrl-btn" title="Fast Forward">⏭</div>
        <div class="ctrl-btn" title="Save to Library">🔖</div>
      </div>
    </div>

    <!-- Text Panel -->
    <div class="text-panel">
      <div class="text-panel-header">
        <span class="text-panel-label">Text View</span>
        <div class="text-meta">
          <span class="tag chars">1,842 chars</span>
          <span class="tag saved">Saved</span>
        </div>
      </div>
      <div class="text-body">
        The key insight most people miss is that habits aren't built through willpower — they're built through environment design. When you remove the friction between you and a good habit, and add friction between you and a bad one, <span class="text-highlight">your brain stops fighting itself and starts working with you instead of against you.</span> The compound effect of small daily actions is what separates those who achieve long-term change from those who stay stuck in cycles of motivation and burnout...
      </div>
    </div>

    <!-- Bottom Nav -->
    <div class="bottom-nav">
      <div class="nav-item active">
        <div class="nav-icon">▶</div>
        <div class="nav-label">Player</div>
      </div>
      <div class="nav-item">
        <div class="nav-icon">☰</div>
        <div class="nav-label">Library</div>
      </div>
      <div class="nav-item">
        <div class="nav-icon">⚙</div>
        <div class="nav-label">Settings</div>
      </div>
    </div>

  </div>
</div>
<div class="screen-label">Main Player</div>
</div>


<!-- ════════════════════════════════ SCREEN 2: LIBRARY ═══ -->
<div>
<div class="phone">
  <div class="phone-inner">

    <div class="status-bar">
      <span>9:41</span>
      <div class="status-icons">
        <div style="display:flex;gap:2px;align-items:flex-end">
          <div class="signal-bar"></div><div class="signal-bar"></div>
          <div class="signal-bar"></div><div class="signal-bar"></div>
        </div>
        <span>⚡ 87%</span>
      </div>
    </div>

    <div class="header">
      <div class="wordmark">Paste<span>It</span></div>
      <div class="header-actions">
        <div class="icon-btn">🔍</div>
      </div>
    </div>

    <!-- Library list -->
    <div class="library-content">

      <div class="lib-item active">
        <div class="lib-thumb">📖</div>
        <div class="lib-info">
          <div class="lib-title">Success Framework — Ch.4</div>
          <div class="lib-meta">1,842 chars · 13 chunks · XAI</div>
        </div>
        <div class="lib-cached">● cached</div>
      </div>

      <div class="lib-item">
        <div class="lib-thumb">📝</div>
        <div class="lib-info">
          <div class="lib-title">PROCESS_TEXT_INTENT.md</div>
          <div class="lib-meta">3,201 chars · 21 chunks · Android TTS</div>
        </div>
        <div class="lib-cached">● cached</div>
      </div>

      <div class="lib-item">
        <div class="lib-thumb">📄</div>
        <div class="lib-info">
          <div class="lib-title">Optimal X — Sprint Notes</div>
          <div class="lib-meta">892 chars · 6 chunks · XAI</div>
        </div>
        <div class="lib-cached">● cached</div>
      </div>

      <div class="lib-item">
        <div class="lib-thumb">✍️</div>
        <div class="lib-info">
          <div class="lib-title">Unorganized Book Draft</div>
          <div class="lib-meta">48,220 chars · 312 chunks · XAI</div>
        </div>
        <div style="font-family:'JetBrains Mono',monospace;font-size:9px;padding:2px 6px;border-radius:4px;background:rgba(245,166,35,0.08);color:var(--amber);border:1px solid rgba(245,166,35,0.2);align-self:center;white-space:nowrap;">▶ 38%</div>
      </div>

      <div class="lib-item">
        <div class="lib-thumb">📋</div>
        <div class="lib-info">
          <div class="lib-title">Clipboard — Apr 11</div>
          <div class="lib-meta">244 chars · 2 chunks · Android TTS</div>
        </div>
        <div class="lib-cached" style="background:rgba(224,84,84,0.1);color:var(--red);border-color:rgba(224,84,84,0.2);">no cache</div>
      </div>

    </div>

    <div class="bottom-nav">
      <div class="nav-item">
        <div class="nav-icon">▶</div>
        <div class="nav-label">Player</div>
      </div>
      <div class="nav-item active">
        <div class="nav-icon">☰</div>
        <div class="nav-label">Library</div>
      </div>
      <div class="nav-item">
        <div class="nav-icon">⚙</div>
        <div class="nav-label">Settings</div>
      </div>
    </div>

  </div>
</div>
<div class="screen-label">Library</div>
</div>


<!-- ════════════════════════════════ SCREEN 3: SETTINGS ═══ -->
<div>
<div class="phone">
  <div class="phone-inner">

    <div class="status-bar">
      <span>9:41</span>
      <div class="status-icons">
        <div style="display:flex;gap:2px;align-items:flex-end">
          <div class="signal-bar"></div><div class="signal-bar"></div>
          <div class="signal-bar"></div><div class="signal-bar"></div>
        </div>
        <span>⚡ 87%</span>
      </div>
    </div>

    <div class="header">
      <div class="wordmark">Paste<span>It</span></div>
    </div>

    <div class="settings-content">

      <!-- TTS Engine -->
      <div class="settings-group">
        <div class="settings-group-label">TTS Engine</div>
        <div class="settings-row">
          <div>
            <div class="settings-row-label">Engine</div>
            <div class="settings-row-sub">Active provider</div>
          </div>
          <div class="select-mock">XAI API</div>
        </div>
        <div class="settings-row">
          <div>
            <div class="settings-row-label">Fallback</div>
            <div class="settings-row-sub">When offline</div>
          </div>
          <div class="select-mock">Android TTS</div>
        </div>
        <div class="settings-row">
          <div>
            <div class="settings-row-label">Voice</div>
            <div class="settings-row-sub">XAI voice model</div>
          </div>
          <div class="select-mock">Nova</div>
        </div>
      </div>

      <!-- API -->
      <div class="settings-group">
        <div class="settings-group-label">API Key</div>
        <div class="settings-row" style="flex-direction:column;align-items:stretch;gap:8px;">
          <div class="api-field">
            <span>xai-••••••••••••••••••••••</span>
            <span style="color:var(--amber);font-size:11px;cursor:pointer;">Edit</span>
          </div>
        </div>
      </div>

      <!-- Playback -->
      <div class="settings-group">
        <div class="settings-group-label">Playback</div>

        <div class="settings-row slider-row">
          <div class="slider-header">
            <div>
              <div class="settings-row-label">Speech Rate</div>
            </div>
            <div class="slider-val">120%</div>
          </div>
          <div class="slider-track"><div class="slider-fill" style="width:60%"></div></div>
          <div class="slider-labels"><span>Slow</span><span>Normal</span><span>Fast</span></div>
        </div>

        <div class="settings-row slider-row">
          <div class="slider-header">
            <div>
              <div class="settings-row-label">Speech Pitch</div>
            </div>
            <div class="slider-val">100%</div>
          </div>
          <div class="slider-track"><div class="slider-fill" style="width:50%"></div></div>
          <div class="slider-labels"><span>Low</span><span>Normal</span><span>High</span></div>
        </div>

        <div class="settings-row slider-row">
          <div class="slider-header">
            <div>
              <div class="settings-row-label">Chunk Size</div>
            </div>
            <div class="slider-val">150</div>
          </div>
          <div class="slider-track"><div class="slider-fill" style="width:45%"></div></div>
          <div class="slider-labels"><span>50</span><span>150</span><span>400</span></div>
        </div>

      </div>

      <!-- Cache -->
      <div class="settings-group">
        <div class="settings-group-label">Cache</div>
        <div class="settings-row">
          <div>
            <div class="settings-row-label">Auto-cache audio</div>
            <div class="settings-row-sub">Save API responses locally</div>
          </div>
          <div class="toggle on"></div>
        </div>
        <div class="settings-row">
          <div>
            <div class="settings-row-label">Cache size</div>
            <div class="settings-row-sub">312 MB used</div>
          </div>
          <div style="font-family:'JetBrains Mono',monospace;font-size:11px;color:var(--amber);cursor:pointer;">Clear</div>
        </div>
      </div>

    </div>

    <div class="bottom-nav">
      <div class="nav-item">
        <div class="nav-icon">▶</div>
        <div class="nav-label">Player</div>
      </div>
      <div class="nav-item">
        <div class="nav-icon">☰</div>
        <div class="nav-label">Library</div>
      </div>
      <div class="nav-item active">
        <div class="nav-icon">⚙</div>
        <div class="nav-label">Settings</div>
      </div>
    </div>

  </div>
</div>
<div class="screen-label">Settings</div>
</div>

</body>
</html>