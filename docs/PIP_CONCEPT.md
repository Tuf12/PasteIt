<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>PasteIt — PiP UI Concept</title>
<link href="https://fonts.googleapis.com/css2?family=Syne:wght@600;700;800&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
  :root {
    --bg:        #0e0e0f;
    --surface:   #161618;
    --surface2:  #1e1e21;
    --border:    #2a2a2e;
    --amber:     #f5a623;
    --amber-dim: #c47d0e;
    --amber-glow:rgba(245,166,35,0.15);
    --text:      #e8e6e1;
    --text-dim:  #7a7875;
    --green:     #4caf7d;
  }

* { margin:0; padding:0; box-sizing:border-box; }

body {
background: #13131a;
min-height: 100vh;
display: flex;
flex-direction: column;
align-items: center;
justify-content: center;
gap: 60px;
padding: 48px 24px;
font-family: 'Syne', sans-serif;
}

h2 {
color: var(--text-dim);
font-family: 'JetBrains Mono', monospace;
font-size: 11px;
letter-spacing: 3px;
text-transform: uppercase;
text-align: center;
margin-bottom: -40px;
}

/* ── PIP WINDOW SHELL ─────────────────────────── */
.pip-shell {
position: relative;
filter: drop-shadow(0 24px 48px rgba(0,0,0,0.8));
}

.pip-window {
width: 320px;
background: var(--surface);
border-radius: 18px;
border: 1px solid var(--border);
overflow: hidden;
position: relative;
}

/* subtle inner glow on top edge */
.pip-window::before {
content: '';
position: absolute;
top: 0; left: 0; right: 0;
height: 1px;
background: linear-gradient(90deg, transparent, rgba(245,166,35,0.3), transparent);
z-index: 10;
}

/* ── WAVEFORM / AMBIENT BG ───────────────────── */
.pip-ambient {
position: absolute;
inset: 0;
overflow: hidden;
pointer-events: none;
border-radius: 18px;
}

.pip-ambient svg {
position: absolute;
bottom: 0;
left: 0;
width: 100%;
opacity: 0.07;
}

/* ── CONTENT ─────────────────────────────────── */
.pip-content {
position: relative;
z-index: 2;
padding: 14px 16px 12px;
display: flex;
flex-direction: column;
gap: 10px;
}

/* Top row — wordmark + live indicator */
.pip-header {
display: flex;
align-items: center;
justify-content: space-between;
}

.pip-wordmark {
font-size: 13px;
font-weight: 800;
letter-spacing: -0.3px;
color: var(--text-dim);
}
.pip-wordmark span { color: var(--amber); }

.pip-live {
display: flex;
align-items: center;
gap: 5px;
font-family: 'JetBrains Mono', monospace;
font-size: 9px;
letter-spacing: 1.5px;
color: var(--green);
text-transform: uppercase;
}
.pip-live-dot {
width: 5px; height: 5px;
border-radius: 50%;
background: var(--green);
box-shadow: 0 0 6px var(--green);
animation: blink 2s ease-in-out infinite;
}
@keyframes blink {
0%,100% { opacity:1; } 50% { opacity:0.3; }
}

/* Now reading text */
.pip-title {
font-size: 12px;
font-weight: 600;
color: var(--text);
line-height: 1.4;
display: -webkit-box;
-webkit-line-clamp: 2;
-webkit-box-orient: vertical;
overflow: hidden;
padding: 0 2px;
}

/* Progress */
.pip-progress-row {
display: flex;
align-items: center;
gap: 8px;
}

.pip-track {
flex: 1;
height: 2px;
background: var(--border);
border-radius: 1px;
position: relative;
}
.pip-fill {
height: 100%;
width: 38%;
background: linear-gradient(90deg, var(--amber-dim), var(--amber));
border-radius: 1px;
position: relative;
}
.pip-fill::after {
content: '';
position: absolute;
right: -3px; top: -3px;
width: 8px; height: 8px;
background: var(--amber);
border-radius: 50%;
box-shadow: 0 0 6px rgba(245,166,35,0.5);
}

.pip-chunk {
font-family: 'JetBrains Mono', monospace;
font-size: 9px;
color: var(--text-dim);
white-space: nowrap;
flex-shrink: 0;
}

/* Controls row */
.pip-controls {
display: flex;
align-items: center;
justify-content: center;
gap: 10px;
padding: 2px 0 2px;
}

.pip-btn {
width: 34px; height: 34px;
border-radius: 10px;
background: var(--surface2);
border: 1px solid var(--border);
display: flex; align-items: center; justify-content: center;
font-size: 14px;
color: var(--text-dim);
cursor: pointer;
flex-shrink: 0;
}

.pip-btn.play {
width: 46px; height: 46px;
border-radius: 14px;
background: var(--amber);
border: none;
color: #0e0e0f;
font-size: 20px;
box-shadow: 0 4px 16px rgba(245,166,35,0.35);
}

.pip-btn.paste {
background: var(--amber-glow);
border-color: var(--amber-dim);
color: var(--amber);
font-size: 13px;
}

/* ── VARIANTS GRID ───────────────────────────── */
.variants {
display: flex;
gap: 32px;
align-items: flex-start;
flex-wrap: wrap;
justify-content: center;
}

.variant-wrap {
display: flex;
flex-direction: column;
align-items: center;
gap: 12px;
}

.variant-label {
font-family: 'JetBrains Mono', monospace;
font-size: 10px;
letter-spacing: 2px;
color: #444;
text-transform: uppercase;
}

/* ── PAUSED STATE ────────────────────────────── */
.pip-window.paused .pip-fill {
opacity: 0.5;
}
.pip-window.paused .pip-live-dot {
background: var(--text-dim);
box-shadow: none;
animation: none;
}
.pip-window.paused .pip-live {
color: var(--text-dim);
}

/* ── COMPACT (landscape-ish) VARIANT ─────────── */
.pip-window.compact {
width: 280px;
}

.pip-window.compact .pip-content {
padding: 10px 14px 10px;
gap: 8px;
}

.pip-window.compact .pip-title {
-webkit-line-clamp: 1;
font-size: 11px;
}

.pip-window.compact .pip-controls {
gap: 8px;
}

.pip-window.compact .pip-btn {
width: 30px; height: 30px;
border-radius: 8px;
font-size: 12px;
}

.pip-window.compact .pip-btn.play {
width: 40px; height: 40px;
border-radius: 12px;
font-size: 17px;
}

/* ── LOADING STATE ───────────────────────────── */
.pip-window.loading .pip-fill {
background: linear-gradient(90deg, var(--border), var(--surface2), var(--border));
background-size: 200% 100%;
animation: shimmer 1.5s ease-in-out infinite;
width: 100%;
}
.pip-window.loading .pip-fill::after { display: none; }

@keyframes shimmer {
0% { background-position: 200% 0; }
100% { background-position: -200% 0; }
}

.pip-window.loading .pip-live-dot {
background: var(--amber);
box-shadow: 0 0 6px var(--amber);
}
.pip-window.loading .pip-live {
color: var(--amber);
}

.pip-btn.play.loading-spin {
background: var(--surface2);
border: 1px solid var(--amber-dim);
color: var(--amber);
font-size: 16px;
animation: spin 1s linear infinite;
}
@keyframes spin {
from { transform: rotate(0deg); }
to   { transform: rotate(360deg); }
}

/* ── DESCRIPTION TEXT ────────────────────────── */
.desc {
max-width: 680px;
text-align: center;
font-family: 'JetBrains Mono', monospace;
font-size: 11px;
color: #3a3a3e;
line-height: 1.8;
letter-spacing: 0.3px;
}
</style>
</head>
<body>

<h2>PasteIt — PiP Window Concept</h2>

<div class="variants">

  <!-- ── PLAYING ── -->
  <div class="variant-wrap">
    <div class="pip-shell">
      <div class="pip-window">
        <div class="pip-ambient">
          <svg viewBox="0 0 320 80" preserveAspectRatio="none">
            <path d="M0,50 Q20,20 40,45 Q60,70 80,40 Q100,10 120,35 Q140,60 160,30 Q180,0 200,28 Q220,56 240,38 Q260,20 280,42 Q300,64 320,45 L320,80 L0,80Z" fill="#f5a623"/>
          </svg>
        </div>
        <div class="pip-content">
          <div class="pip-header">
            <div class="pip-wordmark">Paste<span>It</span></div>
            <div class="pip-live"><div class="pip-live-dot"></div>Reading</div>
          </div>
          <div class="pip-title">The key insight most people miss is that habits aren't built through willpower — they're built through environment design.</div>
          <div class="pip-progress-row">
            <div class="pip-track"><div class="pip-fill"></div></div>
            <div class="pip-chunk">5 / 13</div>
          </div>
          <div class="pip-controls">
            <div class="pip-btn">⏮</div>
            <div class="pip-btn paste">📋</div>
            <div class="pip-btn play">⏸</div>
            <div class="pip-btn">⏭</div>
          </div>
        </div>
      </div>
    </div>
    <div class="variant-label">Playing</div>
  </div>

  <!-- ── PAUSED ── -->
  <div class="variant-wrap">
    <div class="pip-shell">
      <div class="pip-window paused">
        <div class="pip-ambient">
          <svg viewBox="0 0 320 80" preserveAspectRatio="none">
            <path d="M0,50 Q20,20 40,45 Q60,70 80,40 Q100,10 120,35 Q140,60 160,30 Q180,0 200,28 Q220,56 240,38 Q260,20 280,42 Q300,64 320,45 L320,80 L0,80Z" fill="#f5a623"/>
          </svg>
        </div>
        <div class="pip-content">
          <div class="pip-header">
            <div class="pip-wordmark">Paste<span>It</span></div>
            <div class="pip-live"><div class="pip-live-dot"></div>Paused</div>
          </div>
          <div class="pip-title">The key insight most people miss is that habits aren't built through willpower — they're built through environment design.</div>
          <div class="pip-progress-row">
            <div class="pip-track"><div class="pip-fill"></div></div>
            <div class="pip-chunk">5 / 13</div>
          </div>
          <div class="pip-controls">
            <div class="pip-btn">⏮</div>
            <div class="pip-btn paste">📋</div>
            <div class="pip-btn play">▶</div>
            <div class="pip-btn">⏭</div>
          </div>
        </div>
      </div>
    </div>
    <div class="variant-label">Paused</div>
  </div>

  <!-- ── BUFFERING ── -->
  <div class="variant-wrap">
    <div class="pip-shell">
      <div class="pip-window loading">
        <div class="pip-ambient">
          <svg viewBox="0 0 320 80" preserveAspectRatio="none">
            <path d="M0,50 Q20,20 40,45 Q60,70 80,40 Q100,10 120,35 Q140,60 160,30 Q180,0 200,28 Q220,56 240,38 Q260,20 280,42 Q300,64 320,45 L320,80 L0,80Z" fill="#f5a623"/>
          </svg>
        </div>
        <div class="pip-content">
          <div class="pip-header">
            <div class="pip-wordmark">Paste<span>It</span></div>
            <div class="pip-live"><div class="pip-live-dot"></div>Loading</div>
          </div>
          <div class="pip-title">The key insight most people miss is that habits aren't built through willpower — they're built through environment design.</div>
          <div class="pip-progress-row">
            <div class="pip-track"><div class="pip-fill"></div></div>
            <div class="pip-chunk">5 / 13</div>
          </div>
          <div class="pip-controls">
            <div class="pip-btn">⏮</div>
            <div class="pip-btn paste">📋</div>
            <div class="pip-btn play loading-spin">↻</div>
            <div class="pip-btn">⏭</div>
          </div>
        </div>
      </div>
    </div>
    <div class="variant-label">Buffering</div>
  </div>

  <!-- ── COMPACT ── -->
  <div class="variant-wrap">
    <div class="pip-shell">
      <div class="pip-window compact">
        <div class="pip-ambient">
          <svg viewBox="0 0 280 70" preserveAspectRatio="none">
            <path d="M0,44 Q17,18 35,40 Q53,62 70,35 Q87,8 105,30 Q122,52 140,26 Q157,0 175,24 Q192,48 210,33 Q227,18 245,37 Q262,56 280,40 L280,70 L0,70Z" fill="#f5a623"/>
          </svg>
        </div>
        <div class="pip-content">
          <div class="pip-header">
            <div class="pip-wordmark">Paste<span>It</span></div>
            <div class="pip-live"><div class="pip-live-dot"></div>Reading</div>
          </div>
          <div class="pip-title">habits aren't built through willpower — they're built through environment design.</div>
          <div class="pip-progress-row">
            <div class="pip-track"><div class="pip-fill"></div></div>
            <div class="pip-chunk">5 / 13</div>
          </div>
          <div class="pip-controls">
            <div class="pip-btn">⏮</div>
            <div class="pip-btn paste">📋</div>
            <div class="pip-btn play">⏸</div>
            <div class="pip-btn">⏭</div>
          </div>
        </div>
      </div>
    </div>
    <div class="variant-label">Compact</div>
  </div>

</div>

<div class="desc">
  Dark surface matches the main app theme · Amber accent carries through from the player ·
  Wordmark stays present but recedes · Waveform texture adds depth without competing ·
  Three states: Playing · Paused · Buffering (spinner replaces play icon) ·
  Compact variant for tighter aspect ratios · Progress shows chunk position not time
</div>

</body>
</html>