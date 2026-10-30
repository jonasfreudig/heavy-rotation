'use strict';

/*
 * UI for Heavy Rotation (Material You). Inside the app, window.ShelfNative is the bridge to the
 * Kotlin Core; opened in a normal browser, mock.js stands in for it so the interface can be
 * previewed without a phone or accounts (add ?mock=new or ?mock=spin to the URL for other states).
 *
 * Native -> UI: onState(state), onCollection(records), onRelease(release), onActivity(entries),
 *               onToast(msg), onTheme({dark, c}), onInsets({top, bottom, ime}), onOpenSpin()
 * UI -> native: send({t: ...}); the messages are listed in Core.handle.
 */

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const Native = window.ShelfNative;
const send = (msg) => (Native ? Native.send(JSON.stringify(msg)) : Mock.handle(msg));

let showTidalForm = false;
let ST = { lastfm: {}, discogs: {}, prefs: { theme: 'system', defaultTrackSec: 210 }, queue: {}, spin: null, version: '' };
let COLL = [];
let ACT = [];
let CMP = null; // last comparison of shadow mode with Last.fm
let STATS = null; // stats for the chosen range (see Stats.kt)
let statsRange = ''; // the stats period, set in build()
let topKind = 'artists';
const REL = {};
let tab = 'shelf';
const pageStack = [];
let recordId = 0;
let showKeyForm = false;
const shelf = { sort: 'added', rev: false, decade: 0, genre: '', view: 'grid' };
/** The shelf as a grid of covers, a grid of big covers, or a list with the details. */
const SHELF_VIEWS = [['grid', 'apps', 'Grid'], ['big', 'big-grid', 'Big covers'], ['list', 'view-list', 'List']];

// ---------- constants ----------

const FLIP_GAP_MS = 20000;
const MAX_AGE_MS = 14 * 864e5;
const SORTS = [
  { id: 'added', labels: ['Recently added', 'First added'], cmp: (a, b) => b.added - a.added },
  { id: 'artist', labels: ['Artist A–Z', 'Artist Z–A'], cmp: (a, b) => coll.compare(a._artist, b._artist) || (a.year || 9999) - (b.year || 9999) },
  { id: 'year', labels: ['Year, oldest', 'Year, newest'], cmp: (a, b) => (a.year || 9999) - (b.year || 9999) || coll.compare(a._artist, b._artist) },
  { id: 'plays', labels: ['Most played', 'Least played'], cmp: (a, b) => (b.plays - a.plays) || (b.spins - a.spins) || coll.compare(a._artist, b._artist) },
];
const DEF_LENGTHS = [180, 210, 240, 300];
const SRC_NAMES = { discogs: 'Discogs', musicbrainz: 'MusicBrainz', lastfm: 'Last.fm' };
const coll = new Intl.Collator(undefined, { sensitivity: 'base', numeric: true });

// ---------- helpers ----------

function el(tag, cls, html) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (html != null) e.innerHTML = html;
  return e;
}
const icon = (name, cls = 'i') => `<svg class="${cls}"><use href="#i-${name}"/></svg>`;
/** A record label with its catalog number, as Discogs gives them (copies kept from before 0.53 joined them with a middle dot). */
const labelText = (x) => String(x || '').replace(/ · /g, ', ');
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
/** A picture's address for url('…') in a style attribute: quotes, brackets and the like can't end it early. */
const cssUrl = (u) => esc(String(u == null ? '' : u).replace(/['"()\\\s]/g, (c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')));
const norm = (s) => String(s || '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '');
const pad = (n) => String(n).padStart(2, '0');
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;

function fmtDur(sec) {
  const s = Math.max(0, Math.round(sec));
  const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
  return h ? `${h}:${pad(m)}:${pad(x)}` : `${m}:${pad(x)}`;
}
/** "1 h 5 min", "12 min": rounded to the minute first, so 7190 s is 2 h 0 min rather than 1 h 0 min. */
function fmtLong(sec) {
  const m = Math.round(sec / 60);
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${Math.max(1, m)} min`;
}
const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const startOfDay = (ms) => { const d = new Date(ms); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime(); };
function dayLabel(ms) {
  const diff = Math.round((startOfDay(Date.now()) - startOfDay(ms)) / 864e5);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Yesterday';
  const d = new Date(ms);
  return d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short', year: d.getFullYear() !== new Date().getFullYear() ? 'numeric' : undefined });
}
const whenLabel = (ms) => (startOfDay(ms) === startOfDay(Date.now()) ? fmtTime(ms) : `${dayLabel(ms)}, ${fmtTime(ms)}`);
function ago(ms) {
  const s = (Date.now() - ms) / 1000;
  if (s < 90) return 'just now';
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  const d = Math.round(s / 86400);
  return d === 1 ? 'yesterday' : `${d} days ago`;
}
const toLocalInput = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`; };
function fromLocalInput(v) {
  const m = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d)/.exec(v || '');
  return m ? new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5]).getTime() : NaN;
}
const sideLabel = (s) => (!s ? 'Tracks' : s.length <= 2 ? `Side ${s}` : s);

function hue(str) {
  let h = 0;
  for (const ch of String(str)) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return h % 360;
}
/** Cover image over a colored tile with the first letter, which shows while loading or when there is no image. */
function coverHtml(url, title, artist, cls = '', playing = false) {
  const ini = esc(String(title || '?').trim().charAt(0).toUpperCase());
  const img = url ? `<img src="${esc(url)}" alt="" loading="lazy" decoding="async">` : '';
  const eq = playing ? '<span class="eq"><i></i><i></i><i></i></span>' : '';
  return `<div class="cover ${cls}" style="--h:${hue((title || '') + (artist || ''))}"><span>${ini}</span>${img}${eq}</div>`;
}
// A cover that doesn't load goes, leaving the tile with the letter. One listener for all of them (the
// page's CSP allows no inline handlers); error doesn't bubble, so it listens on the way down.
document.addEventListener('error', (e) => { if (e.target.matches && e.target.matches('.cover > img')) e.target.remove(); }, true);

/**
 * A big cover that tilts with the phone (see Tilt). Records on your shelf ([opts.owned]) are the
 * collector's edition: holo foil and the record sliding out of the sleeve. Every
 * other cover tilts and catches the light, nothing more.
 */
function holoCover(url, title, artist, opts = {}) {
  const owned = !!opts.owned;
  const withDisc = owned && opts.disc !== false;
  const lbl = url ? `background-image:url('${cssUrl(url)}')` : '';
  const disc = withDisc ? `<div class="disc"><div class="vinyl"><i class="lbl" style="--h:${hue((title || '') + (artist || ''))};${lbl}"></i></div></div>` : '';
  const sheen = owned ? '<i class="sheen"></i>' : '';
  const back = opts.back ? '<div class="sleeve-back"></div>' : '';
  return `<div class="holo ${owned ? 'foil' : 'plain'} ${withDisc ? 'with-disc' : ''} ${opts.frame ? 'framed' : ''} ${opts.back && turnHintDue() ? 'nudge' : ''}" data-holo ${opts.back ? 'data-flip' : ''} ${opts.rel ? `data-rel="${opts.rel}"` : ''} data-key="${esc(opts.key || url || title)}" data-cover="${esc(url || '')}" style="--h:${hue((title || '') + (artist || ''))}">
    <div class="h3d">${disc}<div class="sleeve">${coverHtml(url, title, artist, 'big')}<span class="fx">${sheen}<i class="glare"></i></span></div>${back}</div></div>`;
}

/*
 * Until a sleeve has been turned once, a turnable one starts to turn on its own when its page
 * opens (the first three times): that it turns over needs no words, and nothing on the cover.
 */
function turnHintDue() {
  try { return Number(localStorage.getItem('turnHint') || 0) < 3; } catch (_) { return false; }
}
document.addEventListener('animationstart', (e) => {
  if (e.animationName !== 'sleeveNudge') return;
  try { localStorage.setItem('turnHint', String(Number(localStorage.getItem('turnHint') || 0) + 1)); } catch (_) { /* preview without storage */ }
});

/**
 * Moving covers: the phone's tilt (from MainActivity, ~30 per second, only while a big
 * cover is showing) eases the sleeves on the top page around. A mouse or a sideways drag
 * tilts them too; without any sensor they sway gently on their own.
 */
const Tilt = {
  els: [], x: 0, y: 0, tx: 0, ty: 0, px: null, py: 0, raf: 0, on: false, seen: false, t0: 0,
  allowed() {
    let pref = true;
    try { pref = localStorage.getItem('tilt') !== 'off'; } catch (_) { /* preview without storage */ }
    return pref && !matchMedia('(prefers-reduced-motion: reduce)').matches;
  },
  refresh() {
    const top = pageStack[pageStack.length - 1];
    this.els = !$('#story').hidden ? $$('#story [data-holo]') : top ? $$(`#${top} [data-holo]`) : [];
    const want = this.els.length > 0 && this.allowed();
    if (want !== this.on) {
      this.on = want;
      send({ t: 'tilt', on: want });
      cancelAnimationFrame(this.raf);
      if (want) { this.t0 = performance.now(); this.raf = requestAnimationFrame((t) => this.step(t)); }
    }
    if (!want) $$('[data-holo]').forEach((el) => { el.style.setProperty('--tx', 0); el.style.setProperty('--ty', 0); el.style.setProperty('--m', 0); });
  },
  step(now) {
    if (!this.on) return;
    if (this.hold) { this.raf = requestAnimationFrame((t) => this.step(t)); return; }
    let tx = this.tx, ty = this.ty;
    if (this.px != null) { tx = this.px; ty = this.py; } else if (!this.seen) {
      const s = (now - this.t0) / 1000;
      tx = Math.sin(s * 0.8) * 0.35;
      ty = Math.cos(s * 0.6) * 0.22;
    }
    this.x += (tx - this.x) * 0.14;
    this.y += (ty - this.y) * 0.14;
    for (const el of this.els) {
      // While a sleeve turns over it holds still; afterwards it eases from there back to the phone's tilt.
      if (el.classList.contains('turning')) {
        if (!el._held) el._held = [el._tx ?? this.x, el._ty ?? this.y];
        el._back = 0;
        continue;
      }
      let x = this.x, y = this.y;
      if (el._held) {
        if (!el._back) el._back = now;
        const k = Math.min(1, (now - el._back) / 450);
        const e = k * k * (3 - 2 * k);
        x = el._held[0] + (x - el._held[0]) * e;
        y = el._held[1] + (y - el._held[1]) * e;
        if (k >= 1) el._held = null;
      }
      // Only when the light visibly moves: every write repaints the foil, the glare and the shadow.
      if (Math.abs(x - (el._tx ?? 9)) < 0.004 && Math.abs(y - (el._ty ?? 9)) < 0.004) continue;
      el._tx = x;
      el._ty = y;
      el.style.setProperty('--tx', x.toFixed(3));
      el.style.setProperty('--ty', y.toFixed(3));
      el.style.setProperty('--m', Math.min(1, Math.hypot(x, y)).toFixed(3));
    }
    this.raf = requestAnimationFrame((t) => this.step(t));
  },
};
window.onTilt = (x, y) => { Tilt.seen = true; Tilt.tx = x; Tilt.ty = y; };

/**
 * Segmented controls, tabs and the tab bar: one pill (a dot under the period tabs) slides to the
 * chosen part. It follows the "on" class wherever that changes; a control that is drawn again
 * starts its pill where the same control had it, so it slides then too.
 */
const Pill = {
  BOX: '.seg, .ptabs, .views, .navbar',
  last: new Map(),
  seen: new WeakSet(),
  ro: null,
  sync(box) {
    if (!box.isConnected) return;
    if (!Pill.seen.has(box)) { Pill.seen.add(box); Pill.ro.observe(box); }
    let pill = box.querySelector(':scope > .pill');
    const fresh = !pill;
    if (fresh) {
      pill = document.createElement('i');
      pill.className = 'pill';
      box.prepend(pill);
    }
    const on = box.querySelector(':scope > .on');
    const b = box.getBoundingClientRect();
    if (!on) { pill.style.opacity = '0'; return; }
    if (!b.width) return;
    const t = (on.querySelector(':scope > .ind') || on).getBoundingClientRect();
    const key = box.id || box.className;
    const now = { transform: `translate(${(t.left - b.left - box.clientLeft).toFixed(1)}px, ${(t.top - b.top - box.clientTop).toFixed(1)}px)`, width: `${t.width}px`, height: `${t.height}px`, opacity: '' };
    if (fresh) {
      pill.style.transition = 'none';
      Object.assign(pill.style, Pill.last.get(key) || now);
      void pill.offsetWidth;
      pill.style.transition = '';
    }
    Object.assign(pill.style, now);
    Pill.last.set(key, now);
  },
  init() {
    Pill.ro = new ResizeObserver((es) => es.forEach((e) => Pill.sync(e.target)));
    new MutationObserver((ms) => {
      const boxes = new Set();
      for (const m of ms) {
        const t = m.target;
        if (m.type === 'attributes') {
          if (t.parentElement && t.parentElement.matches(Pill.BOX)) boxes.add(t.parentElement);
          continue;
        }
        if (t.matches(Pill.BOX)) { boxes.add(t); continue; }
        // One look through the changed part (a whole shelf grid is a single query).
        if (m.addedNodes.length) t.querySelectorAll(Pill.BOX).forEach((x) => boxes.add(x));
      }
      boxes.forEach(Pill.sync);
    }).observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class'] });
    $$(Pill.BOX).forEach(Pill.sync);
  },
};

let toastTimer = 0;
function toast(msg, act) {
  const t = $('#snackbar');
  t.textContent = msg;
  if (act) {
    const b = document.createElement('button');
    b.className = 'snack-act';
    b.textContent = act.label;
    b.onclick = () => { t.classList.remove('show'); act.run(); };
    t.appendChild(b);
  }
  t.classList.toggle('acts', !!act);
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), act ? 5000 : 3200);
}

function openUrl(url) {
  if (Native) send({ t: 'openUrl', url });
  else window.open(url, '_blank', 'noopener');
}

/** Start times of tracks played back to back, with a pause at each side change (same as Planner.kt). */
function planStarts(tracks, mode, at, now) {
  const gaps = tracks.map((t, i) => (i > 0 && t.side !== tracks[i - 1].side ? FLIP_GAP_MS : 0));
  const total = tracks.reduce((s, t) => s + t.dur * 1000, 0) + gaps.reduce((a, b) => a + b, 0);
  let t = mode === 'now' ? now : mode === 'at' ? at : now - total;
  const starts = tracks.map((tr, i) => { t += gaps[i]; const s = t; t += tr.dur * 1000; return s; });
  return { starts, start: starts[0], end: t, total };
}

/** Where the spinning record is at [now]: current track index (or -1), the next one, the end. */
function spinNow(sp, now = Date.now()) {
  let cur = -1;
  for (let k = sp.items.length - 1; k >= 0; k--) {
    const it = sp.items[k];
    if (it.state !== 'skipped' && now >= it.start && now < it.start + it.dur * 1000) { cur = k; break; }
  }
  const live = sp.items.filter((it) => it.state !== 'skipped');
  const next = live.find((it) => it.start > now) || null;
  const end = Math.max(0, ...live.map((it) => it.start + it.dur * 1000));
  return { cur, item: cur >= 0 ? sp.items[cur] : null, next, live, end };
}

// ---------- native callbacks ----------

/**
 * A new theme makes Android start the screen again, page and all: Settings, where it was picked,
 * comes back with it (when that happened just now).
 */
let reopenSettings = false;
try {
  reopenSettings = Date.now() - Number(localStorage.getItem('reopenSettings')) < 10000;
  localStorage.removeItem('reopenSettings');
} catch (_) { /* preview without storage */ }
window.onState = (s) => {
  const hadSpin = ST.spin ? ST.spin.id : null;
  ST = s;
  renderState();
  askBadges();
  applyAmbient();
  vsStateTick();
  // The spin just asked for has started: from nothing, or in place of the record that was spinning.
  if (s.spin && s.spin.id !== hadSpin && pendingSpinOpen) { pendingSpinOpen = false; openPage('spinPage'); renderSpin(true); }
  if (reopenSettings) {
    reopenSettings = false;
    openSettings();
    $('#themeSeg').scrollIntoView({ block: 'center' });
  }
};
window.onCollection = (c) => {
  COLL = c.map((r) => Object.assign(r, {
    _s: norm([r.title, r.artist, r.label, r.year, (r.styles || []).join(' '), (r.genres || []).join(' ')].join(' ')),
    _artist: String(r.artist || '').replace(/^the\s+/i, ''),
  }));
  renderShelf();
  if (isOpen('recordPage')) renderRecord();
  askBadges();
};
window.onActivity = (a) => { ACT = a || []; renderActivity(); applyAmbient(); };
window.onRelease = (r) => {
  const old = REL[r.id];
  if ((r.loading || r.error) && old && old.tracks) return;
  REL[r.id] = r;
  if (isOpen('recordPage') && recordId === r.id) renderRecord();
  fillSleeveBacks();
};
window.onToast = (m) => toast(m);
window.onBackup = (b) => {
  const st = $('#bkStatus');
  st.hidden = false;
  st.textContent = b.error || `Saved, with ${num(b.scrobbles)} scrobbles. Restore it in the new app with Restore… above.`;
  if (!b.error) toast('Backup saved');
};
window.onRestored = (r) => {
  try {
    localStorage.clear();
    for (const [k, v] of Object.entries(r.ui || {})) localStorage.setItem(k, v);
  } catch (_) { /* preview without storage */ }
  toast(`Restored ${num(r.scrobbles)} scrobbles. Starting again…`);
  setTimeout(() => send({ t: 'restartApp' }), 1200);
};
window.onCompare = (c) => { CMP = c; renderPhoneCard(); if (isOpen('comparePage')) renderCompare(); };
let statsLook = '';
window.onStats = (s) => {
  STATS = s;
  $('#statsBody').classList.remove('stale');
  renderStats();
  const look = s && s.range ? [s.range.id, statsGenre, statsDecade].join('|') : '';
  if (statsLook && look && look !== statsLook) settle($('#statsBody'));
  if (look) statsLook = look;
  if (isOpen('settingsPage')) renderSettings();
};
window.onOpenSpin = () => { if (ST.spin) { openPage('spinPage'); renderSpin(true); } };
window.onOpenNow = () => { if ((ST.phone || {}).now) openNow(); };
window.onTheme = (th) => {
  const root = document.documentElement;
  root.dataset.theme = th.dark ? 'dark' : 'light';
  root.style.colorScheme = th.dark ? 'dark' : 'light';
  for (const k in th.c) root.style.setProperty('--md-' + k, th.c[k]);
  drawRecords();
};
let insetsSet = '';
window.onInsets = (i) => {
  // Like the glow's colors, these sit on the root: an unchanged report is skipped.
  const key = [i.top, i.bottom, i.ime].join('|');
  if (key === insetsSet) return;
  insetsSet = key;
  const root = document.documentElement.style;
  root.setProperty('--inset-top', `${i.top || 0}px`);
  root.setProperty('--inset-bottom', `${i.bottom || 0}px`);
  root.setProperty('--ime', `${i.ime || 0}px`);
};
window.onBack = () => {
  const fx = backFx;
  backFx = null;
  // Something opened during the swipe, and back closes that instead: the layer the finger moved goes back.
  const t = fx && backTarget();
  const mine = fx && t && t.el === fx.el;
  if (fx && !mine) backSpring(fx.el);
  // A page the finger moved slides on out in closePage, what was under it coming home at the same pace.
  if (!(mine && fx.kind === 'page' && pageStack.length && t.el === $('#' + pageStack[pageStack.length - 1]))) backSettle(fx, true);
  if (!$('#story').hidden) { closeStory(); return true; }
  if (sheetOpen()) { closeSheet(); return true; }
  if (pageStack.length) { closePage(mine && fx.kind === 'page' ? fx : null); return true; }
  if (searching()) { closeSearch(); return true; }
  if (tab !== 'shelf') { showTab('shelf'); return true; }
  return false;
};

// ---------- navigation ----------

/** Where each tab was scrolled to, so switching back finds it there. */
const tabScroll = {};
function showTab(name) {
  const other = name !== tab;
  if (other) tabScroll[tab] = window.scrollY;
  tab = name;
  $$('.tab').forEach((t) => t.classList.toggle('active', t.id === 'tab-' + name));
  $$('#tabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === name));
  $('#appbarTitle').textContent = { shelf: 'Shelf', stats: 'Stats', explore: 'Explore', activity: 'Activity' }[name];
  $('#shelfCount').hidden = name !== 'shelf';
  window.scrollTo(0, other ? tabScroll[name] || 0 : 0);
  syncBack();
  renderState();
  if (name === 'stats') requestStats();
  if (name === 'explore') requestExplore();
}
const isOpen = (id) => !$('#' + id).hidden;

/**
 * Pages that can be in the back stack more than once (a song, its album, another song of it):
 * what one shows, so Back brings it back. The others are single and move to the top.
 */
const PAGE_STATE = {
  recordPage: { get: () => recordId, set: (s) => { recordId = s; renderRecord(); } },
  artistPage: { get: () => ARTIST, set: (s) => { ARTIST = s; renderArtist(); } },
  trackPage: { get: () => TRACK, set: (s) => { TRACK = s; renderTrack(); } },
  albumPage: { get: () => ALBUM, set: (s) => { ALBUM = s; renderAlbum(); } },
  playlistPage: { get: () => [PLAYLIST, TIDALJOB, PLNAME], set: (s) => { [PLAYLIST, TIDALJOB, PLNAME] = s; renderPlaylist(); } },
  rankPage: { get: () => RANKS, set: (s) => { RANKS = s; renderRanks(); } },
  concertsPage: { get: () => CONCERTS, set: (s) => { CONCERTS = s; renderConcerts(); } },
  vsPage: { get: () => VS, set: (s) => { VS = s; renderVs(); } },
  atlasPage: { get: () => atlasView, set: (s) => { atlasView = s; renderAtlas(); } },
  genrePage: { get: () => GENRE, set: (s) => { GENRE = s; renderGenre(); } },
};
/** Per back-stack entry: what a page further down showed (state and scroll), while another of its kind is on top. */
const pageSaved = [];

/** Opens a page on top. Callers set the page's state after this, so the one below can be remembered first. */
/** Material's emphasized easing: quick out of the gate and a long settle; the accelerating one for things leaving. */
const EASE_OUT = 'cubic-bezier(.2, 0, 0, 1)';
const EASE_IN = 'cubic-bezier(.3, 0, .8, .15)';
/** How long a page takes to slide the rest of the way out from where it is (a whole screen: 320 ms). */
function slideMs(el) {
  const t = getComputedStyle(el).transform;
  const x = t === 'none' ? 0 : new DOMMatrix(t).m41;
  return Math.round(120 + 200 * Math.max(0, 1 - x / window.innerWidth));
}
/**
 * [el] leaves: sliding all the way out to the right (from wherever a back swipe left it, at the
 * pace it was going), or only fading when its cover flies home. It holds its last frame until it
 * is hidden: let go at the end, a busy phone showed the whole page again for a frame (its big
 * blurred cover too) before the page was hidden.
 */
function animateOut(el, how, done, ms = how === 'fade' ? 200 : slideMs(el)) {
  const t = getComputedStyle(el).transform;
  const from = t === 'none' ? 'none' : t;
  const a = el.animate(how === 'fade'
    ? [{ transform: from, opacity: 1 }, { transform: from, opacity: 0 }]
    : [{ transform: from }, { transform: `translateX(${window.innerWidth}px)` }],
  { duration: ms, easing: how === 'fade' ? EASE_IN : EASE_OUT, fill: 'forwards' });
  // Finished, cancelled, or never run (a page in the background): either way it ends once.
  let over = false;
  const end = () => {
    if (over) return;
    over = true;
    done();
    el.style.transform = '';
    el.style.borderRadius = '';
    el.style.transition = '';
    el.style.willChange = '';
    // Hidden now: the held last frame can go.
    if (a.playState !== 'idle') a.cancel();
  };
  a.onfinish = end;
  a.oncancel = end;
  setTimeout(end, ms + 250);
  return a;
}
function openPage(id, how) {
  const p = $('#' + id);
  tidyLayers();
  if (p._closing) {
    p._closing.cancel();
    p._closing = null;
    p.inert = false;
    p.style.transform = '';
    p.style.borderRadius = '';
    // Leaving a page it shows further down: that one's state comes back now; else it was on its way out.
    if (p._restore) p._restore(); else p.hidden = true;
  }
  if (p.hidden) {
    p.hidden = false;
    pageStack.push(id);
  } else if (PAGE_STATE[id]) {
    pageSaved[pageStack.lastIndexOf(id)] = { state: PAGE_STATE[id].get(), scroll: p.scrollTop };
    pageStack.push(id);
  } else {
    const i = pageStack.indexOf(id);
    pageStack.splice(i, 1);
    pageSaved.splice(i, 1);
    pageStack.push(id);
  }
  pageSaved.length = pageStack.length;
  p.style.zIndex = 10 + pageStack.length;
  p.scrollTop = 0;
  const bar = p.querySelector(':scope > .appbar');
  if (bar) bar.classList.remove('away', 'back');
  // In from the right; a page a cover flies into only fades in, so the cover has a still place to land.
  // Held clear for two frames while the phone draws it: shown at once, it was a blank page for those.
  p.animate(how === 'fly' ? [{ opacity: 0 }, { opacity: 1 }] : [{ opacity: 0, transform: 'translateX(32px)' }, { opacity: 1, transform: 'none' }],
    { duration: how === 'fly' ? 220 : 300, delay: 34, fill: 'backwards', easing: EASE_OUT });
  Tilt.refresh();
  syncBack();
}

/**
 * Top bars scroll away with the page, like the rest of it. Scrolling up brings them back over the
 * page ("back": held at the top, a soft fade of the page's color behind them) until the page is at
 * its top again or you scroll down, when they slide away. [onAway] hears whether the bar is out of view.
 */
function watchBar(scroller, bar, onAway) {
  const el = scroller === window ? document.scrollingElement : scroller;
  let last = 0, gone = false;
  scroller.addEventListener('scroll', () => {
    const y = el.scrollTop, d = y - last;
    // Moved by the app (another tab of a page): the bar stays as it is.
    if (el.dataset.quiet) { last = y; return; }
    if (y <= 2) {
      bar.classList.remove('back', 'away');
      last = y;
    } else if (Math.abs(d) >= 6) {
      if (d < 0 && y > bar.offsetHeight) { bar.classList.add('back'); bar.classList.remove('away'); }
      else if (d > 0 && bar.classList.contains('back')) bar.classList.add('away');
      last = y;
    }
    const nowGone = bar.classList.contains('back') ? bar.classList.contains('away') : y > bar.offsetHeight - 8;
    if (nowGone !== gone) { gone = nowGone; if (onAway) onAway(gone); }
  }, { passive: true });
  // Slid away: back into the page's flow, where it waits out of view at the top.
  bar.addEventListener('transitionend', (e) => { if (e.propertyName === 'transform' && bar.classList.contains('away')) bar.classList.remove('back', 'away'); });
}
/**
 * Closes the top page. [swipe]: the back swipe that moved it, whose page underneath comes home
 * as it slides out; closed by a tap, it is set up the same way first, so both look alike.
 */
function closePage(swipe = null) {
  const id = pageStack.pop();
  pageSaved.length = pageStack.length;
  if (!id) return;
  const p = $('#' + id);
  const i = pageStack.lastIndexOf(id);
  const saved = i >= 0 ? pageSaved[i] : null;
  const top = pageStack.length ? $('#' + pageStack[pageStack.length - 1]) : null;
  if (saved && top !== p) {
    // This page also shows something further down the stack (artist, genre, then artist again):
    // it slides off like any page, the one under it coming home, and only then takes up what it
    // showed down there, under the others.
    pageSaved[i] = undefined;
    p.inert = true;
    const ms = slideMs(p);
    backSettle(swipe || backReveal(p, [top]), true, ms);
    p._restore = () => {
      p._restore = null;
      p.inert = false;
      PAGE_STATE[id].set(saved.state);
      p.style.zIndex = 10 + i + 1;
      p.scrollTop = saved.scroll;
    };
    const a = animateOut(p, 'slide', () => { if (p._closing === a) { p._closing = null; if (p._restore) p._restore(); } }, ms);
    p._closing = a;
  } else if (saved) {
    // The same kind of page right under it, that is this very page: show what it showed, where it was.
    if (swipe) backSettle(swipe, true);
    pageSaved[i] = undefined;
    PAGE_STATE[id].set(saved.state);
    p.style.zIndex = 10 + i + 1;
    p.scrollTop = saved.scroll;
    p.style.transform = '';
    p.style.borderRadius = '';
    p.style.willChange = '';
    p.animate([{ opacity: 0.4, transform: 'translateX(-24px)' }, { opacity: 1, transform: 'none' }], { duration: 260, easing: EASE_OUT });
  } else if (i < 0) {
    // A swipe carries the record off with its page; a tap on back flies its cover home to the shelf.
    if (swipe) flyHome = null;
    const flew = id === 'recordPage' && !swipe && flyBack();
    // On its way out it takes no taps: they belong to the page coming back under it.
    p.inert = true;
    let ms;
    if (flew) backSettle(swipe, true);
    else {
      ms = slideMs(p);
      const top = pageStack.length ? $('#' + pageStack[pageStack.length - 1]) : null;
      backSettle(swipe || backReveal(p, top ? [top] : homeLayers()), true, ms);
    }
    const a = animateOut(p, flew ? 'fade' : 'slide', () => { if (p._closing === a) { p.hidden = true; p.inert = false; p._closing = null; } }, ms);
    p._closing = a;
  }
  if (id === 'settingsPage') showKeyForm = false;
  Tilt.refresh();
  syncBack();
}

/**
 * Whether back stays in the app (a layer to close, or a tab to leave for the shelf). Android
 * only previews the way out of the app with its back swipe when it doesn't.
 */
const sheetOpen = () => !$('#sheet').hidden && !$('#sheet').classList.contains('closing');
const canGoBack = () => !$('#story').hidden || sheetOpen() || pageStack.length > 0 || searching() || tab !== 'shelf';
let backSent = null;
function syncBack() {
  const v = canGoBack();
  if (v !== backSent && Native) send({ t: 'canBack', v });
  backSent = v;
  // Nothing behind a sheet, the story or search scrolls along with them.
  document.documentElement.classList.toggle('locked', sheetOpen() || !$('#story').hidden);
  document.documentElement.classList.toggle('locked-root', searching());
}
/**
 * Android 14+ back swipe, the way a stack goes back: from whichever edge it starts, the page it would
 * close slides right with the finger (Android's progress is about the share of the screen the finger
 * has crossed), and what back leads to comes in from the left under a lifting shade. Then the page
 * either leaves from there (onBack) or springs back. A sheet sinks instead.
 */
let backFx = null;
const backShade = document.createElement('div');
backShade.className = 'back-shade';
/** The tabs' screen: its top bar and the tab under it (the navbar stays put, like the system's). */
const homeLayers = () => [$('#appbar'), $('#main')];
const BACK_SHADE = 0.3, BACK_UNDER = 0.22;
function backTarget() {
  const top = pageStack.length ? $('#' + pageStack[pageStack.length - 1]) : null;
  if (!$('#story').hidden) return { el: $('#story'), kind: 'page', under: top ? [top] : homeLayers() };
  if (sheetOpen()) return { el: $('#sheetBody'), kind: 'sheet' };
  if (top) {
    const prev = pageStack.length > 1 ? $('#' + pageStack[pageStack.length - 2]) : null;
    // The same kind of page right under it is this very element, which back fills with the earlier
    // record or artist: nothing to reveal, so it only shrinks a little.
    if (prev === top) return { el: top, kind: 'inplace' };
    return { el: top, kind: 'page', under: prev ? [prev] : homeLayers() };
  }
  if (searching()) return { el: $('#searchPage'), kind: 'page', under: homeLayers() };
  return null;
}
window.onBackStart = () => {
  if (flight && flight.playState === 'running') flight.cancel();
  backFx = backTarget();
  if (!backFx) return;
  backFx.el.style.transition = 'none';
  // Layers of their own from the start, and the corners rounded once: redrawing them on every
  // frame of the swipe is what made it stutter.
  backFx.el.style.willChange = 'transform';
  if (!backFx.under) return;
  // The shade sits right under the page: same layer, just before it.
  const z = getComputedStyle(backFx.el).zIndex;
  backShade.style.zIndex = z === 'auto' ? '' : z;
  backShade.style.transition = 'none';
  backFx.el.before(backShade);
  backFx.el.style.boxShadow = '-10px 0 32px rgba(0, 0, 0, .24)';
  backFx.under.forEach((u) => { u.style.transition = 'none'; u.style.willChange = 'transform'; });
  window.onBackProgress(0);
};
window.onBackProgress = (p) => {
  if (!backFx) return;
  const el = backFx.el, k = Math.min(1, Math.max(0, p)), w = window.innerWidth;
  if (backFx.kind === 'sheet') { el.style.transform = `translateY(${(k * 80).toFixed(1)}px)`; return; }
  if (backFx.kind === 'inplace') {
    el.style.transform = `translateX(${(k * w * 0.12).toFixed(1)}px) scale(${(1 - k * 0.1).toFixed(3)})`;
    return;
  }
  el.style.transform = `translateX(${(k * w).toFixed(1)}px)`;
  backFx.under.forEach((u) => { u.style.transform = `translateX(${(-(1 - k) * w * BACK_UNDER).toFixed(1)}px)`; });
  backShade.style.opacity = ((1 - k) * BACK_SHADE).toFixed(3);
};
/** After the swipe: what was under the page settles home (it was chosen) or back under it (cancelled). */
function backSettle(fx, chosen, ms = chosen ? 260 : 300) {
  if (!fx || !fx.under) return;
  const w = window.innerWidth;
  fx.under.forEach((u) => {
    u.style.transition = `transform ${ms}ms cubic-bezier(.2, 0, 0, 1)`;
    u.style.transform = chosen ? '' : `translateX(${(-w * BACK_UNDER).toFixed(1)}px)`;
  });
  backShade.style.transition = `opacity ${ms}ms`;
  backShade.style.opacity = chosen ? '0' : String(BACK_SHADE);
  setTimeout(() => {
    if (!backFx || backFx.el !== fx.el) fx.el.style.boxShadow = '';
    if (backFx) return; // a new swipe has started and owns the rest now
    fx.under.forEach((u) => { u.style.transition = ''; u.style.transform = ''; u.style.willChange = ''; });
    backShade.remove();
    tidyLayers();
  }, ms + 30);
}
/**
 * The safety net: any page (or the tabs) a swipe or a slide moved, that nothing moves anymore,
 * goes back to its place, and a shade left behind goes. (A quick second swipe, or a way back that
 * forgot its layers, once left a page shifted to the side over another.)
 */
function tidyLayers() {
  if (backFx) return;
  for (const el of [...$$('.page'), ...homeLayers()]) {
    if (el.getAnimations().some((a) => a.playState === 'running')) continue;
    if (el.style.transform || el.style.willChange || el.style.boxShadow) {
      el.style.transition = '';
      el.style.transform = '';
      el.style.willChange = '';
      el.style.boxShadow = '';
    }
  }
  if (backShade.isConnected && !backShade.getAnimations().some((a) => a.playState === 'running')) backShade.remove();
}
/** A layer the swipe moved springs back to its place. */
function backSpring(el) {
  el.style.transition = 'transform .3s cubic-bezier(.2, 0, 0, 1), border-radius .3s';
  el.style.transform = '';
  el.style.borderRadius = '';
  setTimeout(() => { if (!el.style.transform) { el.style.transition = ''; el.style.willChange = ''; } }, 320);
}
/** Back by a tap: what the page covers waits a little to the left under the shade, as at the start of a swipe. */
function backReveal(el, under) {
  const z = getComputedStyle(el).zIndex;
  backShade.style.zIndex = z === 'auto' ? '' : z;
  backShade.style.transition = 'none';
  backShade.style.opacity = String(BACK_SHADE);
  el.before(backShade);
  el.style.boxShadow = '-10px 0 32px rgba(0, 0, 0, .24)';
  under.forEach((u) => {
    u.style.transition = 'none';
    u.style.willChange = 'transform';
    u.style.transform = `translateX(${(-window.innerWidth * BACK_UNDER).toFixed(1)}px)`;
  });
  void el.offsetWidth; // from there, not from where they were
  return { el, kind: 'page', under };
}
window.onBackCancel = () => {
  if (!backFx) return;
  const fx = backFx;
  backFx = null;
  backSpring(fx.el);
  backSettle(fx, false);
};

/**
 * A copy of cover [from] flies onto cover [to], the way a shared element moves between pages.
 * Only its transform animates, so the compositor runs it even while the new page is being built.
 * A sleeve at either end waits (hidden, its record in) and takes over at the end: its foil fades
 * in, then the record slides out. If [to] shows a sharper picture, the copy fades it in on the
 * way, and lands only once it is there.
 */
function flyCover(from, to, ms) {
  const a = from.getBoundingClientRect(), b = to.getBoundingClientRect();
  if (!a.width || !b.width) return;
  const c = from.cloneNode(true);
  c.classList.add('fly');
  c.removeAttribute('style');
  const r = parseFloat(getComputedStyle(to).borderTopLeftRadius) || 12;
  c.style.cssText = `left:${b.left}px;top:${b.top}px;width:${b.width}px;height:${b.height}px;border-radius:${r}px;--h:${from.style.getPropertyValue('--h')}`;
  const toImg = to.querySelector('img'), fromImg = from.querySelector('img');
  if (toImg && toImg.src && (!fromImg || fromImg.src !== toImg.src)) {
    const hi = new Image();
    hi.className = 'hi';
    hi.alt = '';
    hi.onload = () => hi.classList.add('ok');
    hi.src = toImg.src;
    c.append(hi);
  }
  document.body.append(c);
  const holos = [from, to].map((el) => el.closest('.holo')).filter(Boolean);
  holos.forEach((h) => { h.classList.add('landing'); h.classList.remove('landed'); });
  if (!from.closest('.holo')) from.style.visibility = 'hidden';
  if (!to.closest('.holo')) to.style.visibility = 'hidden';
  Tilt.hold = true;
  const anim = c.animate([
    { transform: `translate(${a.left - b.left}px, ${a.top - b.top}px) scale(${a.width / b.width})` },
    { transform: 'none' },
  ], { duration: ms, easing: EASE_OUT });
  let over = false;
  const end = () => {
    if (over) return;
    over = true;
    Tilt.hold = false;
    from.style.visibility = '';
    to.style.visibility = '';
    holos.forEach((h) => {
      h.classList.remove('landing');
      h.classList.add('landed');
      setTimeout(() => h.classList.remove('landed'), 600);
    });
    // The copy steps aside a moment later, so the picture under it is drawn by then.
    c.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 140, delay: 40, fill: 'forwards' }).onfinish = () => c.remove();
    setTimeout(() => c.remove(), 400);
  };
  // Lands when the flight is over and [to]'s picture is ready (or after a short wait for it).
  const ready = () => !toImg || (toImg.complete && toImg.naturalWidth > 0);
  const land = () => {
    if (ready()) { end(); return; }
    toImg.addEventListener('load', end, { once: true });
    toImg.addEventListener('error', end, { once: true });
    setTimeout(end, 500);
  };
  anim.onfinish = land;
  anim.oncancel = end;
  flight = anim;
  setTimeout(end, ms + 900);
  // Scrolling meanwhile moves where it would land: then it lands at once.
  const scroller = to.closest('.page') || window;
  scroller.addEventListener('scroll', () => anim.cancel(), { once: true, passive: true });
}
/** The cover flight under way, if any: a back swipe lands it at once, so no copy floats over the moving page. */
let flight = null;
/** Where the record page's cover came from, so Back can fly it home. */
let flyHome = null;
const heroCover = () => $('#recHolo .sleeve .cover') || $('#recHolo .cover');
function flyBack() {
  const h = flyHome;
  flyHome = null;
  if (!h || h.id !== recordId) return false;
  const home = h.el.isConnected ? h.el : document.querySelector(`.rec[data-id="${h.id}"] .cover`);
  const hero = heroCover();
  if (!home || !hero) return false;
  const r = home.getBoundingClientRect();
  if (!r.width || r.bottom < 0 || r.top > innerHeight) return false;
  flyCover(hero, home, 380);
  return true;
}
/**
 * An answer for a page that may have been covered meanwhile: [apply] it to whichever state asked ([req]).
 * The ids start from the clock, so an answer still on its way to the page before a reload can't match
 * a new request; under 10^9, as the app reads them as Ints.
 */
let REQ = Date.now() % 1e9;
function deliver(pageId, req, current, apply) {
  if (current && current.req === req) return apply(current, true);
  pageStack.forEach((pid, i) => {
    const s = pageSaved[i];
    if (pid === pageId && s && s.state && s.state.req === req) s.state = apply(s.state, false);
  });
  return current;
}
function openSettings(section) {
  const showing = isOpen('settingsPage');
  openPage('settingsPage');
  renderSettings();
  // Accounts fold away once they're set up; one that needs something, or was asked for, opens.
  const l = ST.lastfm || {}, d = ST.discogs || {}, p = ST.phone || {};
  const done = { lfSection: !!l.connected && !l.needsAuth, dgSection: !!d.connected, tdSection: true, slSection: true, phSection: (p.mode || 'off') === 'off' || !!p.access };
  let moved = false;
  $$('#settingsPage details.acct').forEach((el) => {
    const open = el.id === section || !done[el.id];
    if (open !== el.open) moved = true;
    el.open = open;
  });
  if (!section) return;
  // On a page that was showing, folds slide open and shut (look.css) and move the section along: scroll once
  // they're done. That transition is on ::details-content, which getAnimations and transitionend don't see.
  const fold = $('#settingsPage details.acct');
  const ms = showing && moved ? Math.max(...getComputedStyle(fold, '::details-content').transitionDuration.split(',').map((x) => parseFloat(x) * 1000 || 0)) : 0;
  setTimeout(() => { const s = $('#' + section); if (s) $('#settingsPage').scrollTop = s.offsetTop - 72; }, ms + 30);
}

let sheetGen = 0;
function openSheet(html) {
  sheetGen++;
  const sh = $('#sheet'), body = $('#sheetBody');
  $('#sheetContent').innerHTML = html;
  if (sh.hidden || sh.classList.contains('closing')) {
    sh.classList.remove('closing');
    [body, $('#scrim')].forEach((el) => el.getAnimations().forEach((a) => a.cancel()));
    body.style.transform = '';
    $('#scrim').style.opacity = '';
    sh.hidden = false;
    body.animate([{ transform: 'translateY(100%)' }, { transform: 'none' }], { duration: 340, easing: EASE_OUT });
    $('#scrim').animate([{ opacity: 0 }, { opacity: 1 }], { duration: 260, easing: 'ease-out' });
  }
  // A short sheet drags down from anywhere; a long one scrolls, and drags by its handle.
  body.classList.toggle('fits', body.scrollHeight <= body.clientHeight + 1);
  syncBack();
}
function closeSheet() {
  const sh = $('#sheet');
  if (sh.hidden || sh.classList.contains('closing')) return;
  const gen = ++sheetGen;
  sh.classList.add('closing');
  const body = $('#sheetBody');
  const t = getComputedStyle(body).transform;
  body.animate([{ transform: t === 'none' ? 'none' : t }, { transform: 'translateY(100%)' }], { duration: 220, easing: EASE_IN, fill: 'forwards' });
  $('#scrim').animate([{ opacity: getComputedStyle($('#scrim')).opacity }, { opacity: 0 }], { duration: 220, easing: 'ease-in', fill: 'forwards' });
  setTimeout(() => {
    if (gen !== sheetGen) return;
    sh.hidden = true;
    sh.classList.remove('closing');
    body.getAnimations().forEach((a) => a.cancel());
    $('#scrim').getAnimations().forEach((a) => a.cancel());
    body.style.transform = '';
    $('#scrim').style.opacity = '';
    $('#sheetContent').innerHTML = '';
  }, 230);
  syncBack();
}
/** Dragging a sheet down: it follows the finger, and closes past a third of its height or on a flick. */
function sheetDrag() {
  const body = $('#sheetBody'), scrim = $('#scrim');
  let y0 = 0, t0 = 0, dy = 0, id = null, dragging = false, dragged = false;
  body.addEventListener('pointerdown', (e) => {
    if (e.target.closest('.ar-grip, .ar-sw')) return;
    if (!e.target.closest('.sheet-grab') && !(body.classList.contains('fits') && body.scrollTop <= 0)) return;
    id = e.pointerId; y0 = e.clientY; t0 = e.timeStamp; dy = 0; dragging = false;
  });
  body.addEventListener('pointermove', (e) => {
    if (e.pointerId !== id) return;
    dy = Math.max(0, e.clientY - y0);
    if (!dragging && dy > 8) { dragging = true; body.setPointerCapture(id); body.getAnimations().forEach((a) => a.cancel()); }
    if (!dragging) return;
    body.style.transform = `translateY(${dy}px)`;
    scrim.style.opacity = String(Math.max(0, 1 - dy / body.offsetHeight));
  });
  const end = (e) => {
    if (e.pointerId !== id) return;
    id = null;
    if (!dragging) return;
    dragging = false;
    dragged = true;
    setTimeout(() => { dragged = false; }, 0);
    const fast = dy / Math.max(1, e.timeStamp - t0) > 0.6;
    if (dy > body.offsetHeight / 3 || fast) { closeSheet(); return; }
    body.animate([{ transform: `translateY(${dy}px)` }, { transform: 'none' }], { duration: 240, easing: EASE_OUT });
    body.style.transform = '';
    scrim.style.opacity = '';
  };
  body.addEventListener('pointerup', end);
  body.addEventListener('pointercancel', end);
  // A tap that ended a drag isn't a tap on whatever is under the finger.
  body.addEventListener('click', (e) => { if (dragged) e.stopPropagation(); }, true);
}
function confirmSheet(title, text, yes, onYes) {
  openSheet(`<div class="sheet-title">${esc(title)}</div><p class="body">${esc(text)}</p>
    <div class="sheet-actions"><button class="btn text" id="cfNo">Cancel</button><button class="btn filled" id="cfYes">${esc(yes)}</button></div>`);
  $('#cfNo').onclick = closeSheet;
  $('#cfYes').onclick = () => { closeSheet(); onYes(); };
}

// ---------- state-driven parts ----------

function renderState() {
  const d = ST.discogs || {};
  renderSetup();
  renderSpinCard();
  const syncBtn = $('#syncBtn');
  syncBtn.hidden = !d.connected || tab !== 'shelf';
  syncBtn.classList.toggle('busy', !!d.syncing);
  const bar = $('#syncBar');
  bar.hidden = !d.syncing || tab !== 'shelf';
  bar.classList.toggle('indet', !d.pages);
  bar.style.setProperty('--p', d.pages ? `${Math.round((d.page / d.pages) * 100)}%` : '30%');
  const waiting = (ST.queue || {}).waiting || 0;
  $('#actBadge').hidden = !waiting;
  $('#actBadge').textContent = waiting > 99 ? '99+' : waiting;
  if (!COLL.length || !$('#shelfEmpty').hidden) renderShelfEmpty();
  renderQueueCard();
  renderPhoneCard();
  renderStatsHead();
  if (isOpen('nowPage')) renderNow();
  if (isOpen('settingsPage')) renderSettings();
  if (isOpen('playlistPage') && $('#plTidalBlock') && !(TIDALJOB && TIDALJOB.state === 'finding')) $('#plTidalBlock').innerHTML = tidalBlock();
  if (isOpen('comparePage')) renderCompare();
  if (isOpen('spinPage')) renderSpin();
  if (isOpen('recordPage')) renderRecordSpinNote();
}

function renderSetup() {
  const d = ST.discogs || {}, l = ST.lastfm || {};
  let h = '';
  if (!d.connected) {
    h += `<div class="card tonal">
      <div class="card-title">${icon('album')}Put your records on the shelf</div>
      <p>Connect your Discogs collection to see it here as a cover wall. Then pick a record and scrobble what you play.</p>
      <div class="btn-row"><button class="btn filled" data-go="dgSection">Connect Discogs</button>${l.connected ? '' : '<button class="btn tonal" data-go="lfSection">Connect Last.fm</button>'}</div>
    </div>`;
  } else if (!l.connected) {
    h += `<div class="card warn">
      <div class="card-title">${icon('link')}${l.needsAuth ? 'Reconnect Last.fm' : 'Connect Last.fm'}</div>
      <p>${l.needsAuth ? 'Last.fm asks you to allow access again. Your scrobbles wait on this phone until then.' : 'Needed to scrobble your records. It takes a minute with a free Last.fm API account.'}</p>
      <div class="btn-row"><button class="btn tonal" data-go="lfSection">${l.needsAuth ? 'Reconnect' : 'Set up Last.fm'}</button></div>
    </div>`;
  }
  if (d.connected && d.error) h += `<div class="card bad"><div class="card-title">${icon('warning')}Couldn't sync</div><p>${esc(d.error)}</p></div>`;
  $('#setupCards').innerHTML = h;
}

let spinCardSig = '';
/** Settings → Look → Now playing: 'line' (a line at the top of the shelf) or 'corner' (a record peeking out above the search). */
function nowStyle() {
  try { return localStorage.getItem('nowStyle') === 'corner' ? 'corner' : 'line'; } catch (_) { return 'line'; }
}
/** The sleeve with the record half out of it, the cover on both (the label of the record too). */
function npArt(cover, title, artist) {
  return `<span class="np-art"><span class="np-disc" style="--h:${hue((title || '') + (artist || ''))}"><i>${cover ? `<img src="${esc(cover)}" alt="" loading="lazy">` : ''}</i></span>${coverHtml(cover, title, artist, 'np-sleeve')}</span>`;
}
/**
 * What plays now, the record spinning or else the phone's music app: as a line on the shelf, or
 * as a record peeking out above the search circle. [it] has cover, title, artist, label and the
 * ids its line keeps up to date (see tickUi and tickPhone); [attrs] make a tap open its page.
 */
function showNow(it, sig) {
  const slot = $('#spinSlot'), peek = $('#npPeek');
  const corner = nowStyle() === 'corner';
  if (!it) { spinCardSig = ''; slot.innerHTML = ''; peek.hidden = true; peek.innerHTML = ''; return; }
  sig += corner ? '|c' : '|l';
  if (sig === spinCardSig) return;
  spinCardSig = sig;
  const art = npArt(it.cover, it.album || it.title, it.artist);
  if (corner) {
    slot.innerHTML = '';
    peek.outerHTML = `<button class="np-peek${it.playing ? ' playing' : ''}" id="npPeek" ${it.attrs} aria-label="${esc(`${it.title} by ${it.artist}`)}">${art}<span class="np-zz" hidden>${icon('moon', 'i small')}<b></b></span></button>`;
    $('#npPeek').onclick = () => { if (ST.spin) { openPage('spinPage'); renderSpin(true); } };
  } else {
    peek.hidden = true;
    peek.innerHTML = '';
    slot.innerHTML = `<button class="np-line${it.playing ? ' playing' : ''}" ${it.attrs}>${art}
      <span class="np-txt"><small ${it.labelId ? `id="${it.labelId}"` : ''}>${esc(it.label || '')}</small><b ${it.titleId ? `id="${it.titleId}"` : ''}>${esc(it.title || '')}</b>
      <span>${esc(it.artist)}${it.album ? `, ${esc(it.album)}` : ''}</span><i class="np-bar"><i id="${it.barId}"></i></i></span><span class="np-zz" hidden>${icon('moon', 'i small')}<b></b></span></button>`;
  }
  sleepMarks();
}
/** The sleep timer on what plays, in the corner or on the shelf's line: a moon and the minutes left. */
function sleepMarks() {
  const s = ST.sleep;
  const min = s && !s.song ? Math.max(1, Math.ceil((s.at - Date.now()) / 60000)) : 0;
  for (const z of $$('.np-zz')) {
    const corner = !!z.closest('.np-peek');
    const t = !s ? '' : s.song ? (corner ? '' : 'after this song') : corner ? `${min}′` : `${min} min`;
    z.hidden = !s;
    if (z.lastElementChild.textContent !== t) z.lastElementChild.textContent = t;
    z.title = s ? sleepLabel() : '';
  }
}
function renderSpinCard() {
  const sp = ST.spin;
  if (!sp) { renderPhoneNow(); return; }
  showNow({ cover: sp.cover, title: sp.album, album: sp.album, artist: sp.artist, playing: true, attrs: 'id="spinCard"', labelId: 'scLabel', titleId: 'scTitle', barId: 'scBar' }, sp.id + '|' + sp.cover);
  tickUi();
}

/**
 * With no record spinning, the same card shows what a music app on the phone plays (TIDAL and
 * the like), with its cover; a tap opens the song's page.
 */
function renderPhoneNow() {
  const n = (ST.phone || {}).now;
  if (!n || !n.title) { showNow(null); return; }
  showNow({ cover: n.cover, title: n.title, album: n.album, artist: n.artist, playing: n.playing, label: `${n.playing ? 'Now playing' : 'Paused'} in ${n.app}`,
    attrs: 'data-nowpage', barId: 'phNowBar' }, ['p', n.pkg, n.artist, n.title, n.cover || '', n.playing].join('|'));
  tickPhone();
}

let lastCur = -2;
/** Once a second while a record spins: progress, times and which track is current. */
function tickUi() {
  syncAwake();
  tickPhone();
  tickNow();
  sleepMarks();
  const sp = ST.spin;
  if (!sp) return;
  const now = Date.now();
  const n = spinNow(sp, now);
  const it = n.item;
  const pct = it ? Math.min(100, ((now - it.start) / (it.dur * 1000)) * 100) : 0;
  const left = it ? (it.start + it.dur * 1000 - now) / 1000 : 0;
  if ($('#scTitle')) {
    $('#scTitle').textContent = it ? it.title : n.next ? n.next.title : sp.album;
    $('#scLabel').textContent = it ? `Now spinning${it.side ? ` ${sideLabel(it.side)}` : ''}, ${fmtDur(left)} left` : n.next ? `Up next at ${fmtTime(n.next.start)}` : 'Now spinning';
    $('#scBar').style.width = pct + '%';
  }
  if (isOpen('spinPage') && $('#spTitle')) {
    tickTurntable(n, now);
    $('#spTitle').textContent = it ? it.title : n.next ? n.next.title : sp.album;
    $('#spSide').textContent = it ? [it.side ? sideLabel(it.side) : '', `${n.live.indexOf(it) + 1} of ${n.live.length}`].filter(Boolean).join(', ') : n.next ? `Starts at ${fmtTime(n.next.start)}` : '';
    $('#spBar').style.width = pct + '%';
    $('#spElapsed').textContent = it ? fmtDur((now - it.start) / 1000) : '';
    $('#spLeft').textContent = it ? '−' + fmtDur(left) : '';
    $('#spEnd').textContent = n.end > now ? `ends about ${fmtTime(n.end)}` : '';
    if (n.cur !== lastCur) renderSpinList();
  }
}

// ---------- shelf ----------

function sorted() {
  const s = SORTS.find((x) => x.id === shelf.sort) || SORTS[0];
  const list = COLL.filter((r) => shelfFilterOk(r));
  list.sort(s.cmp);
  if (shelf.rev) list.reverse();
  return list;
}

/** A quick settle (fade and rise) for content that was just swapped for something else. */
function settle(el) {
  if (el && el.offsetParent) el.animate([{ opacity: 0, transform: 'translateY(8px)' }, { opacity: 1, transform: 'none' }], { duration: 260, easing: EASE_OUT });
}
let shelfLook = '';
/*
 * Badges: one small drawn mark per record on the shelf (worked out from the whole history), and
 * spelled out on the record's page. [name, the icon's strokes, what it says on the page].
 */
let BADGES = {};
let badgesAsked = '';
const BADGE = {
  // A record turning, an arrow round it.
  heavy: ['Heavy rotation', '<circle cx="12" cy="12" r="3.2"/><path d="M19.4 9.2a8 8 0 1 0 .4 4.6"/><path d="M20.6 5.6l-1.1 3.7-3.7-1"/>', (b) => `${plural(b.n, 'play')} in two weeks`],
  // A record, just in: a spark at its edge.
  new: ['New arrival', '<circle cx="10.5" cy="13.5" r="7"/><circle cx="10.5" cy="13.5" r="2"/><path d="M19 3v4.4M16.8 5.2h4.4"/>', (b) => `added ${daysAgo(b.at)}`],
  // A record gone faint, a clock's hands in it: time since.
  lost: ['Lost favorite', '<circle cx="12" cy="12" r="8.6" stroke-dasharray="2.6 2.4"/><path d="M12 7.6V12l3 2"/>', (b) => `not played since ${monthYear(b.at)}`],
  // The grooves of the records you play most.
  worn: ['Worn in', '<circle cx="12" cy="12" r="8.6"/><circle cx="12" cy="12" r="5.6"/><circle cx="12" cy="12" r="2.2" fill="currentColor"/>', (b) => (b.rank === 1 ? 'your most played record' : `your #${b.rank} most played record`)],
  // A sleeve still in its shrink-wrap: the wrap's shine across a corner.
  sealed: ['Still sealed', '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M13.5 4.6l5.9 5.9M10.2 4.6l9.2 9.2"/><circle cx="8.5" cy="15.5" r="1.6"/>', (b) => (b.at ? `on your shelf since ${monthYear(b.at)}, never played` : 'never played')],
};
const daysAgo = (ms) => { const d = Math.round((Date.now() - ms) / 86400000); return d <= 0 ? 'today' : d === 1 ? 'yesterday' : `${d} days ago`; };
const badgeMark = (k) => `<i class="rbadge b-${k}"><svg viewBox="0 0 24 24">${BADGE[k][1]}</svg></i>`;
function badgeChip(id) {
  const b = BADGES[id];
  return b && BADGE[b.k] ? badgeMark(b.k).replace('<i ', `<i title="${BADGE[b.k][0]}" `) : '';
}
function badgeLine(id) {
  const b = id && BADGES[id];
  if (!b || !BADGE[b.k]) return '';
  const [name, , say] = BADGE[b.k];
  return `<div class="rbadge-line"><span class="rbadge-pill b-${b.k}">${badgeMark(b.k)}<b>${name}</b>: ${esc(say(b))}</span></div>`;
}
/** Asks again when the history or the shelf changed (and on a new day: two weeks move on). */
function askBadges() {
  const hs = ST.history || {};
  const key = `${hs.count || 0}|${COLL.length}|${new Date().toDateString()}`;
  if (!hs.done || !COLL.length || key === badgesAsked) return;
  badgesAsked = key;
  send({ t: 'badges' });
}
window.onBadges = (b) => {
  BADGES = b || {};
  // In place: the shelf's covers stay as they are.
  $$('#grid .rec').forEach((r) => {
    const old = r.querySelector('.rbadge');
    const html = badgeChip(+r.dataset.id);
    if (old && old.outerHTML === html) return;
    if (old) old.remove();
    if (html) r.insertAdjacentHTML('beforeend', html);
  });
  if (isOpen('recordPage')) renderRecord();
  if (isOpen('albumPage')) renderAlbum();
};

function renderShelf() {
  const srt = SORTS.find((x) => x.id === shelf.sort) || SORTS[0];
  $('#sortBtn').innerHTML = `${esc(srt.labels[shelf.rev ? 1 : 0])}${icon('drop')}`;
  $('#filterBtn').classList.toggle('on', shelfFiltered());
  const list = sorted();
  shownCount = list.length;
  const spinId = ST.spin && ST.spin.releaseId;
  $('#shelfTools').hidden = !COLL.length;
  $('#shelfCount').textContent = !COLL.length ? '' : shelfFiltered() ? `${list.length} of ${plural(COLL.length, 'record')}` : plural(COLL.length, 'record');
  renderFilterChips();
  $('#shelfSummary').innerHTML = shelfSummaryHtml(list);
  $('#shelfViews').innerHTML = SHELF_VIEWS.map(([v, ic, label]) => `<button class="icon-btn small${shelf.view === v ? ' on' : ''}" data-view="${v}" aria-label="${label}">${icon(ic)}</button>`).join('');
  $('#grid').className = `grid ${shelf.view}`;
  const look = [shelf.view, shelf.sort, shelf.rev, shelf.decade, shelf.genre].join('|');
  // A very long shelf just changes: fading thousands of covers at once would stutter.
  if (shelfLook && look !== shelfLook && list.length <= 300) requestAnimationFrame(() => settle($('#grid')));
  shelfLook = look;
  $('#grid').innerHTML = list.map((r) => {
    const cover = coverHtml(shelf.view === 'list' ? r.thumb || r.cover : r.cover || r.thumb, r.title, r.artist, '', r.id === spinId);
    if (shelf.view === 'list') {
      const meta = [r.year || '', (r.label || '').split(/ · |, /)[0], (r.format || '').split(',')[0]].filter(Boolean).join(', ');
      const styles = (r.styles || []).slice(0, 2).map((x) => `<span class="mini-chip">${esc(x)}</span>`).join('');
      return `<button class="rec" data-id="${r.id}">${cover}${badgeChip(r.id)}<span class="lmain"><b>${esc(r.title)}</b><small>${esc(r.artist)}</small><small class="meta">${esc(meta)}</small>${styles ? `<span class="lchips">${styles}</span>` : ''}</span>
        <span class="lplays"><b>${r.plays ? compact(r.plays) : '–'}</b><small>${r.plays === 1 ? 'play' : 'plays'}</small></span></button>`;
    }
    let meta = '';
    if (shelf.sort === 'plays' || shelf.view === 'big') meta = `<small class="meta">${[shelf.view === 'big' && r.year ? r.year : '', r.plays ? plural(r.plays, 'play') : 'no plays yet'].filter(Boolean).join(', ')}</small>`;
    else if (shelf.sort === 'year' && r.year) meta = `<small class="meta">${r.year}</small>`;
    return `<button class="rec" data-id="${r.id}">${cover}${badgeChip(r.id)}<b>${esc(r.title)}</b><small>${esc(r.artist)}</small>${meta}</button>`;
  }).join('');
  renderShelfEmpty();
  // Discogs asks for this under its data, linked to where it lives on discogs.com.
  const du = (ST.discogs || {}).user;
  $('#shelfCredit').hidden = !COLL.length;
  $('#shelfCredit button').dataset.url = du ? `https://www.discogs.com/user/${encodeURIComponent(du)}/collection` : 'https://www.discogs.com/';
}

let shownCount = 0;
function renderShelfEmpty() {
  const d = ST.discogs || {};
  const e = $('#shelfEmpty');
  let h = '';
  if (!COLL.length && d.connected && d.syncing) h = `${icon('album', 'i big')}<p>Loading your collection…${d.done ? `<br>${plural(d.done, 'record')} so far` : ''}</p>`;
  else if (!COLL.length && d.connected) h = `${icon('album', 'i big')}<p>No records yet.<br>Add some to your Discogs collection, then sync.</p><button class="btn tonal" id="emptySync">${icon('sync')}Sync now</button>`;
  e.innerHTML = h;
  e.hidden = !h;
}

// ---------- Now playing on the phone: the song, the app's queue or the album, the controls ----------

/** The album of what plays, from Deezer: asked once per album ([q] is "artist|album"). */
let NOWALB = { q: '', data: null };
let nowSig = '';
function openNow() {
  openPage('nowPage');
  nowSig = '';
  renderNow();
}
window.onNowAlbum = (a) => {
  if (a.q !== NOWALB.q) return;
  NOWALB.data = a;
  if (isOpen('nowPage')) renderNow();
};
/** Where the song is now: the app's own position, carried forward while it plays; else the listened time. */
function nowPos(n) {
  const m = n.media || {};
  const dur = m.dur || n.dur || 0;
  const p = m.pos != null && m.pos >= 0
    ? m.pos + (m.playing ? (Date.now() - m.posAt) * (m.speed || 1) : 0)
    : n.listened + (n.playing ? Date.now() - n.at : 0);
  return Math.max(0, dur ? Math.min(dur, p) : p);
}
/** Which of the album's tracks is the song (by its title, "(Remastered)" and the like aside); -1 if none. */
function nowAlbumIndex(tracks, title) {
  const k = looseKey(title);
  return tracks.findIndex((t) => looseKey(t.title) === k);
}
function renderNow() {
  const n = (ST.phone || {}).now, body = $('#nowBody');
  if (!n || !n.title) {
    nowSig = '';
    body.innerHTML = `<div class="empty">${icon('music', 'i big')}<p>Nothing plays on the phone right now.</p></div>`;
    return;
  }
  const m = n.media || {};
  const q = m.queue || [];
  const playing = m.playing ?? n.playing;
  const aq = n.album ? `${n.artist}|${n.album}` : '';
  if (aq !== NOWALB.q) {
    NOWALB = { q: aq, data: null };
    if (aq) send({ t: 'nowAlbum', artist: n.artist, album: n.album, q: aq });
  }
  const al = NOWALB.data;
  const sig = [n.pkg, n.artist, n.title, n.album, n.cover, playing, m.active, q.map((x) => x.id).join(','), m.queueTitle, m.canNext, m.canPrev, m.canSeek, m.canQueue, al ? 1 : 0].join('|');
  if (sig === nowSig) { tickNow(); return; }
  nowSig = sig;
  const dur = m.dur || n.dur || 0;
  const ctl = (act, ic, label, ok, cls = '') => `<button class="icon-btn np-ctl ${cls}" data-np="${act}" aria-label="${label}"${ok ? '' : ' disabled'}>${icon(ic)}</button>`;
  const hasMedia = n.media != null;
  let h = `<div class="hero now-hero"><div id="nowHolo">${holoCover(n.cover || '', n.album || n.title, n.artist, { key: `now|${n.artist}|${n.album || n.title}` })}</div>
    <button class="np-app" data-npapp aria-label="Open ${esc(n.app)}">${icon('phone', 'i small')}${playing ? 'Playing' : 'Paused'} in ${esc(n.app)}${icon('open', 'i small')}</button>
    <h1>${esc(n.title)}</h1>
    <div class="artist"><button class="artist-link" data-artist="${esc(n.artist)}">${esc(n.artist)}</button>${n.album ? `<span class="dot">,</span><button class="artist-link" ${albumAttrs(n.artist, n.album)}>${esc(n.album)}</button>` : ''}</div>
    <div class="np-progress"><input type="range" id="npSeek" min="0" max="${dur}" step="1000" value="0"${m.canSeek ? '' : ' disabled'} aria-label="Position in the song">
      <div class="times"><span id="npEl"></span><span id="npLeft"></span></div></div>
    ${hasMedia ? `<div class="np-ctls">${ctl('prev', 'skip-prev', 'Previous', m.canPrev)}${ctl(playing ? 'pause' : 'play', playing ? 'pause' : 'play', playing ? 'Pause' : 'Play', m.canPlay !== false, 'big')}${ctl('next', 'skip-next', 'Next', m.canNext)}</div>` : ''}
    ${hasMedia && m.canPlay !== false ? `<button class="chip np-sleep${ST.sleep ? ' on' : ''}" id="npSleep" data-sleep>${icon('moon', 'i small')}<span>${esc(sleepLabel())}</span></button>` : ''}
  </div>`;
  // The words, in time with the song when they come with times.
  const lq = `${n.artist}|${n.title}`;
  if (lq !== LYR.q) {
    LYR = { q: lq, data: null, lines: null };
    send({ t: 'lyrics', artist: n.artist, title: n.title, album: n.album || '', dur: Math.round(dur / 1000), q: lq });
  }
  h += `<div class="viz np-lyrics" id="npLyrics">${lyricsHtml(m.canSeek)}</div>`;
  // What the app will play next, if it tells (a playlist, a mix, the album, the radio).
  if (q.length) {
    const at = q.findIndex((x) => x.id === m.active);
    h += `<div class="section-head"><h2>Up next</h2><span class="sub">${esc(m.queueTitle || n.app)}${m.queueSize > q.length ? `, ${plural(m.queueSize, 'song')}` : ''}</span></div>
      <div class="list np-queue">${q.map((x, i) => `<button class="track${i === at ? ' now' : at >= 0 && i < at ? ' done' : ''}"${m.canQueue ? ` data-npq="${x.id}"` : ''}>
        <span class="pos">${i === at ? '<span class="eq plain"><i></i><i></i><i></i></span>' : at >= 0 ? (i < at ? '' : i - at) : i + 1}</span>
        <span class="grow"><b>${esc(x.title)}</b><small>${esc(x.artist)}</small></span></button>`).join('')}</div>`;
  }
  // The whole album, with the song in it and how long it has left, as on a record.
  if (n.album) {
    if (!al) h += `<div class="section-head"><h2>${esc(n.album)}</h2><span class="sub">looking up the album…</span></div>`;
    else if (al.tracks && al.tracks.length) {
      const i = nowAlbumIndex(al.tracks, n.title);
      h += `<div class="section-head"><h2>${esc(al.title || n.album)}</h2><span class="sub" id="npAlbumLeft"></span></div>
        <div class="list">${al.tracks.map((t, k) => `<button class="track${k === i ? ' now' : i >= 0 && k < i ? ' done' : ''}" ${trackAttrs(n.artist, t.title, { album: n.album, cover: n.cover })}>
          <span class="pos">${k + 1}</span><span class="grow"><b>${esc(t.title)}</b></span><span class="dur">${t.sec ? fmtDur(t.sec) : ''}</span></button>`).join('')}</div>`;
    } else h += `<p class="hint">Deezer doesn't know the album "${esc(n.album)}".</p>`;
  }
  if (!hasMedia) h += `<p class="hint">${esc(n.app)} doesn't share its controls or queue with other apps.</p>`;
  else if (!q.length) h += `<p class="hint">${esc(n.app)} doesn't share what it plays next, so here is the album.</p>`;
  keepHolo(body, h);
  tint(body, n.cover || '', (n.album || n.title) + n.artist);
  lyrAt = -2;
  tickNow();
  Tilt.refresh();
}

/*
 * Lyrics from LRCLIB for what plays. With times ("[01:23.45] a line"), the line being sung lights
 * up and the lyrics scroll along by themselves (a timer set for the next line, not a loop), unless
 * you scrolled them a moment ago; tapping a line jumps the song there.
 */
let LYR = { q: '', data: null, lines: null };
let lyrAt = -2, lyrTimer = 0, lyrUserUntil = 0;
/** Lines are lit this much early: the eye is a little behind the ear. */
const LYR_LEAD = 250;
function parseLrc(s) {
  const out = [];
  for (const line of String(s || '').split('\n')) {
    const tags = [...line.matchAll(/\[(\d+):(\d+(?:\.\d+)?)\]/g)];
    if (!tags.length) continue;
    const text = line.replace(/\[[^\]]*\]/g, '').trim();
    for (const t of tags) out.push({ t: Math.round((+t[1] * 60 + +t[2]) * 1000), text });
  }
  return out.sort((a, b) => a.t - b.t);
}
/** Whether the screen stays on while the lyrics of a playing song are open (the switch on the lyrics; on unless turned off). */
const lyricsAwake = () => { try { return localStorage.getItem('lyricsAwake') !== 'off'; } catch (_) { return true; } };
const hasLyrics = () => { const d = LYR.data; return !!d && !d.missing && !d.error && !d.instrumental && !!((LYR.lines && LYR.lines.length) || d.plain); };
let awake = false;
/** Asks Android to keep the screen on, or to let it go, when that changes. Once a second (tickUi) and on every change. */
function syncAwake() {
  const n = (ST.phone || {}).now;
  const m = (n && n.media) || {};
  const want = !!n && lyricsAwake() && isOpen('nowPage') && hasLyrics() && !!(m.playing ?? n.playing);
  if (want === awake) return;
  awake = want;
  send({ t: 'keepAwake', on: want });
}
const lyricsHead = () => `<div class="lyr-head"><h2>Lyrics</h2><button class="chip lyr-awake${lyricsAwake() ? ' on' : ''}" data-lyrawake aria-pressed="${lyricsAwake()}">${icon('sun', 'i small')}Keep the screen on</button></div>`;
function lyricsHtml(canSeek) {
  const d = LYR.data;
  const credit = '<button class="credit inline-link" data-url="https://lrclib.net">Lyrics from LRCLIB</button>';
  if (!d) return '<h2>Lyrics</h2><p class="hint">Looking for the lyrics…</p>';
  if (d.error) return '<h2>Lyrics</h2><p class="hint">Couldn\'t reach LRCLIB for the lyrics just now.</p>';
  if (d.missing) return `<h2>Lyrics</h2><p class="hint">No lyrics found for this song.</p>${credit}`;
  if (d.instrumental) return `<h2>Lyrics</h2><p class="hint">An instrumental: nothing to sing along to.</p>${credit}`;
  if (LYR.lines && LYR.lines.length) {
    return `${lyricsHead()}<span class="sub">In time with the song${canSeek ? '; tap a line to jump there' : ''}</span>
      <div class="lyr-lines synced">${LYR.lines.map((l) => `<p data-lt="${l.t}">${l.text ? esc(l.text) : '<span class="lyr-gap">♪</span>'}</p>`).join('')}</div>${credit}`;
  }
  return `${lyricsHead()}<div class="lyr-lines">${esc(d.plain).split('\n').map((l) => `<p>${l || '&nbsp;'}</p>`).join('')}</div>${credit}`;
}
window.onLyrics = (d) => {
  if (d.q !== LYR.q) return;
  LYR.data = d;
  LYR.lines = d.synced ? parseLrc(d.synced) : null;
  const box = $('#npLyrics'), n = (ST.phone || {}).now;
  if (!box || !n) return;
  box.innerHTML = lyricsHtml((n.media || {}).canSeek);
  lyrAt = -2;
  syncLyrics();
};
/** Lights the line being sung, and sets a timer for the next one while the song plays. */
function syncLyrics() {
  clearTimeout(lyrTimer);
  const box = $('#npLyrics .lyr-lines.synced'), L = LYR.lines, n = (ST.phone || {}).now;
  if (!box || !L || !n || !isOpen('nowPage')) return;
  const m = n.media || {};
  const pos = nowPos(n) + LYR_LEAD;
  let i = -1;
  while (i + 1 < L.length && L[i + 1].t <= pos) i++;
  if (i !== lyrAt) {
    const ps = box.children;
    for (let k = 0; k < ps.length; k++) {
      ps[k].classList.toggle('now', k === i);
      ps[k].classList.toggle('past', k < i);
    }
    // Into the middle of the box, unless you are reading elsewhere in it.
    if (i >= 0 && ps[i] && Date.now() > lyrUserUntil) {
      const off = ps[i].getBoundingClientRect().top - box.getBoundingClientRect().top;
      box.scrollTo({ top: box.scrollTop + off - box.clientHeight / 2 + ps[i].offsetHeight / 2, behavior: lyrAt === -2 ? 'auto' : 'smooth' });
    }
    lyrAt = i;
  }
  if ((m.playing ?? n.playing) && i + 1 < L.length) lyrTimer = setTimeout(syncLyrics, Math.max(40, (L[i + 1].t - pos) / (m.speed || 1)));
}
/** Once a second: the position, the time left, and what is left of the album. */
function tickNow() {
  if (!isOpen('nowPage')) return;
  const n = (ST.phone || {}).now;
  const seek = $('#npSeek');
  if (!n || !seek) return;
  const m = n.media || {};
  const dur = m.dur || n.dur || 0;
  const pos = nowPos(n);
  const dragging = seek.matches(':active');
  if (!dragging) {
    seek.max = dur;
    seek.value = pos;
  }
  // Without a length there is nothing to seek along, and the slider (max 0) would hold the time at 0:00.
  seek.hidden = !dur;
  const at = dragging ? +seek.value : pos;
  seek.style.setProperty('--p', dur ? `${((at / dur) * 100).toFixed(2)}%` : '0%');
  syncLyrics();
  $('#npEl').textContent = fmtDur(at / 1000);
  const sl = $('#npSleep');
  if (sl) {
    sl.classList.toggle('on', !!ST.sleep);
    const t = sleepLabel();
    if (sl.lastElementChild.textContent !== t) sl.lastElementChild.textContent = t;
  }
  $('#npLeft').textContent = dur ? `−${fmtDur((dur - at) / 1000)}` : '';
  const al = NOWALB.data, left = $('#npAlbumLeft');
  if (left && al && al.tracks) {
    const i = nowAlbumIndex(al.tracks, n.title);
    const rest = i < 0 ? 0 : al.tracks.slice(i + 1).reduce((s, t) => s + (t.sec || 0), 0) + Math.max(0, dur - pos) / 1000;
    left.textContent = i < 0 ? plural(al.tracks.length, 'track') : `track ${i + 1} of ${al.tracks.length}, ${fmtLong(rest)} left`;
  }
}
/** The sleep timer, as its button says it: off, at the end of the song, or the time left. */
function sleepLabel() {
  const s = ST.sleep;
  if (!s) return 'Sleep timer';
  if (s.song) return 'Stops after this song';
  const min = Math.max(1, Math.ceil((s.at - Date.now()) / 60000));
  return `Stops in ${min >= 60 ? `${Math.floor(min / 60)} h${min % 60 ? ` ${min % 60} min` : ''}` : `${min} min`}`;
}
/** Stop what plays after a while: the music fades out over the last half minute, then pauses. */
function openSleepSheet() {
  const s = ST.sleep;
  const opts = [[15, '15 min'], [30, '30 min'], [45, '45 min'], [60, '1 hour'], [90, '1½ hours']];
  openSheet(`<div class="sheet-title">Sleep timer</div>
    <p class="body">${s ? `${esc(sleepLabel())}${s.song ? '' : ` (at ${esc(fmtTime(s.at))})`}. Pick another time, or turn it off.` : 'Stops what plays after a while. The music fades out over the last half minute, then pauses; the volume is back where it was for next time.'}</p>
    <div class="chips">${opts.map(([m, l]) => `<button class="chip" data-sleepset="${m}">${l}</button>`).join('')}<button class="chip" data-sleepsong>${icon('music', 'i small')}End of this song</button></div>
    ${s ? `<div class="btn-row"><button class="btn tonal" data-sleepoff>Turn off</button></div>` : ''}`);
  $('#sheetContent').onclick = (e) => {
    const m = e.target.closest('[data-sleepset]');
    if (m) send({ t: 'sleep', minutes: +m.dataset.sleepset });
    else if (e.target.closest('[data-sleepsong]')) send({ t: 'sleep', song: true });
    else if (e.target.closest('[data-sleepoff]')) send({ t: 'sleep', cancel: true });
    else return;
    closeSheet();
  };
}

/** Asks the app to play, pause, skip, seek or jump; the page shows the result right away and the app's own answer after. */
function npControl(action, arg = 0) {
  const n = (ST.phone || {}).now;
  if (!n) return;
  send({ t: 'phoneControl', pkg: n.pkg, action, arg });
  const m = n.media;
  if (m && (action === 'play' || action === 'pause')) {
    m.pos = nowPos(n);
    m.posAt = Date.now();
    m.playing = action === 'play';
    renderNow();
  } else if (m && action === 'seek') {
    m.pos = arg;
    m.posAt = Date.now();
    tickNow();
  }
}

// ---------- search: what you own first, then everything you played ----------

let SEARCH = { q: '', res: null };
let searchTimer = 0;
const searching = () => document.body.classList.contains('searching');
let searchOut = null;
function openSearch() {
  if (searchOut) { searchOut.cancel(); searchOut = null; }
  $('#searchPage').inert = $('#searchDock').inert = false;
  $('#searchPage').hidden = false;
  $('#searchDock').hidden = false;
  document.body.classList.add('searching');
  renderSearch();
  $('#gSearch').focus();
  syncBack();
}
function closeSearch() {
  if (!searching()) return;
  const page = $('#searchPage'), dock = $('#searchDock');
  document.body.classList.remove('searching');
  $('#gSearch').blur();
  dock.animate([{ clipPath: 'inset(0 0 0 0 round 31px)', opacity: 1 }, { clipPath: 'inset(0 0 0 calc(100% - 62px) round 31px)', opacity: 0 }], { duration: 220, easing: EASE_IN });
  // Fading out, the results and the field take no taps; the tab under them does.
  page.inert = dock.inert = true;
  const a = animateOut(page, 'fade', () => {
    if (searchOut !== a) return;
    searchOut = null;
    page.hidden = true;
    dock.hidden = true;
    page.inert = dock.inert = false;
    $('#gSearch').value = '';
    SEARCH = { q: '', res: null };
  });
  searchOut = a;
  syncBack();
}
function onSearchInput(e) {
  SEARCH.q = e.target.value;
  renderSearch();
  clearTimeout(searchTimer);
  const q = SEARCH.q.trim();
  if (q) searchTimer = setTimeout(() => send({ t: 'search', q: SEARCH.q }), 160);
}
window.onSearch = (r) => {
  if (r.q !== SEARCH.q) return;
  SEARCH.res = r;
  renderSearch();
};
/** Records on the shelf whose title, artist, label, year or styles hold every word of [q]. */
function shelfMatches(q) {
  const words = norm(q.trim()).split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  return COLL.filter((r) => words.every((w) => r._s.includes(w))).sort((a, b) => coll.compare(a._artist, b._artist) || (a.year || 9999) - (b.year || 9999));
}
function renderSearch() {
  const q = SEARCH.q.trim();
  const body = $('#searchBody');
  if (!q) {
    body.innerHTML = `<div class="search-hint">${icon('search', 'i big')}<p>Your records first, then every artist, album and song you've played.</p></div>`;
    return;
  }
  const r = SEARCH.res && SEARCH.res.q === SEARCH.q ? SEARCH.res : null;
  const owned = shelfMatches(q);
  const sec = (title, n, html) => n ? `<div class="section-head"><h2>${title}</h2><span class="sub">${n}</span></div>${html}` : '';
  const shown = owned.slice(0, 9);
  const shelfHtml = sec('On your shelf', owned.length, `<div class="grid">${shown.map((x) => `<button class="rec" data-id="${x.id}">${coverHtml(x.cover || x.thumb, x.title, x.artist)}<b>${esc(x.title)}</b><small>${esc(x.artist)}</small></button>`).join('')}</div>
    ${owned.length > shown.length ? `<p class="hint">and ${plural(owned.length - shown.length, 'more record')}: add a word to narrow it down</p>` : ''}`);
  // Albums you own are already on the shelf above.
  const artists = (r && r.artists) || [], albums = ((r && r.albums) || []).filter((a) => !a.owned), tracks = (r && r.tracks) || [];
  const artistsHtml = sec('Artists', artists.length, `<div class="rank">${artists.map((a) => `<button class="r with-cover" data-artist="${esc(a.name)}">${coverHtml(a.photo, a.name, a.name, 'tiny round')}<span class="t"><b>${esc(a.name)}</b><small>${plural(a.plays, 'play')}</small></span>${icon('chevron-right')}</button>`).join('')}</div>`);
  const albumsHtml = sec('Albums', albums.length, `<div class="rank">${albums.map((a) => `<button class="r with-cover" ${albumAttrs(a.artist, a.title)}${a.cover ? ` data-cover="${esc(a.cover)}"` : ''}>${coverHtml(a.cover, a.title, a.artist, 'tiny')}<span class="t"><b>${esc(a.title)}</b><small>${esc(a.artist)}, ${plural(a.plays, 'play')}</small></span>${icon('chevron-right')}</button>`).join('')}</div>`);
  const tracksHtml = sec('Songs', tracks.length, `<div class="rank">${tracks.map((t) => `<button class="r" ${trackAttrs(t.artist, t.title)}><span class="n">${icon('music', 'i small')}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}</small></span><span class="v">${compact(t.plays)}</span></button>`).join('')}</div>`);
  // On the shelf your records come first; elsewhere what you played does.
  const parts = tab === 'shelf' ? [shelfHtml, artistsHtml, albumsHtml, tracksHtml] : [artistsHtml, albumsHtml, tracksHtml, shelfHtml];
  const none = !owned.length && r && !artists.length && !albums.length && !tracks.length;
  body.innerHTML = parts.join('') + (r ? '' : `<p class="hint search-wait">Looking through your history…</p>`) + (none ? `<div class="search-hint"><p>Nothing matches “${esc(q)}”.</p></div>` : '');
}

// ---------- record page ----------

/** The record page; [from] (a tapped record) lends its cover, which flies up to the big one. */
function openRecord(id, from) {
  const cover = from && from.querySelector('.cover');
  openPage('recordPage', cover ? 'fly' : '');
  recordId = id;
  const r = REL[id];
  if (!r || r.error || !r.tracks) send({ t: 'openRelease', id });
  renderRecord();
  flyHome = cover ? { id, el: cover } : null;
  const to = cover && heroCover();
  if (to) flyCover(cover, to, 440);
}

function trackGroups(tracks) {
  const groups = [];
  tracks.forEach((t, i) => {
    let g = groups[groups.length - 1];
    if (!g || g.side !== t.side) { g = { side: t.side, idx: [] }; groups.push(g); }
    g.idx.push(i);
  });
  return groups;
}
const lenOf = (t) => (t.dur > 0 ? t.dur : ST.prefs.defaultTrackSec);

function renderRecord() {
  const base = COLL.find((r) => r.id === recordId) || {};
  const r = REL[recordId];
  const rel = r && r.tracks ? r : null;
  const info = rel || base;
  const title = info.title || base.title || '';
  const artist = info.artist || base.artist || '';
  const meta = [info.year || '', labelText(info.label), info.format].filter(Boolean).join(', ');
  const tags = [...new Set([...(info.genres || []), ...(info.styles || [])])];
  const plays = (rel && rel.plays) || base.plays || 0;
  const spins = (rel && rel.spins) || base.spins || 0;
  const coverUrl = info.cover || base.cover || base.thumb;
  // Keep the moving sleeve across re-renders, so the record doesn't slide out again.
  const holoKey = `${recordId}|${coverUrl || ''}`;
  const keep = $('#recordBody [data-holo]');
  const reuse = keep && keep.dataset.key === holoKey;
  let h = `<div class="hero">
      <div id="recHolo">${reuse ? '' : holoCover(coverUrl, title, artist, { owned: true, back: true, rel: recordId, key: holoKey })}</div>
      <h1>${esc(title)}</h1>
      <div class="artist"><button class="artist-link" data-artist="${esc(artist)}">${esc(artist)}</button></div>
      <div class="meta">${esc(meta)}</div>
      ${badgeLine(recordId)}
      <div class="stats">${plays ? `<button class="chip link" data-url="${esc(lastfmUrl(artist, title))}">${plural(plays, 'play')}${rel && rel.tracks.length && plays >= rel.tracks.length ? `, ≈${Math.round(plays / rel.tracks.length)}× through` : ' on Last.fm'}${icon('open')}</button>` : ''}${spins ? `<span class="chip static">${plural(spins, 'spin')} logged here</span>` : ''}${(ST.history || {}).count ? `<button class="chip link" ${albumAttrs(artist, title)} data-cover="${esc(coverUrl || '')}">${icon('chart')}Your stats</button>` : ''}</div>
      ${listenRow('albums', artist, title)}
    </div>
    <div class="record-actions"><button class="btn filled big" id="scrobbleBtn" ${rel && rel.tracks.length ? '' : 'disabled'}>${icon('play')}Scrobble</button></div>
    <div id="recordSpinNote"></div>`;
  if (tags.length) h += `<div class="tags">${tags.map((t) => `<button class="chip link" data-genre="${esc(genreKeyOf(t))}" data-glabel="${esc(t)}">${esc(t)}</button>`).join('')}</div>`;

  if (!rel && r && r.error) {
    h += `<div class="card bad"><div class="card-title">${icon('warning')}Couldn't load the tracklist</div><p>${esc(r.error)}</p><div class="btn-row"><button class="btn tonal" id="retryRel">Try again</button></div></div>`;
  } else if (!rel) {
    h += `<div class="side-head"><h2>Tracklist</h2><span class="sub">loading…</span></div>${'<div class="skeleton"></div>'.repeat(6)}`;
  } else if (!rel.tracks.length) {
    h += `<div class="card"><p>Discogs has no tracklist for this release.</p></div>`;
  } else {
    for (const g of trackGroups(rel.tracks)) {
      const dur = g.idx.reduce((s, i) => s + lenOf(rel.tracks[i]), 0);
      h += `<div class="side-head"><h2>${esc(sideLabel(g.side))}</h2><span class="sub">${plural(g.idx.length, 'track')}, ${fmtLong(dur)}</span><span class="grow"></span>
        ${g.side ? `<button class="btn text small" data-side="${esc(g.side)}">${icon('play')}Scrobble</button>` : ''}</div>
        <div class="list">${g.idx.map((i) => trackRow(rel, i)).join('')}</div>`;
    }
    const srcs = [...new Set(rel.tracks.map((t) => t.src).filter(Boolean))].map((s) => SRC_NAMES[s] || s);
    const unknown = rel.tracks.filter((t) => !(t.dur > 0)).length;
    h += `<div class="note">${srcs.length ? `Track lengths from ${esc(srcs.join(' and '))}.` : 'No source knows the track lengths.'}
      ${unknown ? ` ${unknown === rel.tracks.length ? 'All' : unknown} unknown, counted as ≈${fmtDur(ST.prefs.defaultTrackSec)} each (change in Settings).` : ''}
      ${unknown ? `<br><button class="btn text small" id="refreshLen">${icon('sync')}Look up lengths again</button>` : ''}</div>`;
  }
  $('#recordBody').innerHTML = h;
  tint($('#recordBody'), coverUrl, title + artist);
  if (reuse) $('#recHolo').appendChild(keep);
  const sb = $('#recHolo .sleeve-back');
  if (sb) sb.innerHTML = sleeveBackInner(base, rel, plays);
  renderRecordSpinNote();
  Tilt.refresh();
}

const lfEnc = (s) => encodeURIComponent(s).replace(/%20/g, '+');
/** The user's own Last.fm library page for an artist or album (the Last.fm app opens these links if installed). */
function lastfmUrl(artist, album) {
  const u = (ST.lastfm || {}).user;
  const base = u ? `https://www.last.fm/user/${encodeURIComponent(u)}/library/music/` : 'https://www.last.fm/music/';
  return base + lfEnc(artist) + (album ? '/' + lfEnc(album) : '');
}

function trackRow(rel, i) {
  const t = rel.tracks[i];
  const est = !(t.dur > 0);
  const showArtist = t.artist && t.artist !== rel.artist;
  return `<button class="track" data-i="${i}"><span class="pos">${esc(t.pos)}</span><span class="grow"><b>${esc(t.title)}</b>${showArtist ? `<small>${esc(t.artist)}</small>` : ''}</span>
    <span class="dur ${est ? 'est' : ''}">${est ? '≈' : ''}${fmtDur(lenOf(t))}</span></button>`;
}

function renderRecordSpinNote() {
  const slot = $('#recordSpinNote');
  if (!slot) return;
  const sp = ST.spin;
  slot.innerHTML = sp && sp.releaseId === recordId
    ? `<button class="spin-card" id="recordSpinCard" style="grid-template-columns:1fr auto"><div class="txt"><small>Spinning now</small><b>${esc((spinNow(sp).item || {}).title || sp.album)}</b></div><span class="eq plain"><i></i><i></i><i></i></span></button>`
    : '';
}

// ---------- scrobble sheet ----------

function openScrobble(preset = {}) {
  const rel = REL[recordId];
  if (!rel || !rel.tracks || !rel.tracks.length) return;
  const tracks = rel.tracks.map((t, i) => ({ ...t, i, dur: lenOf(t), est: !(t.dur > 0) }));
  const sides = [...new Set(tracks.map((t) => t.side).filter(Boolean))];
  const st = { what: 'all', picked: new Set(), when: 'now', at: null };
  if (preset.side != null) st.what = 'side:' + preset.side;
  else if (preset.track != null) { st.what = 'pick'; st.picked.add(preset.track); }
  else if (sides.length > 1) {
    const sp = ST.spin;
    st.what = 'side:' + (sp && sp.releaseId === rel.id && sp.nextSide ? sp.nextSide : sides[0]);
  }
  const selected = () => (st.what === 'all' ? tracks : st.what === 'pick' ? tracks.filter((t) => st.picked.has(t.i)) : tracks.filter((t) => 'side:' + t.side === st.what));

  function render() {
    const sel = selected();
    const now = Date.now();
    if (st.when === 'at' && st.at == null) st.at = Math.round((now - 3600e3) / 300e3) * 300e3;
    const plan = sel.length ? planStarts(sel, st.when, st.at, now) : null;
    const chip = (v, label, sub) => `<button class="chip ${st.what === v ? 'on' : ''}" data-what="${esc(v)}">${esc(label)}${sub ? ` <small>${sub}</small>` : ''}</button>`;
    let h = `<div class="sheet-title">Scrobble</div>
      <div class="mini">${coverHtml(rel.thumb || rel.cover, rel.title, rel.artist, 'tiny')}<div><b>${esc(rel.title)}</b><small>${esc(rel.artist)}</small></div></div>
      <div class="label">What you play</div>
      <div class="chips" id="whatChips">
        ${sides.map((s) => chip('side:' + s, sideLabel(s), fmtLong(tracks.filter((t) => t.side === s).reduce((a, t) => a + t.dur, 0)))).join('')}
        ${chip('all', sides.length > 1 ? 'Whole record' : 'All tracks', sides.length > 1 ? fmtLong(tracks.reduce((a, t) => a + t.dur, 0)) : '')}
        ${chip('pick', 'Choose tracks')}
      </div>`;
    if (st.what === 'pick') {
      h += `<div class="pick" id="pickList">${tracks.map((t) => `<label><input type="checkbox" data-i="${t.i}" ${st.picked.has(t.i) ? 'checked' : ''}><span class="pos">${esc(t.pos)}</span><span class="t">${esc(t.title)}</span><span class="d">${t.est ? '≈' : ''}${fmtDur(t.dur)}</span></label>`).join('')}</div>`;
    }
    h += `<div class="label">When</div>
      <div class="seg" id="whenSeg">
        ${[['now', 'Starting now'], ['at', 'Earlier'], ['finished', 'Just finished']].map(([v, l]) => `<button data-v="${v}" class="${st.when === v ? 'on' : ''}">${l}</button>`).join('')}
      </div>`;
    if (st.when === 'at') h += `<label class="field"><span>Started at</span><input type="datetime-local" id="atInput" value="${toLocalInput(st.at)}"></label>`;

    let blocked = !plan;
    if (plan) {
      const n = sel.length;
      let sub;
      if (st.when === 'now') sub = `Until about ${fmtTime(plan.end)}. Each track is scrobbled once half of it has played, and shows as now playing on Last.fm.`;
      else if (plan.end > now) sub = `${whenLabel(plan.start)} to ${fmtTime(plan.end)}. Tracks that have played are scrobbled now, the rest as they play.`;
      else sub = `${whenLabel(plan.start)} to ${fmtTime(plan.end)}. Scrobbled right away, each at the time it played.`;
      h += `<div class="summary"><b>${plural(n, 'track')}, ${fmtLong(plan.total / 1000)}</b><small>${sub}</small></div>`;
      const warn = (text, bad) => `<div class="warnline ${bad ? 'bad' : ''}">${icon(bad ? 'block' : 'warning')}<span>${text}</span></div>`;
      const est = sel.filter((t) => t.est).length;
      const guess = `≈${fmtDur(ST.prefs.defaultTrackSec)}`;
      if (est === n) h += warn(`No source knows these track lengths, so each counts as ${guess}.`);
      else if (est === 1) h += warn(`1 of these tracks has no known length, so it counts as ${guess}.`);
      else if (est) h += warn(`${est} of these tracks have no known length, so they count as ${guess}.`);
      const old = plan.starts.filter((s) => now - s > MAX_AGE_MS).length;
      if (old === n) { h += warn('Last.fm ignores scrobbles older than 2 weeks, so these would be dropped.', true); blocked = true; }
      else if (old) h += warn(`Last.fm ignores scrobbles older than 2 weeks: ${old} of these tracks would be dropped.`, true);
      else if (plan.starts.some((s) => now - s > MAX_AGE_MS - 864e5)) h += warn('Close to Last.fm\'s 2-week limit. Make sure the phone is online so they go out in time.');
      if (st.when === 'at' && plan.start > now + 60e3) h += warn('That start time is in the future; the tracks are scrobbled as they play.');
      if (ST.spin && plan.end > now) h += warn(`Replaces ${esc(ST.spin.album)}, which is spinning now. What has played of it still counts.`);
      if (!(ST.lastfm || {}).connected) h += warn('Last.fm isn\'t connected yet. The scrobbles wait on this phone until it is.');
    }
    h += `<div class="sheet-actions"><button class="btn text" id="scCancel">Cancel</button>
      <button class="btn filled" id="scGo" ${blocked ? 'disabled' : ''}>Scrobble ${sel.length ? plural(sel.length, 'track') : ''}</button></div>`;
    openSheet(h);

    $('#whatChips').onclick = (e) => {
      const b = e.target.closest('[data-what]');
      if (!b) return;
      st.what = b.dataset.what;
      if (st.what === 'pick' && !st.picked.size) tracks.forEach((t) => st.picked.add(t.i));
      render();
    };
    const pick = $('#pickList');
    if (pick) pick.onchange = (e) => {
      const i = +e.target.dataset.i;
      if (e.target.checked) st.picked.add(i); else st.picked.delete(i);
      render();
    };
    $('#whenSeg').onclick = (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      st.when = b.dataset.v;
      render();
    };
    const at = $('#atInput');
    if (at) at.onchange = () => { const v = fromLocalInput(at.value); if (!Number.isNaN(v)) { st.at = v; render(); } };
    $('#scCancel').onclick = closeSheet;
    $('#scGo').onclick = () => {
      const p = planStarts(sel, st.when, st.at, Date.now());
      send({ t: 'startSpin', releaseId: rel.id, tracks: sel.map((t) => t.i), when: st.when, at: st.at || 0 });
      closeSheet();
      if (p.end > Date.now()) {
        if (ST.spin && ST.spin.releaseId === rel.id) { openPage('spinPage'); renderSpin(true); }
        else pendingSpinOpen = true;
      } else toast(`Scrobbling ${plural(sel.length, 'track')}`);
    };
  }
  render();
}
let pendingSpinOpen = false;

// ---------- now spinning page ----------

let spinSig = '';
function renderSpin(force) {
  const sp = ST.spin;
  const body = $('#spinBody');
  if (!sp) {
    spinSig = '';
    body.innerHTML = `<div class="empty">${icon('album', 'i big')}<p>Nothing is spinning.<br>Pick a record on your shelf and tap Scrobble.</p></div>`;
    return;
  }
  const sig = sp.id + sp.items.map((i) => i.state.charAt(0) + i.start).join(',') + sp.nextSide;
  if (!force && sig === spinSig) { tickUi(); return; }
  spinSig = sig;
  const keep = $('#spinBody .tt');
  const reuse = keep && keep.dataset.key === sp.id;
  body.innerHTML = `<div class="spin-hero">
      <div id="spinHolo">${reuse ? '' : turntableHtml(sp)}</div>
      <div class="side" id="spSide"></div>
      <h1 id="spTitle"></h1>
      <div class="artist">${esc(sp.artist)}, ${esc(sp.album)}</div>
      <div class="bar"><i id="spBar"></i></div>
      <div class="times"><span id="spElapsed"></span><span id="spLeft"></span></div>
    </div>
    <div class="btn-row">
      ${sp.nextSide ? `<button class="btn tonal" id="spFlip">${icon('flip')}Flip to ${esc(sp.nextSide.length <= 2 ? 'side ' + sp.nextSide : sp.nextSide)}</button>` : ''}
      <button class="btn outlined" id="spStop">${icon('stop')}Stop</button>
    </div>
    <div class="section-head"><h2>Tracks</h2><span class="sub" id="spEnd"></span></div>
    <div class="list" id="spList"></div>
    <p class="hint">Out of step with the record? Tap the track that is playing right now, and the times after it move.</p>`;
  if (reuse) $('#spinHolo').appendChild(keep);
  tint(body, sp.cover, sp.album + sp.artist);
  lastCur = -2;
  tickUi();
  Tilt.refresh();
}

function renderSpinList() {
  const sp = ST.spin;
  const list = $('#spList');
  if (!sp || !list) return;
  const n = spinNow(sp);
  lastCur = n.cur;
  list.innerHTML = sp.items.map((it, i) => {
    const now = i === n.cur;
    const cls = it.state === 'skipped' ? 'skipped' : now ? 'now' : it.state === 'scrobbled' ? 'done' : '';
    const mark = now ? '<span class="eq plain"><i></i><i></i><i></i></span>' : it.state === 'scrobbled' ? icon('check') : '';
    const sub = it.state === 'skipped' ? 'Not played' : now ? `${fmtTime(it.start)}, playing${it.state === 'scrobbled' ? ', scrobbled' : ''}` : it.state === 'scrobbled' ? `${fmtTime(it.start)}, scrobbled` : `${fmtTime(it.start)}${it.est ? ', length guessed' : ''}`;
    return `<button class="track ${cls}" data-i="${i}"><span class="pos">${esc(it.pos)}</span><span class="grow"><b>${esc(it.title)}</b><small>${sub}</small></span><span class="st">${mark}</span></button>`;
  }).join('');
}

// ---------- activity ----------

function renderActivity() {
  renderQueueCard();
  const list = ACT.slice().sort((a, b) => b.ts - a.ts || b.at - a.at);
  $('#activityEmpty').hidden = list.length > 0;
  let html = '', day = '', group = [];
  const close = () => { if (group.length) html += `<div class="list">${group.join('')}</div>`; group = []; };
  for (const e of list) {
    const d = dayLabel(e.ts * 1000);
    if (d !== day) { close(); html += `<div class="day">${esc(d)}</div>`; day = d; }
    group.push(actRow(e));
  }
  close();
  $('#activityList').innerHTML = html;
}

/** The status card above the activity list: what waits to be sent, and why. */
function renderQueueCard() {
  const q = ST.queue || {}, l = ST.lastfm || {};
  let card = '';
  if (q.waiting > 0) {
    const old = q.oldest ? q.oldest * 1000 : 0;
    const near = old && Date.now() - old > 12 * 864e5;
    const nearText = near ? ` The oldest is from ${dayLabel(old)}, and Last.fm ignores scrobbles older than 2 weeks.` : '';
    if (!l.connected) {
      card = `<div class="card ${near ? 'bad' : 'warn'}"><div class="card-title">${icon('cloud-off')}${q.waiting} waiting on this phone</div>
        <p>${l.needsAuth ? 'Last.fm asks you to allow access again.' : 'Connect Last.fm to send them.'}${nearText}</p>
        <div class="btn-row"><button class="btn tonal" data-go="lfSection">${l.needsAuth ? 'Reconnect' : 'Connect Last.fm'}</button></div></div>`;
    } else {
      card = `<div class="card ${near ? 'bad' : 'warn'}"><div class="card-title">${icon('schedule')}${q.waiting} waiting to be sent</div>
        <p>${esc(q.error || 'Sending…')}${nearText}</p>
        <div class="btn-row"><button class="btn tonal" id="flushBtn">Send now</button></div></div>`;
    }
  } else if (ACT.length && l.connected) {
    card = `<div class="card"><div class="status-line">${icon('done-all')}<span>All sent to Last.fm<small>as ${esc(l.user)}</small></span></div></div>`;
  }
  if ($('#queueCard').innerHTML !== card) $('#queueCard').innerHTML = card;
}

function actRow(e) {
  const st = { waiting: ['schedule', 'Waiting'], ignored: ['block', 'Ignored'], failed: ['warning', 'Failed'], shadow: ['eye', 'Shadow'] }[e.status];
  const why = e.msg && e.status !== 'sent' ? `<small class="why">${esc(e.msg)}</small>` : '';
  const from = e.src === 'vinyl' ? 'Vinyl' : e.app || 'Phone';
  return `<div class="item act" ${trackAttrs(e.artist, e.track, { album: e.album, cover: e.cover })}>${coverHtml(e.cover, e.album || e.track, e.artist, 'tiny')}
    <span class="grow"><b>${esc(e.track)}</b><small>${esc(e.artist)}${e.album ? ', ' + esc(e.album) : ''}</small>${why}</span>
    <span class="trail"><span>${fmtTime(e.ts * 1000)}</span>${st ? `<span class="status ${e.status}">${icon(st[0])}${st[1]}</span>` : `<span class="status sent">${esc(from)}</span>`}</span></div>`;
}

// ---------- phone scrobbling ----------

const dismissed = (k) => { try { return localStorage.getItem('dismiss:' + k) === '1'; } catch (_) { return false; } };
const dismiss = (k) => { try { localStorage.setItem('dismiss:' + k, '1'); } catch (_) { /* preview without storage */ } };

function caughtPct(c) {
  const theirs = c.matched + c.renamed.length + c.onlyTheirs.length;
  return theirs ? Math.round(((c.matched + c.renamed.length) / theirs) * 100) : 0;
}

/** Top of the Activity tab: what the phone is playing and how close it is to counting. */
function renderPhoneCard() {
  const p = ST.phone || {};
  let h = '';
  if (!p.mode || p.mode === 'off') {
    if (!dismissed('phoneIntro')) {
      h = `<div class="card tonal"><div class="card-title">${icon('phone')}Scrobble this phone too</div>
        <p>Heavy Rotation can take over from Pano Scrobbler. Start with shadow mode: it only logs what it would scrobble, so you can compare it with Pano first.</p>
        <div class="btn-row"><button class="btn filled" data-go="phSection">Set up</button><button class="btn tonal" id="phIntroNo">Not now</button></div></div>`;
    }
  } else if (!p.access) {
    h = `<div class="card warn"><div class="card-title">${icon('phone')}Phone scrobbling needs access</div>
      <p>Allow notification access so Heavy Rotation can see what music apps play. Nothing is scrobbled from the phone until then.</p>
      <div class="btn-row"><button class="btn tonal" id="phAccessBtn2">Allow access</button></div></div>`;
  } else {
    const n = p.now;
    const mode = p.mode === 'shadow' ? `<span class="chip static mode">${icon('eye')}Shadow</span>` : `<span class="chip static mode on">${icon('check')}Scrobbling</span>`;
    if (n) {
      h = `<div class="card phone-now"><div class="pn-top"><span class="chip static">${esc(n.app)}</span>${mode}</div>
        <b>${esc(n.title)}</b><small>${esc(n.artist)}${n.album ? ', ' + esc(n.album) : ''}</small>
        <div class="bar"><i id="pnBar"></i></div><small class="pn-text" id="pnText"></small>
        ${n.on ? '' : `<button class="btn tonal small" data-app-on="${esc(n.pkg)}">Scrobble ${esc(n.app)}</button>`}</div>`;
    } else {
      h = `<div class="listen-line">${icon('phone')}<span>Waiting for music on this phone</span>${mode}</div>`;
    }
    if (p.mode === 'shadow') {
      const sum = CMP ? `${caughtPct(CMP)}% of what Pano sent to Last.fm was caught here; ${plural(CMP.onlyTheirs.length, 'track')} missed. Checked ${ago(CMP.at)}.`
        : 'After a day or two of listening, compare what Heavy Rotation logged with what Pano sent to Last.fm.';
      h += `<div class="card"><div class="card-title">${icon('compare')}Compare with Pano</div><p>${esc(sum)}</p>
        <div class="btn-row"><button class="btn tonal" id="cmpOpen">${CMP ? 'See comparison' : 'Compare now'}</button></div></div>`;
    }
    if (p.mode === 'on' && p.pano) {
      h += `<div class="card bad"><div class="card-title">${icon('warning')}Pano Scrobbler is still installed</div>
        <p>If it still scrobbles, every track is scrobbled twice. Turn off its scrobbling or uninstall it.</p>
        <div class="btn-row"><button class="btn tonal" data-app-info="com.arn.scrobble">Pano's app info</button></div></div>`;
    }
  }
  const slot = $('#phoneCard');
  if (slot.dataset.html !== h) { slot.innerHTML = h; slot.dataset.html = h; }
  tickPhone();
}

function tickPhone() {
  const n = (ST.phone || {}).now;
  if (!n) return;
  const listened = n.listened + (n.playing ? Date.now() - n.at : 0);
  // The shelf's now-playing card: how far into the song (listened time stands in for the position).
  const nb = $('#phNowBar');
  if (nb && n.dur > 0) nb.style.width = `${Math.min(100, (listened / n.dur) * 100)}%`;
  const bar = $('#pnBar'), text = $('#pnText');
  if (!bar) return;
  const left = Math.max(0, n.threshold - listened);
  bar.style.width = `${n.scrobbled ? 100 : Math.min(100, (listened / n.threshold) * 100)}%`;
  const shadow = (ST.phone || {}).mode === 'shadow';
  let t;
  if (!n.on) t = `${n.app} is switched off, so it isn't scrobbled`;
  else if (!n.eligible) t = '30 seconds or shorter: Last.fm doesn\'t count it';
  else if (n.scrobbled) t = shadow ? 'Logged (shadow mode: not sent)' : 'Scrobbled';
  else t = `Counts in ${fmtDur(left / 1000)}${n.playing ? '' : ', paused'}`;
  text.textContent = t;
}

function renderCompare() {
  const p = ST.phone || {};
  const c = CMP;
  let h = '';
  if (p.comparing) h += `<div class="lin inline indet"><i></i></div><p class="hint">Reading your scrobbles from Last.fm…</p>`;
  if (p.compareError) h += `<div class="card bad"><p>${esc(p.compareError)}</p></div>`;
  if (!c) {
    h += `<div class="empty">${icon('compare', 'i big')}<p>Nothing compared yet.<br>This reads what reached Last.fm while shadow mode was on and puts it next to what Heavy Rotation logged.</p></div>`;
  } else {
    const theirs = c.matched + c.renamed.length + c.onlyTheirs.length;
    const pct = caughtPct(c);
    let verdict = '';
    if (theirs < 30) verdict = 'Still a small sample. Listen a bit more for a clear picture.';
    else if (pct >= 97 && c.onlyTheirs.length <= 3) verdict = 'Looks ready: switch phone scrobbling to On and turn off Pano.';
    else verdict = 'Look at the missed tracks below before switching.';
    h += `<div class="card cmp-head"><div class="big">${pct}%</div>
      <p>of ${plural(theirs, 'scrobble')} on Last.fm since ${esc(whenLabel(c.from * 1000))} were also caught here.</p>
      <p class="verdict">${esc(verdict)}</p></div>
      <div class="stats-grid">
        <div class="stat"><b>${c.matched}</b><span>the same</span></div>
        <div class="stat"><b>${c.renamed.length}</b><span>named differently</span></div>
        <div class="stat ${c.onlyTheirs.length ? 'bad' : ''}"><b>${c.onlyTheirs.length}</b><span>only on Last.fm</span></div>
        <div class="stat"><b>${c.onlyOurs.length}</b><span>only here</span></div>
      </div>`;
    const row = (x, sub) => `<div class="cmp-row"><span class="t">${esc(dayLabel(x.ts * 1000))}<br>${esc(fmtTime(x.ts * 1000))}</span><span class="w"><div>${esc(x.title)} by ${esc(x.artist)}</div>${sub ? `<small>${esc(sub)}</small>` : ''}</span></div>`;
    if (c.onlyTheirs.length) {
      h += `<div class="section-head"><h2>Only on Last.fm</h2></div>
        <p class="hint">Pano (or another scrobbler) sent these; Heavy Rotation didn't log them. Was the app switched on here, or the track skipped early?</p>
        <div class="list">${c.onlyTheirs.slice().sort((a, b) => b.ts - a.ts).map((x) => row(x)).join('')}</div>`;
    }
    if (c.renamed.length) {
      h += `<div class="section-head"><h2>Named differently</h2></div>
        <p class="hint">Same time, different names. Pano may clean up titles with its edit rules.</p>
        <div class="list">${c.renamed.slice().sort((a, b) => b.ours.ts - a.ours.ts).map((r) => row(r.theirs, `here: ${r.ours.title} by ${r.ours.artist}`)).join('')}</div>`;
    }
    if (c.onlyOurs.length) {
      h += `<div class="section-head"><h2>Only here</h2></div>
        <p class="hint">Heavy Rotation would have scrobbled these, but Last.fm has nothing at that time.</p>
        <div class="list">${c.onlyOurs.slice().sort((a, b) => b.ts - a.ts).map((x) => row(x)).join('')}</div>`;
    }
  }
  h += `<button class="btn filled wide" id="cmpRun" ${p.comparing ? 'disabled' : ''}>${icon('sync')}${c ? 'Compare again' : 'Compare now'}</button>
    <p class="hint">Covers the time since shadow mode was switched on (at most the last 7 days), up to 10 minutes ago. Records scrobbled here are left out.</p>`;
  $('#compareBody').innerHTML = h;
}

function setPhoneMode(v) {
  const p = ST.phone || {};
  if (v === p.mode) return;
  if (v === 'on' && p.pano) {
    confirmSheet('Switch phone scrobbling on?', 'Pano Scrobbler is still installed. Turn off its scrobbling or uninstall it afterwards, or every track is scrobbled twice.', 'Switch on', () => send({ t: 'setPhoneMode', mode: 'on' }));
    return;
  }
  send({ t: 'setPhoneMode', mode: v });
  if (v !== 'off' && !p.access) toast('Now allow notification access for Heavy Rotation');
}

function renderPhoneSettings() {
  const p = ST.phone || {};
  const mode = p.mode || 'off';
  $$('#phMode button').forEach((b) => b.classList.toggle('on', b.dataset.v === mode));
  let st;
  if (mode === 'off') st = statusLine('', 'Off', 'Nothing on this phone is scrobbled by Heavy Rotation');
  else if (!p.access) st = statusLine('bad', 'Needs notification access', '');
  else if (!p.connected) st = statusLine('busy', 'Starting…', 'Android is connecting the listener');
  else st = statusLine('ok', mode === 'shadow' ? 'Shadow mode: logging only' : 'Scrobbling this phone', p.now ? `${p.now.app}: ${p.now.title}` : 'Waiting for music');
  $('#phStatus').innerHTML = st;
  $('#phHint').textContent = {
    off: 'Pano Scrobbler (or nothing) takes care of the phone.',
    shadow: 'Logs what it would scrobble without sending anything. Keep Pano running, listen for a day or two, then compare on the Activity tab.',
    on: 'Scrobbles what plays on this phone, with now playing and the same offline queue as your records.',
  }[mode];
  $('#phAccess').hidden = mode === 'off' || !!p.access;
  $('#phPano').hidden = !(mode === 'on' && p.pano);
  $('#phAppsWrap').hidden = mode === 'off';
  const apps = p.apps || [];
  const html = apps.map((a) => `<label class="switch-row"><span>${esc(a.label)}<small>${a.last ? esc(a.last) : 'Nothing played yet'}</small></span>
    <input type="checkbox" data-pkg="${esc(a.pkg)}" ${a.on ? 'checked' : ''}><i class="switch"></i></label>`).join('');
  if ($('#phApps').dataset.html !== html) { $('#phApps').innerHTML = html; $('#phApps').dataset.html = html; }
}

// ---------- stats ----------

const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const DOW_LONG = ['Mondays', 'Tuesdays', 'Wednesdays', 'Thursdays', 'Fridays', 'Saturdays', 'Sundays'];
const GENRE_COLORS = ['var(--g1)', 'var(--g2)', 'var(--g3)', 'var(--g4)', 'var(--g5)', 'var(--g6)'];
/** Artist inks (page) and the same hues for the black record (its dark steps). */
const INKS = ['var(--a1)', 'var(--a2)', 'var(--a3)', 'var(--a4)', 'var(--a5)', 'var(--a6)', 'var(--a7)', 'var(--a8)'];
const INKS_ON_VINYL = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'];
const num = (n) => Number(n || 0).toLocaleString();
const pct = (x) => `${Math.round((x || 0) * 100)}%`;
function compact(n) {
  n = Number(n || 0);
  // The unit is picked after rounding: 999,600 is 1M, not 1000K.
  if (Math.round(n / 1e3) >= 1e3) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
  if (n >= 1e4) return Math.round(n / 1e3) + 'K';
  if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K';
  return String(n);
}
const dateLabel = (ms) => new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
const dayName = (ms) => new Date(ms).toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' });

/** A day, week, month, quarter or year as a label; [short] for axis ticks. */
function periodLabel(ms, unit, short) {
  const d = new Date(ms);
  switch (unit) {
    case 'day': return d.toLocaleDateString([], short ? { day: 'numeric', month: 'short' } : { weekday: 'short', day: 'numeric', month: 'short' });
    case 'week': return short ? d.toLocaleDateString([], { day: 'numeric', month: 'short' }) : `Week of ${d.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' })}`;
    case 'month': return d.toLocaleDateString([], short ? { month: 'short' } : { month: 'long', year: 'numeric' });
    case 'quarter': return `Q${Math.floor(d.getMonth() / 3) + 1}${short ? '' : ' ' + d.getFullYear()}`;
    default: return String(d.getFullYear());
  }
}

/** The name of a stats period ({kind, from in seconds}). */
function periodName(r) {
  // Without its start (an older answer), the period's own label rather than "Invalid date".
  if (!r.from && r.kind !== 'all' && r.kind !== '12m') return r.label || 'This period';
  if (r.kind === 'day') {
    const d = new Date(r.from * 1000);
    return d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'short', ...(d.getFullYear() === new Date().getFullYear() ? {} : { year: 'numeric' }) });
  }
  const d = new Date(r.from * 1000);
  switch (r.kind) {
    case 'week': {
      const e = new Date(d);
      e.setDate(e.getDate() + 6);
      // One month: "3–9 Oct 2026"; across two: "29 Sep to 5 Oct 2026".
      return d.getMonth() === e.getMonth() ? `${d.getDate()}–${e.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' })}`
        : `${d.toLocaleDateString([], { day: 'numeric', month: 'short' })} to ${e.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' })}`;
    }
    case 'month': return d.toLocaleDateString([], { month: 'long', year: 'numeric' });
    case 'year': return String(d.getFullYear());
    case '12m': return 'Last 12 months';
    default: return 'All time';
  }
}
const periodWord = (kind) => ({ day: 'day', week: 'week', month: 'month', year: 'year', '12m': 'year' }[kind] || 'time');
const ofThe = (kind, what) => (kind === 'all' ? `${what} of all time` : `${what} of the ${periodWord(kind)}`);
const lastWord = (kind) => ({ day: 'yesterday', week: 'last week', month: 'last month', year: 'last year', '12m': 'the year before' }[kind] || 'before');
/** What a running period is compared with: the same stretch of the one before ("this time last week"). */
const vsWord = (r) => (r.running && r.kind !== '12m' ? `this time ${lastWord(r.kind)}` : lastWord(r.kind));

const isoDate = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
/** The id of the [kind] period that contains [ms] (see Stats.range in Kotlin). */
function periodId(kind, ms) {
  const d = new Date(ms);
  if (kind === 'day') return 'd' + isoDate(d);
  if (kind === 'week') return 'w' + isoDate(new Date(d.getFullYear(), d.getMonth(), d.getDate() - ((d.getDay() + 6) % 7)));
  if (kind === 'month') return `m${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
  if (kind === 'year') return 'y' + d.getFullYear();
  return 'all';
}
/** The id of the period [k] steps from period [id] (see Stats.neighbor), or null past now or before [historyStart] (s). */
function stepPeriod(id, k, historyStart) {
  const m = /^([dwmy])(\d+)(?:-(\d+))?(?:-(\d+))?$/.exec(id || '');
  if (!m) return null;
  const y = +m[2], mo = (+m[3] || 1) - 1, d = +m[4] || 1;
  const at = (j) => (m[1] === 'd' ? new Date(y, mo, d + j) : m[1] === 'w' ? new Date(y, mo, d + 7 * j) : m[1] === 'm' ? new Date(y, mo + j, 1) : new Date(y + j, 0, 1));
  const from = at(k), to = at(k + 1);
  if (+from > Date.now() || (historyStart && +to <= historyStart * 1000)) return null;
  return periodId({ d: 'day', w: 'week', m: 'month', y: 'year' }[m[1]], +from);
}

/** The period last asked for: ‹ and › step on from it, as STATS only changes once the answer is in. */
let statsAsked = '';
function requestStats(id) {
  if (id) statsRange = statsAsked = id;
  if (!STATS || STATS.empty) { if ((ST.history || {}).count > 0) send({ t: 'stats', range: statsRange, genre: statsGenre, decade: statsDecade }); return; }
  $('#statsBody').classList.add('stale');
  send({ t: 'stats', range: statsRange, genre: statsGenre, decade: statsDecade });
}

/** Above the stats: importing, or how fresh the history is. */
function renderStatsHead() {
  const hs = ST.history || {}, l = ST.lastfm || {};
  let h = '';
  if (!l.connected && !(hs.count > 0)) {
    h = `<div class="card warn"><div class="card-title">${icon('chart')}Stats come from Last.fm</div>
      <p>Connect Last.fm, then import your history to see when, what and how you listen.</p>
      <div class="btn-row"><button class="btn tonal" data-go="lfSection">Set up Last.fm</button></div></div>`;
  } else if (hs.importing) {
    const p = hs.total ? Math.round((hs.fetched / hs.total) * 100) : 0;
    h = `<div class="card"><div class="card-title">${icon('download')}Importing your history: ${p}%</div>
      <p>${num(hs.fetched)} of ${hs.total ? num(hs.total) : '…'} scrobbles. Keep using your phone; it carries on in the background.</p>
      <div class="lin inline ${hs.total ? '' : 'indet'}" style="--p:${p}%"><i></i></div>
      <div class="btn-row"><button class="btn text" id="histStop">Pause</button></div></div>`;
  } else if (!hs.done) {
    h = `<div class="card tonal"><div class="card-title">${icon('chart')}${hs.resumable ? 'Import paused' : 'See your whole listening history'}</div>
      <p>Copies all your Last.fm scrobbles to this phone, so the stats cover everything: streaming, records, all of it. A long history takes a few minutes the first time (roughly one per 20,000 scrobbles); after that only new scrobbles are fetched.</p>
      ${hs.error ? `<p class="error">${esc(hs.error)}</p>` : ''}
      <div class="btn-row"><button class="btn filled" id="histImport">${icon('download')}${hs.resumable ? 'Continue import' : 'Import history'}</button></div></div>`;
  } else {
    const bits = [`${num(hs.count)} scrobbles`, hs.syncing ? 'updating…' : hs.lastSync ? `updated ${ago(hs.lastSync)}` : ''];
    if (hs.tagsTotal) bits.push(`looking up genres ${hs.tagsDone}/${hs.tagsTotal}`);
    h = `<button class="listen-line" data-histcheck>${icon('history')}<span>${bits.filter(Boolean).join(', ')}</span></button>`;
  }
  const slot = $('#statsHead');
  if (slot.dataset.html !== h) { slot.innerHTML = h; slot.dataset.html = h; }
  if (hs.done && hs.count > 0 && (!STATS || STATS.empty) && tab === 'stats' && !renderStatsHead.asked) {
    renderStatsHead.asked = true;
    send({ t: 'stats', range: statsRange });
  }
}

/**
 * Your history against Last.fm: all scrobbles, each year's, and your most played artists. A year
 * that came up short can be fetched again (the app also does this by itself once a week).
 */
let HCHECK = null;
function openHistoryCheck() {
  HCHECK = { busy: true, step: 'Asking Last.fm…' };
  openSheet(`<div class="sheet-title">Your history and Last.fm</div><div id="hcBody">${hcHtml()}</div>`);
  send({ t: 'historyCheck', repair: false });
}
window.onHistoryCheck = (r) => {
  HCHECK = r;
  const b = $('#hcBody');
  if (b) b.innerHTML = hcHtml();
};
function hcHtml() {
  const r = HCHECK || {};
  if (r.error) return `<p class="body error">${esc(r.error)}</p>`;
  if (r.busy) return `<p class="body">${esc(r.step || 'Checking…')}</p><div class="lin inline indet"><i></i></div>`;
  const years = r.years || [];
  const short = years.filter((y) => y.lastfm - y.here >= 3);
  const gap = r.lastfm - r.here;
  const head = short.length
    ? `<p class="body"><b>${num(short.reduce((n, y) => n + y.lastfm - y.here, 0))} scrobbles</b> on Last.fm aren't on this phone yet, in ${short.map((y) => y.year).join(', ')}. Fetching those years again adds them; what is here already stays as it is.</p>`
    : `<p class="body">Every year matches Last.fm${gap > 0 ? ` (${plural(gap, 'scrobble')} apart in all: new ones on their way, or on a year's edge)` : ''}.${r.added ? ` ${num(r.added)} missing ${r.added === 1 ? 'scrobble was' : 'scrobbles were'} just added.` : ''}</p>`;
  const rows = years.slice().reverse().map((y) => {
    const d = y.lastfm - y.here;
    return `<div class="hc-row${d >= 3 ? ' short' : ''}"><b>${y.year}</b><span>${num(y.lastfm)}</span><span>${num(y.here)}</span><em>${d >= 3 ? `−${num(d)}` : '✓'}</em></div>`;
  }).join('');
  const artists = (r.artists || []).map((a) => `<div class="hc-row"><b>${esc(a.name)}</b><span>${num(a.lastfm)}</span><span>${num(a.here)}</span><em></em></div>`).join('');
  return `${head}
    <div class="hc"><div class="hc-row hc-h"><b>Year</b><span>Last.fm</span><span>Here</span><em></em></div>${rows}</div>
    ${short.length ? '<div class="btn-row"><button class="btn filled" data-hcfix>Fetch those years again</button></div>' : ''}
    ${artists ? `<h3 class="hc-t">Artists that differ</h3><p class="hint">Of your ${num(r.checked)} most played on Last.fm, counted by the name as scrobbled. A gap left after the years match usually means Last.fm merged spellings of a name that are apart here.</p><div class="hc">${artists}</div>` : ''}`;
}

/** Week / Month / Year / All, and ‹ period › to step through them. */
function renderPeriod(s) {
  const pick = $('#periodPick');
  pick.hidden = !s || s.empty;
  if (pick.hidden) return;
  const kind = s.range.kind === '12m' ? 'year' : s.range.kind;
  $$('#periodKind button').forEach((b) => b.classList.toggle('on', b.dataset.k === kind));
  $('#pLabel').textContent = periodName(s.range);
  $('#pPrev').disabled = !s.range.prev;
  $('#pNext').disabled = !s.range.next;
  renderGenreRow();
}

function deltaHtml(cur, prev, kind, running) {
  if (prev == null || !(prev > 0)) return '';
  const p = Math.round(((cur - prev) * 100) / prev);
  const vs = running ? vsWord({ kind, running }) : lastWord(kind);
  return `<span class="delta">${icon(p >= 0 ? 'trend-up' : 'down-trend')}<b>${p >= 0 ? '+' : ''}${p}%</b>&nbsp;vs ${esc(vs)}</span>`;
}
const tile = (label, value, sub, delta, ic, attrs) => {
  const inner = `<span class="tl">${ic ? `<i class="tile-ic">${icon(ic)}</i>` : ''}${esc(label)}</span><b>${esc(value)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}${delta || ''}`;
  return attrs ? `<button class="tile go" ${attrs}>${inner}<i class="tile-go">${icon('chevron-right')}</i></button>` : `<div class="tile">${inner}</div>`;
};
/** A tile's way into the whole top list of [kind] in period [range], with the page's own song, album or artist marked there. */
const rankAttrs = (kind, range, f = {}) => `data-ranks="${kind}" data-rrange="${esc(range)}" data-ra="${esc(f.artist || '')}" data-rt="${esc(f.title || '')}" data-ral="${esc(f.album || '')}" data-raa="${esc(f.albumArtist || '')}"`;

function storyCardHtml(s) {
  if (!s.summary.scrobbles) return '';
  const name = periodName(s.range);
  const al = (s.top.albums || []).find((x) => x.cover) || {};
  // The colors the story itself opens with: the top album's, once its palette is known.
  const c = al.cover && TINTS[al.cover];
  const h1 = c ? hexHsl(c.vibrant)[0] : hue(name + ' story');
  return `<button class="story-card" data-story="${esc(s.range.id)}" style="--s1:${h1.toFixed(0)};--s2:${((h1 + 55) % 360).toFixed(0)}">
    <div class="spinner-disc" style="--h:${hue(name)}"><i style="${al.cover ? `background-image:url('${cssUrl(al.cover)}')` : ''}"></i></div>
    <div class="k">${s.range.kind === 'all' ? 'Your whole history' : `Your ${periodWord(s.range.kind)}`}${s.genre || s.decade ? ` ${esc(filterWords(s.genre, s.decade))}` : ''}</div>
    <h2>${esc(name)}, on record</h2>
    <p>Your top artist, ${ofThe(s.range.kind, 'song')}, listening personality and more.</p>
    <span class="go">${icon('play')}Play the story</span></button>`;
}

/** One observation in words, with its icon. */
function insightText(x, kind, running) {
  switch (x.kind) {
    case 'volume': return [x.pct >= 0 ? 'trend-up' : 'down-trend', `You listened <b>${Math.abs(x.pct)}% ${x.pct >= 0 ? 'more' : 'less'}</b> than ${running ? `at this point ${lastWord(kind)}` : lastWord(kind)}: ${num(x.count)} plays against ${num(x.prev)}.`];
    case 'stillTop': return ['star', `<b>${esc(x.artist)}</b> stays on top: your number one ${lastWord(kind)} as well.`];
    case 'topArtist': return ['heart', `<b>${esc(x.artist)}</b> made up ${pct(x.share)} of everything you played.`];
    case 'song': return ['repeat', `${ofThe(kind, 'Song')}: <b>${esc(x.track)}</b> by ${esc(x.artist)}, ${plural(x.plays, 'play')}.`];
    case 'peak': return ['schedule', `Your busiest day is <b>${DOW_LONG[x.dow].replace(/s$/, '')}</b>, your busiest hours <b>${pad(x.hour)}:00–${pad((x.hour + 2) % 24)}:00</b>.`];
    case 'night': return ['moon', `<b>${pct(x.share)}</b> of your plays came between 22:00 and 04:00.`];
    case 'weekend': return ['weekend', x.share >= 0.4 ? `Weekend listener: <b>${pct(x.share)}</b> of your plays fell on Saturdays and Sundays.` : `Mostly a weekday thing: only <b>${pct(x.share)}</b> on weekends.`];
    case 'discovery': return ['explore', `You found <b>${plural(x.count, 'new artist')}</b>. The one that stuck: <b>${esc(x.best)}</b>, ${plural(x.plays, 'play')}.`];
    case 'comeback': return ['history', `Welcome back, <b>${esc(x.artist)}</b>: your first plays since ${esc(dateLabel(x.lastBefore))}.`];
    case 'bigDay': return ['calendar', `Biggest day: <b>${esc(dayName(x.day))}</b>, ${plural(x.count, 'play')}.`];
    case 'session': {
      const dur = x.minutes >= 60 ? `${Math.floor(x.minutes / 60)} h${x.minutes % 60 ? ` ${x.minutes % 60} min` : ''}` : `${x.minutes} min`;
      return ['music', `Your longest session: <b>${dur}</b>${kind === 'day' ? '' : ` on ${esc(dayName(x.start))}`} from ${esc(fmtTime(x.start))}, ${plural(x.count, 'song')}${x.artist ? `, mostly <b>${esc(x.artist)}</b>` : ''}.`];
    }
    case 'albums': return ['album', x.count === 1 ? `You played <b>${esc(x.title)}</b> by ${esc(x.artist)} through.`
      : `<b>${plural(x.count, 'album')}</b> played through${x.times > 1 ? `, <b>${esc(x.title)}</b> ${x.times} times` : ''}.`];
    case 'genreShift': return ['chart', `More <button class="inline-link" data-genre="${esc(x.key)}" data-glabel="${esc(x.label)}">${esc(x.label)}</button> than usual: <b>${pct(x.share)}</b> of your plays, against ${pct(x.before)} in the year before.`];
    case 'decade': return ['history', x.current ? `Mostly new music: <b>${pct(x.share)}</b> of it came out in the ${x.decade}s.` : `A trip back: <b>${pct(x.share)}</b> of what you played came out in the <b>${x.decade}s</b>.`];
    case 'loop': return ['repeat', `On repeat: <b>${esc(x.track)}</b> by ${esc(x.artist)}, ${x.run} times in a row.`];
    case 'shelf': return ['big-grid', `Off your shelf: <b>${pct(x.share)}</b> of your plays were albums you own, most of all <b>${esc(x.title)}</b>.`];
    case 'streak': return ['fire', `Music on <b>${x.days} days in a row</b>, until ${esc(dateLabel(x.to))}.`];
    case 'vinyl': return ['album', `<b>${plural(x.count, 'track')}</b> came from your records, ${pct(x.share)} of your listening.`];
    default: return null;
  }
}
const INSIGHT_ORDER = ['comeback', 'discovery', 'genreShift', 'albums', 'loop', 'volume', 'session', 'decade', 'stillTop', 'shelf', 'vinyl', 'peak', 'night', 'streak', 'song', 'weekend', 'bigDay', 'topArtist'];

/** Where a highlight leads: the artist, song or album it names, the block it comes from, or the day. */
function insightLink(x, s) {
  const day = s.range.kind !== 'day';
  switch (x.kind) {
    case 'comeback': case 'stillTop': case 'topArtist': return x.artist ? `data-artist="${esc(x.artist)}"` : '';
    case 'song': case 'loop': return x.track ? trackAttrs(x.artist, x.track) : '';
    case 'albums': return x.title && x.artist ? albumAttrs(x.artist, x.title) : '';
    case 'discovery': return 'data-goblock="newArtists"';
    case 'streak': return 'data-goblock="streaks"';
    case 'decade': return x.decade ? `data-decade="${x.decade}"` : '';
    case 'bigDay': return day && x.day ? `data-day="${periodId('day', x.day)}"` : '';
    case 'session': return day && x.start ? `data-day="${periodId('day', x.start)}"` : '';
    default: return '';
  }
}
function highlightsViz(s) {
  // A song on repeat that is also the most played one is said once.
  const loop = (s.insights || []).find((x) => x.kind === 'loop');
  const live = liveInsights(s.concerts || [], s.range.kind);
  const list = live.concat((s.insights || []).filter((x) => !(x.kind === 'song' && loop && loop.track === x.track && loop.artist === x.artist))
    .sort((a, b) => INSIGHT_ORDER.indexOf(a.kind) - INSIGHT_ORDER.indexOf(b.kind)).slice(0, 6 - live.length)
    .map((x) => { const t = insightText(x, s.range.kind, s.range.running); return t && [...t, insightLink(x, s)]; }).filter(Boolean));
  if (!list.length) return '';
  // A sentence with a link of its own inside (a genre, a concert) stays a sentence; the others open what they name.
  return `<div class="viz"><h2>Highlights</h2>${list.map(([ic, text, go]) => (go && !text.includes('<button')
    ? `<button class="hl go" ${go}><span class="hl-ic">${icon(ic)}</span><span>${text}</span>${icon('chevron-right', 'i hl-go')}</button>`
    : `<div class="hl"><span class="hl-ic">${icon(ic)}</span><span>${text}</span></div>`)).join('')}</div>`;
}

/** Weekday × hour heatmap: one hue (the wallpaper's primary), darker = more plays. */
function clockViz(clock, id, title) {
  const max = Math.max(1, ...clock);
  let cells = '';
  for (let d = 0; d < 7; d++) {
    cells += `<span class="rl">${DOW[d].charAt(0)}</span>`;
    for (let hr = 0; hr < 24; hr++) {
      const v = clock[d * 24 + hr];
      const mix = v ? Math.round(14 + 86 * Math.sqrt(v / max)) : 0;
      cells += `<i data-heat="${id}" data-d="${d}" data-h="${hr}" data-v="${v}"${v ? ` style="background:color-mix(in srgb,var(--md-primary) ${mix}%,var(--md-surface-container-high))"` : ''}></i>`;
    }
  }
  cells += '<span></span>' + [0, 6, 12, 18].map((hr) => `<span class="cl" style="grid-column:${hr + 2} / span 6">${pad(hr)}:00</span>`).join('');
  let peak = 0;
  clock.forEach((v, i) => { if (v > clock[peak]) peak = i; });
  const def = clock[peak] ? `Busiest: <b>${DOW[Math.floor(peak / 24)]}s, ${pad(peak % 24)}:00–${pad((peak % 24) + 1)}:00</b> (${num(clock[peak])} plays)` : 'No plays in this period';
  return `<div class="viz"><h2>${esc(title || 'When you listen')}</h2><span class="sub">Plays per weekday and hour</span>
    <div class="heat">${cells}</div><div class="readout" id="ro-${id}">${def}</div></div>`;
}

function streakViz(st, s) {
  const range = (a, b) => (a === b ? dateLabel(a) : new Date(a).getFullYear() === new Date(b).getFullYear()
    ? `${new Date(a).toLocaleDateString([], { day: 'numeric', month: 'short' })} to ${dateLabel(b)}` : `${dateLabel(a)} to ${dateLabel(b)}`);
  const cur = s.range.to * 1000 > Date.now() - 2000;
  let h = `<div class="tiles">${cur ? tile('Current streak', plural(st.current, 'day'), st.current ? `since ${dateLabel(st.currentFrom)}` : 'no music today or yesterday') : ''}
    ${tile(s.range.kind === 'all' ? 'Longest streak' : `Longest this ${periodWord(s.range.kind)}`, plural(st.longest, 'day'), st.longest ? range(st.longestFrom, st.longestTo) : '')}</div>`;
  if (st.artists && st.artists.length) {
    h += `<div class="viz"><h2>Artist streaks</h2><span class="sub">Most days in a row with the same artist</span><div class="rank">${st.artists.map((a, i) =>
      `<button class="r" data-artist="${esc(a.artist)}"><span class="n">${i + 1}</span><span class="t"><b>${esc(a.artist)}</b><small>${esc(range(a.from, a.to))}</small></span><span class="v">${plural(a.days, 'day')}</span></button>`).join('')}</div></div>`;
  }
  return h;
}

/** Plays per day, week or month across the period, with the average marked. */
function timelineViz(t) {
  const counts = (t && t.counts) || [];
  if (counts.length < 2) return '';
  const max = Math.max(1, ...counts);
  const step = Math.max(1, Math.ceil(counts.length / 5));
  const labels = t.starts.filter((x, i) => i % step === 0).map((x) => periodLabel(x, t.unit, true));
  let best = 0;
  counts.forEach((v, i) => { if (v > counts[best]) best = i; });
  if (counts.length > 16) {
    return `<div class="viz"><h2>Listening over time</h2><span class="sub">Plays per ${t.unit}</span>
      ${areaViz(counts, { col: 'tl', avg: t.avg, peakLabel: (i) => `${esc(periodLabel(t.starts[i], t.unit, true))}: ${num(counts[i])}` })}
      <div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div>
      <div class="readout" id="ro-tl">Most: <b>${esc(periodLabel(t.starts[best], t.unit))}</b>, ${plural(counts[best], 'play')}</div></div>`;
  }
  return `<div class="viz"><h2>Listening over time</h2><span class="sub">Plays per ${t.unit}</span>
    <div class="cols"><span class="ymax">${num(max)}</span>${counts.map((v, i) => `<div class="c" data-col="tl" data-i="${i}"><i style="height:${(v / max) * 100}%"></i></div>`).join('')}
      <div class="avg" style="bottom:${(t.avg / max) * 100}%"><span>avg ${t.avg}</span></div></div>
    <div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div>
    <div class="readout" id="ro-tl">Most: <b>${esc(periodLabel(t.starts[best], t.unit))}</b>, ${plural(counts[best], 'play')}</div></div>`;
}

/** A smooth line through [pts] ([x, y] pairs): Catmull-Rom, as cubic Béziers (no "M"). */
function smoothThrough(pts) {
  let d = '';
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(pts.length - 1, i + 2)];
    d += ` C${(p1[0] + (p2[0] - p0[0]) / 6).toFixed(1)},${(p1[1] + (p2[1] - p0[1]) / 6).toFixed(1)} ${(p2[0] - (p3[0] - p1[0]) / 6).toFixed(1)},${(p2[1] - (p3[1] - p1[1]) / 6).toFixed(1)} ${p2[0].toFixed(1)},${p2[1].toFixed(1)}`;
  }
  return d;
}
/** [a] softened over its neighbors (a Gaussian [s] bins wide). */
function soften(a, s) {
  if (s <= 0) return a.slice();
  return a.map((v, i) => {
    let t = 0, n = 0;
    for (let j = i - 3; j <= i + 3; j++) {
      if (j < 0 || j >= a.length) continue;
      const k = Math.exp(-((j - i) ** 2) / (2 * s * s));
      t += a[j] * k;
      n += k;
    }
    return t / n;
  });
}
/**
 * The river: plays per bin flowing around a middle line, everyone else in grey through the
 * middle and the top artists (in their record colors) above and below it, in the order they
 * peaked, each named where it is widest. A long break shows as a pinch, with its length.
 */
/**
 * The river: [rv]'s parts as streams (artists on the Stats tab; songs or albums on a page, with
 * [o]: title, sub(top share), link(name) for the legend, other for the grey).
 */
function riverViz(rv, o = {}) {
  const B = (rv && rv.starts) ? rv.starts.length : 0;
  if (B < 2 || !rv.artists.length) return '';
  const W = 340, H = 190, mid = H / 2;
  const sig = B > 24 ? 1.1 : B > 10 ? 0.6 : 0;
  const S = rv.series.map((s, k) => ({ v: soften(s, sig), color: INKS[k], name: rv.artists[k] }));
  const O = soften(rv.other, sig);
  const totals = Array.from({ length: B }, (_, b) => O[b] + S.reduce((a, s) => a + s.v[b], 0));
  const scale = (H - 16) / Math.max(1, ...totals);
  const x = (b) => (b / (B - 1)) * W;
  // Inside out: by when each artist peaked, alternately above and below the grey.
  const peak = (s) => s.v.indexOf(Math.max(...s.v));
  const byPeak = [...S].sort((p, q) => peak(p) - peak(q));
  const above = byPeak.filter((_, i) => i % 2 === 0), below = byPeak.filter((_, i) => i % 2 === 1);
  const stack = [...below.reverse(), { v: O, color: 'var(--md-outline-variant)', grey: true }, ...above];
  let lo = totals.map((t) => mid - (t * scale) / 2);
  let paths = '', labels = '';
  const placed = [];
  for (const L of stack) {
    const hi = lo.map((y, b) => y + L.v[b] * scale);
    const top = hi.map((y, b) => [x(b), y]), bot = lo.map((y, b) => [x(b), y]).reverse();
    paths += `<path d="M${top[0][0].toFixed(1)},${top[0][1].toFixed(1)}${smoothThrough(top)} L${bot[0][0].toFixed(1)},${bot[0][1].toFixed(1)}${smoothThrough(bot)}Z" fill="${L.color}"/>`;
    if (!L.grey) {
      const b = L.v.indexOf(Math.max(...L.v)), thick = L.v[b] * scale;
      if (thick > 8) {
        const cx = x(b), cy = (lo[b] + hi[b]) / 2, lw = L.name.length * 5.4 + 4;
        const anchor = cx < lw / 2 + 2 ? 'start' : cx > W - lw / 2 - 2 ? 'end' : 'middle';
        const bx = anchor === 'start' ? cx : anchor === 'end' ? cx - lw : cx - lw / 2;
        // Only where it doesn't run into another name.
        if (!placed.some((p) => bx < p[0] + p[2] && p[0] < bx + lw && Math.abs(p[1] - cy) < 12)) {
          placed.push([bx, cy, lw]);
          labels += `<text class="rv-name" x="${cx.toFixed(1)}" y="${(cy + 3.5).toFixed(1)}" text-anchor="${anchor}">${esc(L.name)}</text>`;
        }
      }
    }
    lo = hi;
  }
  // Breaks: three bins or more without a play, with how long they were.
  const raw = Array.from({ length: B }, (_, b) => rv.other[b] + rv.series.reduce((a, s) => a + s[b], 0));
  let gaps = '';
  for (let b = 0; b < B;) {
    if (raw[b] > 0) { b++; continue; }
    let e = b;
    while (e + 1 < B && raw[e + 1] === 0) e++;
    if (e - b >= 2 && b > 0 && e < B - 1) {
      const days = Math.round((rv.starts[e + 1] - rv.starts[b]) / 864e5);
      gaps += `<text class="rv-gap" x="${x((b + e) / 2).toFixed(1)}" y="${mid - 8}" text-anchor="middle">${days} days off</text>`;
    }
    b = e + 1;
  }
  const cw = W / (B - 1);
  const hits = rv.starts.map((s, b) => `<rect class="hit" data-rv="${b}" x="${Math.max(0, x(b) - cw / 2)}" y="0" width="${cw}" height="${H}"/>`).join('');
  const others = rv.other.some((v) => v > 0);
  const legend = rv.artists.map((n, k) => `<button class="ink" ${o.link ? o.link(n) : `data-artist="${esc(n)}"`}><i class="sw" style="background:${INKS[k]}"></i><span>${esc(n)}</span></button>`).join('')
    + (others ? `<span class="ink"><i class="sw" style="background:var(--md-outline-variant)"></i><span>${esc(o.other || 'Everyone else')}</span></span>` : '');
  const topN = rv.series.reduce((a, s) => a + s.reduce((p, q) => p + q, 0), 0), all = topN + rv.other.reduce((p, q) => p + q, 0);
  const share = Math.round((topN / Math.max(1, all)) * 100);
  // A page's river spans years: its ends say which.
  const edge = (ms) => (o.title && rv.unit !== 'year' ? new Date(ms).toLocaleDateString([], { month: 'short', year: 'numeric' }) : periodLabel(ms, rv.unit, true));
  return `<div class="viz" data-other="${esc((o.other || 'everyone else').toLowerCase())}"><h2>${esc(o.title || 'The river')}</h2>
    <span class="sub">${o.sub ? o.sub(share) : `Plays per ${rv.unit}, flowing around everyone else (grey). Your top ${rv.artists.length} are <b>${share}%</b> of it.`}</span>
    <div class="river"><svg viewBox="0 0 ${W} ${H + 14}">${paths}${labels}${gaps}<line class="cursor" x1="0" x2="0" y1="0" y2="${H}" visibility="hidden"/>${hits}
      <text x="0" y="${H + 12}">${esc(edge(rv.starts[0]))}</text><text x="${W}" y="${H + 12}" text-anchor="end">${esc(edge(rv.starts[B - 1]))}</text></svg></div>
    <div class="readout">Tap the river for a ${rv.unit}.</div><div class="inks">${legend}</div></div>`;
}
function riverText(rv, b, otherWord = 'everyone else') {
  const parts = rv.artists.map((n, k) => [n, rv.series[k][b]]).filter((p) => p[1] > 0).sort((p, q) => q[1] - p[1]).slice(0, 3)
    .map(([n, v]) => `${esc(n)} ${v}`);
  const total = rv.series.reduce((a, s) => a + s[b], 0) + rv.other[b];
  if (rv.other[b]) parts.push(`${esc(otherWord)} ${rv.other[b]}`);
  return `${esc(periodLabel(rv.starts[b], rv.unit))}: <b>${plural(total, 'play')}</b>${parts.length ? ` (${parts.join(', ')})` : ''}`;
}

/** New artists per day, week or month: columns in one hue; the start of your history is grayed. */
function newArtistsViz(na) {
  const b = na.buckets || [];
  if (b.length < 2) return '';
  if (!b.some((x) => x.count)) {
    return `<div class="viz"><h2>New artists</h2><span class="sub">No first listens in this stretch: you stuck with artists you already knew.</span></div>`;
  }
  const max = Math.max(1, ...b.filter((x) => !x.historyStart).map((x) => x.count));
  const cols = b.map((x, i) => `<div class="c ${x.historyStart ? 'start' : ''}" data-col="na" data-i="${i}"><i style="height:${Math.min(100, (x.count / max) * 100)}%"></i></div>`).join('');
  const step = Math.max(1, Math.ceil(b.length / 4));
  const labels = b.filter((x, i) => i % step === 0).map((x) => periodLabel(x.start, na.unit, true));
  const last = b[b.length - 1];
  const found = na.found || [];
  const head = na.count ? `<div class="na-big"><b>${num(na.count)}</b><span>${na.count === 1 ? 'new artist' : 'new artists'}${na.kept ? `, <b>${num(na.kept)}</b> you kept playing` : ''}</span></div>` : '';
  const finds = found.slice(0, 8);
  const row = finds.length ? `<h3 class="na-h">Your best finds</h3>
    <div class="na-row">${finds.map((f) => `<button class="na-find" data-artist="${esc(f.name)}">${artistFace(f)}<b>${esc(f.name)}</b><small>${esc(monthYear(f.first))}</small><small>${plural(f.plays, 'play')}</small></button>`).join('')}</div>
    ${found.length > 1 ? `<button class="btn tonal wide" data-newlist>All ${num(na.count)} new artists</button>` : ''}` : '';
  return `<div class="viz"><h2>New artists</h2><span class="sub">Artists you heard for the first time, per ${na.unit}</span>${head}
    <div class="cols"><span class="ymax">${num(max)}</span>${cols}</div><div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div>
    <div class="readout" id="ro-na">${newArtistsText(last, na.unit)}</div>${row}</div>`;
}
/** An artist's round photo, or their initial in their hue while there is none. */
function artistFace(f) {
  return f.photo ? `<span class="na-face"><img src="${esc(f.photo)}" alt=""></span>`
    : `<span class="na-face none" style="--h:${hue(f.name)}">${esc((f.name || '?').trim().charAt(0).toUpperCase())}</span>`;
}
/** Every new artist of the period, most played since first: when you found them and what you played right before. */
function openNewArtists() {
  const na = STATS && STATS.newArtists;
  if (!na || !na.found) return;
  const more = na.count > na.found.length ? `<p class="hint">The ${na.found.length} played most since; ${num(na.count - na.found.length)} more you heard only now and then.</p>` : '';
  openSheet(`<div class="sheet-title">${num(na.count)} new artists</div><p class="body">Most played first, with what you played right before you found them.</p>
    ${discoverOn() ? `<div class="btn-row"><button class="btn tonal" data-likethem>${icon('explore')}More like them</button></div>` : ''}
    <div class="na-list">${na.found.map((f) => `<button class="na-item" data-artist="${esc(f.name)}">${artistFace(f)}<span class="t"><b>${esc(f.name)}</b><small>first played ${esc(dateLabel(f.first))}${f.from ? `, after ${esc(f.from)}` : ''}</small></span><span class="v">${num(f.plays)}<small>${f.plays === 1 ? 'play' : 'plays'}</small></span></button>`).join('')}</div>${more}`);
}

/**
 * Habits: a row per artist, a tick for each week (or day) they were played in, taller for more
 * plays; most often played first. A steady habit fills its row, a wave crowds into one stretch.
 */
function habitsViz(hb) {
  const rows = (hb && hb.rows) || [];
  const B = hb && hb.starts ? hb.starts.length : 0;
  if (rows.length < 3 || B < 6) return '';
  const unitWord = { day: 'days', week: 'weeks', month: 'months', quarter: 'quarters', year: 'years' }[hb.unit] || 'weeks';
  const W = 340, nameW = 104, colW = 58, rh = 20, top = 16;
  const w = W - nameW - colW - 6;
  const X = (i) => nameW + 6 + (i / B) * w;
  const bw = Math.max(1, Math.min(4, w / B - 0.4));
  let s = `<text class="hb-head" x="${W}" y="10" text-anchor="end">${unitWord}, peak</text>`;
  for (const i of hb.empty || []) s += `<rect class="hb-off" x="${X(i).toFixed(1)}" y="${top}" width="${(w / B + 0.4).toFixed(2)}" height="${rows.length * rh}"/>`;
  rows.forEach((r, j) => {
    const y = top + j * rh;
    const col = r.ink >= 0 ? INKS[r.ink] : 'var(--md-on-surface-variant)';
    const name = r.name.length > 16 ? r.name.slice(0, 15) + '…' : r.name;
    s += `<text class="hb-name${r.ink >= 0 ? ' top' : ''}" x="${nameW}" y="${y + 13}" text-anchor="end" data-artist="${esc(r.name)}">${esc(name)}</text>`;
    r.counts.forEach((n, i) => {
      if (!n) return;
      const hh = Math.min(16, 3 + Math.sqrt(n) * 2.2);
      s += `<rect x="${X(i).toFixed(1)}" y="${(y + 10 - hh / 2).toFixed(1)}" width="${bw.toFixed(2)}" height="${hh.toFixed(1)}" rx="${Math.min(0.8, bw / 2)}" fill="${col}"${r.ink >= 0 ? '' : ' opacity=".55"'}/>`;
    });
    s += `<text class="hb-n" x="${W - 32}" y="${y + 13}" text-anchor="end">${r.present}</text><text class="hb-p" x="${W}" y="${y + 13}" text-anchor="end">${Math.round(r.peakShare * 100)}%</text>`;
  });
  const H = top + rows.length * rh;
  const steady = [...rows].sort((a, b) => b.present - a.present)[0];
  const wave = rows.filter((r) => r !== steady && r.plays >= 15 && r.peakShare >= 0.4).sort((a, b) => b.peakShare - a.peakShare)[0];
  const month = (ms) => new Date(ms).toLocaleDateString([], { month: 'long', year: 'numeric' });
  const sub = `<b>${esc(steady.name)}</b> is your steadiest habit: played in ${steady.present} of the ${hb.active} ${unitWord} with music.`
    + (wave ? ` <b>${esc(wave.name)}</b> came in a wave: ${Math.round(wave.peakShare * 100)}% of their plays fell in ${esc(month(wave.peakMonth))}.` : '');
  return `<div class="viz habits"><h2>Habits</h2><span class="sub">${sub}</span>
    <svg class="hb" viewBox="0 0 ${W} ${H + 16}">${s}<text class="hb-ax" x="${nameW + 6}" y="${H + 13}">${esc(periodLabel(hb.starts[0], hb.unit, true))}</text><text class="hb-ax" x="${nameW + 6 + w}" y="${H + 13}" text-anchor="end">${esc(periodLabel(hb.starts[B - 1], hb.unit, true))}</text></svg>
    <p class="hint">A tick for each ${hb.unit} with a play, taller for more. Right: in how many ${unitWord}, and how much of their plays their busiest month holds.</p></div>`;
}
function newArtistsText(x, unit) {
  if (!x) return '';
  const who = x.top && x.top.length ? ` (${esc(x.top.join(', '))})` : '';
  return `${esc(periodLabel(x.start, unit))}: <b>${plural(x.count, 'new artist')}</b>${who}${x.historyStart ? ' (your history starts here, so everything is new)' : ''}`;
}

/** Genre shares over time: 100% stacked columns, fixed colors per genre, legend with the period's shares. */
function genreViz(g) {
  const periods = g.periods || [];
  if (!periods.length || !g.names || g.names.length < 2) return '';
  const n = g.names.length;
  const totals = new Array(n).fill(0);
  let all = 0;
  periods.forEach((p) => { p.shares.forEach((s, k) => { totals[k] += s * p.total; }); all += p.total; });
  if (!all) return '';
  const color = (k) => (k < n - 1 ? GENRE_COLORS[k] : 'var(--md-outline-variant)');
  const cols = periods.map((p, i) => {
    if (!p.total) return `<div class="c empty" data-col="g" data-i="${i}"></div>`;
    return `<div class="c" data-col="g" data-i="${i}">${p.shares.map((s, k) => (s > 0 ? `<i style="flex:${s} 1 0;background:${color(k)}"></i>` : '')).join('')}</div>`;
  }).join('');
  const step = Math.max(1, Math.ceil(periods.length / 4));
  const labels = periods.filter((x, i) => i % step === 0).map((p) => periodLabel(p.start, g.unit, true));
  const legend = g.names.map((name, k) => {
    const key = (g.keys || [])[k];
    const open = key && key !== 'other' ? ` data-genre="${esc(key)}" data-glabel="${esc(name)}"` : '';
    return `<div${open}><span class="sw" style="background:${color(k)}"></span><span class="nm">${esc(name)}</span><span class="pc">${Math.round((totals[k] / all) * 100)}%</span></div>`;
  }).join('');
  const cov = g.coverage || {};
  const last = periods.length - 1;
  return `<div class="viz"><h2>Genre drift</h2><span class="sub">Share of plays per ${g.unit}</span>
    <div class="stack">${cols}</div><div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div>
    <div class="readout" id="ro-g">${genreText(g, last)}</div>
    <div class="legend">${legend}</div>
    <p class="hint">One set of genres for all your music: the Discogs styles of records you own and each artist's Last.fm tags are sorted into it and weighed together (mostly Discogs for ${pct(cov.discogs)} of plays, mostly Last.fm for ${pct(cov.lastfm)}); unknown for ${pct(cov.none)}.</p></div>`;
}
function genreText(g, i) {
  const p = g.periods[i];
  if (!p || !p.total) return p ? `${esc(periodLabel(p.start, g.unit))}: no known genres` : '';
  const parts = p.shares.map((s, k) => [s, k]).filter(([s]) => s > 0).sort((a, b) => b[0] - a[0]).slice(0, 3)
    .map(([s, k], j) => (j === 0 ? `<b>${esc(g.names[k])} ${Math.round(s * 100)}%</b>` : `${esc(g.names[k])} ${Math.round(s * 100)}%`));
  return `${esc(periodLabel(p.start, g.unit))}: ${parts.join(', ')}`;
}

function obsessionsViz(list, loops) {
  list = list || [];
  loops = loops || [];
  if (!list.length && !loops.length) return '';
  let h = '<div class="viz"><h2>Obsessions</h2>';
  if (list.length) {
    h += `<span class="sub">A track on repeat: most plays in one week</span><div class="rank">${list.map((o, i) =>
      `<button class="r" ${trackAttrs(o.artist, o.track)}><span class="n">${i + 1}</span><span class="t"><b>${esc(o.track)}</b><small>${esc(o.artist)}, week of ${esc(dateLabel(o.week))}</small></span><span class="v">${num(o.count)}×</span></button>`).join('')}</div>`;
  }
  if (loops.length) {
    h += `<span class="sub" style="margin-top:${list.length ? 20 : 0}px">Back to back: the same song again and again, nothing in between</span><div class="rank">${loops.map((o, i) =>
      `<button class="r" ${trackAttrs(o.artist, o.track)}><span class="n">${i + 1}</span><span class="t"><b>${esc(o.track)}</b><small>${esc(o.artist)}, ${esc(dateLabel(o.at))}</small></span><span class="v">${o.run}× in a row</span></button>`).join('')}</div>`;
  }
  return h + '</div>';
}

// ---- the record: every play as a dot on a disc ----

/** Record charts on screen: id -> {rec, spot (artist index or -1), label}. */
const RECS = {};

function recordViz(rec, id, title, sub, opts = {}) {
  if (!rec || !rec.days) return '';
  const st = RECS[id];
  let h = `<div class="viz"><h2>${esc(title)}</h2><span class="sub">${esc(sub || 'Every play is a dot. Each groove is a day, from the label to the rim; noon is at the top, midnight at the bottom. Your top artists have their own colors.')}</span>
    <div class="rec-wrap${st && st.z > 1 ? ' zoomed' : ''}"><canvas data-record="${id}"></canvas>
      <div class="rec-zoom"><button class="icon-btn small" data-rzoom="in" data-rec="${id}" aria-label="Zoom in">+</button><button class="icon-btn small" data-rzoom="out" data-rec="${id}" aria-label="Zoom out">−</button><button class="icon-btn small rz-reset" data-rzoom="reset" data-rec="${id}" aria-label="Whole record">1×</button></div>
      <span class="rec-hint">Pinch or double-tap to zoom in</span></div>
    <div class="readout" id="ro-rec-${id}">${st && st.spot >= 0 ? spotText(id) : 'Tap a name below, search, or tap a dot to light an artist up.'}</div>`;
  if (opts.legend !== false) {
    h += `<div class="inks" id="inks-${id}">${rec.artists.slice(0, 8).map((n, k) => `<button class="ink ${st && st.spot === k ? 'on' : ''}" data-ink="${id}" data-k="${k}"><i class="sw" style="background:${INKS[k]}"></i><span>${esc(n)}</span><small>${compact(rec.plays[k])}</small></button>`).join('')}</div>
      <div class="asearch">${icon('search')}<input type="search" placeholder="Find an artist on the record" data-asearch="${id}" autocomplete="off" spellcheck="false"><div class="sugg" hidden></div></div>`;
  }
  return h + '</div>';
}

function spotText(id) {
  const st = RECS[id];
  const name = st.rec.artists[st.spot];
  return `Showing <button class="artist-link" data-artist="${esc(name)}">${esc(name)}</button>: ${plural(st.rec.plays[st.spot], 'play')}. Tap the label to show everyone.`;
}

function setSpot(id, k) {
  const st = RECS[id];
  if (!st) return;
  st.spot = st.spot === k ? -1 : k;
  $$(`canvas[data-record="${id}"]`).forEach(drawRecord);
  const ro = $('#ro-rec-' + id);
  if (ro) ro.innerHTML = st.spot >= 0 ? spotText(id) : 'Tap a name below, search, or tap a dot to light an artist up.';
  $$(`[data-ink="${id}"]`).forEach((b) => b.classList.toggle('on', +b.dataset.k === st.spot));
}

function themeColor(name) { return getComputedStyle(document.documentElement).getPropertyValue('--md-' + name).trim(); }

function drawRecords() { $$('canvas[data-record]').forEach(drawRecord); }

function recordGeometry(c, rec) {
  const W = c.width, R = W / 2 - W * 0.01;
  const r0 = R * 0.25, r1 = R - W * 0.045;
  return { cx: W / 2, cy: W / 2, R, r0, r1, step: (r1 - r0) / rec.days };
}
function recordPos(g, rec, i) {
  const r = g.r0 + (rec.d[i] + 0.5) * g.step;
  const ang = Math.PI / 2 + (rec.m[i] / 1440) * 2 * Math.PI; // midnight at the bottom, noon at the top
  return [g.cx + r * Math.cos(ang), g.cy + r * Math.sin(ang)];
}

/**
 * Where a record is looked at: zoom [z] (1 to 8) around [vx, vy] (fractions of the canvas), as a
 * transform from the record's own pixels to the screen's.
 */
function recordView(st, W) {
  const z = st.z || 1, vx = (st.vx == null ? 0.5 : st.vx) * W, vy = (st.vy == null ? 0.5 : st.vy) * W;
  return { z, vx, vy, toWorld: (sx, sy) => [vx + (sx - W / 2) / z, vy + (sy - W / 2) / z] };
}
/** Zooms record [id] to [z], keeping the point at canvas pixel [fx, fy] where it is (the middle by default). */
function zoomRecord(id, z, fx, fy) {
  const st = RECS[id];
  const c = $(`canvas[data-record="${id}"]`);
  if (!st || !c) return;
  const W = c.width, v = recordView(st, W);
  if (fx == null) { fx = W / 2; fy = W / 2; }
  const [wx, wy] = v.toWorld(fx, fy);
  const nz = Math.min(8, Math.max(1, z));
  st.z = nz;
  panRecord(st, W, wx - (fx - W / 2) / nz, wy - (fy - W / 2) / nz);
  drawRecord(c);
  c.closest('.rec-wrap').classList.toggle('zoomed', st.z > 1);
}
/** Moves the view of [st] to look at [x, y] (record pixels), kept on the record. */
function panRecord(st, W, x, y) {
  if (st.z <= 1.02) { st.z = 1; st.vx = st.vy = 0.5; return; }
  const half = W / 2 / st.z;
  st.vx = Math.min(W - half, Math.max(half, x)) / W;
  st.vy = Math.min(W - half, Math.max(half, y)) / W;
}

function drawRecord(c) {
  const st = RECS[c.dataset.record];
  if (!st) return;
  const rec = st.rec;
  const dpr = Math.min(3, window.devicePixelRatio || 1);
  c.width = Math.round((c.clientWidth || 300) * dpr);
  c.height = c.width;
  const ctx = c.getContext('2d');
  const g = recordGeometry(c, rec);
  const v = recordView(st, c.width), z = v.z;
  ctx.setTransform(z, 0, 0, z, c.width / 2 - v.vx * z, c.width / 2 - v.vy * z);
  // the vinyl, with faint grooves
  ctx.fillStyle = '#121214';
  ctx.beginPath(); ctx.arc(g.cx, g.cy, g.R, 0, 2 * Math.PI); ctx.fill();
  ctx.strokeStyle = 'rgba(255,255,255,.035)';
  ctx.lineWidth = dpr / z;
  for (let r = g.r0; r < g.R; r += 3 * dpr) { ctx.beginPath(); ctx.arc(g.cx, g.cy, r, 0, 2 * Math.PI); ctx.stroke(); }
  // Zoomed in: the hours as faint spokes.
  if (z > 1.5) {
    ctx.strokeStyle = 'rgba(255,255,255,.07)';
    for (let hr = 0; hr < 24; hr++) {
      const a = Math.PI / 2 + (hr / 24) * 2 * Math.PI;
      ctx.beginPath(); ctx.moveTo(g.cx + g.r0 * Math.cos(a), g.cy + g.r0 * Math.sin(a)); ctx.lineTo(g.cx + g.r1 * Math.cos(a), g.cy + g.r1 * Math.sin(a)); ctx.stroke();
    }
  }
  // the plays: everyone else first, the eight inks on top, a spotlit artist above all (zoomed in, the dots get room)
  const ds = Math.max(1.4 * dpr, Math.min(3 * dpr, g.step * 1.3)) * (1 + (z - 1) * 0.3) / z;
  const n = rec.d.length;
  const spot = st.spot;
  const pass = (test, color, alpha, size) => {
    ctx.fillStyle = color;
    ctx.globalAlpha = alpha;
    for (let i = 0; i < n; i++) {
      if (!test(rec.a[i])) continue;
      const [x, y] = recordPos(g, rec, i);
      ctx.fillRect(x - size / 2, y - size / 2, size, size);
    }
  };
  if (spot < 0) {
    pass((a) => a >= 8, '#9a99a3', 0.4, ds);
    for (let k = 7; k >= 0; k--) pass((a) => a === k, INKS_ON_VINYL[k], 0.92, ds);
  } else {
    pass((a) => a !== spot, '#9a99a3', 0.16, ds);
    pass((a) => a === spot, spot < 8 ? INKS_ON_VINYL[spot] : '#ffffff', 1, ds * 1.5);
  }
  ctx.globalAlpha = 1;
  // The play tapped last, ringed.
  if (st.sel >= 0 && st.sel < n) {
    const [x, y] = recordPos(g, rec, st.sel);
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 1.6 * dpr / z;
    ctx.beginPath(); ctx.arc(x, y, ds * 1.6 + 2 * dpr / z, 0, 2 * Math.PI); ctx.stroke();
  }
  // the label
  ctx.fillStyle = themeColor('primary-container') || '#4f378b';
  ctx.beginPath(); ctx.arc(g.cx, g.cy, g.r0 - 3 * dpr, 0, 2 * Math.PI); ctx.fill();
  ctx.fillStyle = '#121214';
  ctx.beginPath(); ctx.arc(g.cx, g.cy, 2.5 * dpr, 0, 2 * Math.PI); ctx.fill();
  ctx.fillStyle = themeColor('on-primary-container') || '#eaddff';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  const lab = spot >= 0 ? rec.artists[spot] : st.label;
  // As big as fits on the label (a month's name, a long artist), down to 8 px, then cut short.
  const room = 2 * (g.r0 - 3 * dpr) - 14 * dpr;
  let size = 11, text = lab;
  const setFont = () => { ctx.font = `500 ${(size * dpr).toFixed(1)}px Roboto, system-ui, sans-serif`; };
  setFont();
  while (size > 8 && ctx.measureText(text).width > room) { size -= 0.5; setFont(); }
  if (ctx.measureText(text).width > room) {
    while (text.length > 1 && ctx.measureText(text + '…').width > room) text = text.slice(0, -1);
    text += '…';
  }
  ctx.fillText(text, g.cx, g.cy - 10 * dpr);
  ctx.font = `${Math.round(9 * dpr)}px Roboto, system-ui, sans-serif`;
  ctx.fillText(`${compact(spot >= 0 ? rec.plays[spot] : n)} plays`, g.cx, g.cy + 9 * dpr);
  // the clock around the rim (every hour once zoomed in)
  ctx.fillStyle = 'rgba(255,255,255,.5)';
  ctx.font = `${(9 * dpr / Math.sqrt(z)).toFixed(1)}px Roboto, system-ui, sans-serif`;
  const rt = g.R - (g.R - g.r1) / 2;
  if (z > 1.5) {
    for (let hr = 0; hr < 24; hr++) {
      const a = Math.PI / 2 + (hr / 24) * 2 * Math.PI;
      ctx.fillText(String(hr), g.cx + rt * Math.cos(a), g.cy + rt * Math.sin(a));
    }
  } else {
    [['12', 0, -1], ['18', 1, 0], ['0', 0, 1], ['6', -1, 0]].forEach(([t, x, y]) => ctx.fillText(t, g.cx + x * rt, g.cy + y * rt));
  }
  // Zoomed in: each groove's date, along the line from the middle of the record to the middle of the view.
  if (z > 1.5) {
    const toView = Math.hypot(v.vx - g.cx, v.vy - g.cy) > g.r0 ? Math.atan2(v.vy - g.cy, v.vx - g.cx) : -Math.PI * 0.75;
    const every = Math.max(1, Math.ceil((13 * dpr) / (g.step * z)));
    const s0 = new Date(rec.start);
    // Along each groove (across the line), kept the right way up.
    const along = toView + Math.PI / 2, flip = Math.cos(along) < 0;
    ctx.font = `${(9 * dpr / z).toFixed(2)}px 'DM Mono', monospace`;
    ctx.fillStyle = 'rgba(255,255,255,.62)';
    for (let d = 0; d < rec.days; d += every) {
      const r = g.r0 + (d + 0.5) * g.step;
      const x = g.cx + r * Math.cos(toView), y = g.cy + r * Math.sin(toView);
      ctx.save();
      ctx.translate(x, y);
      ctx.rotate(along + (flip ? Math.PI : 0));
      ctx.fillText(new Date(s0.getFullYear(), s0.getMonth(), s0.getDate() + d).toLocaleDateString([], { day: 'numeric', month: 'short' }), 0, 0);
      ctx.restore();
    }
  }
  ctx.setTransform(1, 0, 0, 1, 0, 0);
}

/** Tap on a record: the nearest play, its artist lit up across the disc. */
function onRecordTap(c, e) {
  const id = c.dataset.record;
  const st = RECS[id];
  if (!st) return;
  const rec = st.rec;
  const box = c.getBoundingClientRect();
  const k = c.width / box.width;
  const v = recordView(st, c.width);
  const [px, py] = v.toWorld((e.clientX - box.left) * k, (e.clientY - box.top) * k);
  const g = recordGeometry(c, rec);
  if (Math.hypot(px - g.cx, py - g.cy) < g.r0) { st.sel = -1; if (st.spot >= 0) setSpot(id, st.spot); else drawRecord(c); return; }
  let best = -1, bestD = (22 * k) / v.z;
  for (let i = 0; i < rec.d.length; i++) {
    const [x, y] = recordPos(g, rec, i);
    const d = Math.hypot(x - px, y - py);
    if (d < bestD) { bestD = d; best = i; }
  }
  if (best < 0) return;
  st.spot = -1;
  st.sel = best;
  setSpot(id, rec.a[best]);
  // Days on the calendar from the first groove's midnight (not 24-hour steps, which a DST change shifts).
  const s0 = new Date(rec.start);
  const when = new Date(s0.getFullYear(), s0.getMonth(), s0.getDate() + rec.d[best]);
  const name = rec.artists[rec.a[best]];
  const ro = $('#ro-rec-' + id);
  if (ro) {
    ro.innerHTML = `${esc(when.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' }))}, ${pad(Math.floor(rec.m[best] / 60))}:${pad(rec.m[best] % 60)}, <b>${esc(rec.tracks[rec.t[best]])}</b> by
      <button class="artist-link" data-artist="${esc(name)}">${esc(name)}</button> (${plural(rec.plays[rec.a[best]], 'play')}, lit up). Tap the label to show everyone.`;
  }
}

/**
 * Pinching a record zooms it, dragging moves it once zoomed (at its whole size the page scrolls as
 * usual), and a double tap zooms in on that spot, or back out.
 */
const recGesture = { pts: new Map(), c: null, pinch: null, drag: null, moved: false, lastTap: null, quietUntil: 0 };
function recPointer(e) {
  const g = recGesture;
  const canvasPx = (c, x, y) => { const b = c.getBoundingClientRect(), k = c.width / b.width; return [(x - b.left) * k, (y - b.top) * k]; };
  if (e.type === 'pointerdown') {
    const c = e.target.closest && e.target.closest('canvas[data-record]');
    if (!c) return;
    const st = RECS[c.dataset.record];
    if (!st) return;
    if (g.c !== c) { g.pts.clear(); g.c = c; }
    g.pts.set(e.pointerId, [e.clientX, e.clientY]);
    g.moved = false;
    if (g.pts.size === 2) {
      const [[ax, ay], [bx, by]] = [...g.pts.values()];
      g.pinch = { d: Math.hypot(ax - bx, ay - by), z: st.z || 1 };
      g.drag = null;
    } else if (g.pts.size === 1) {
      g.drag = { x: e.clientX, y: e.clientY, view: recordView(st, c.width) };
    }
    return;
  }
  if (!g.c || !g.pts.has(e.pointerId)) return;
  const c = g.c, id = c.dataset.record, st = RECS[id];
  if (!st) return;
  if (e.type === 'pointermove') {
    g.pts.set(e.pointerId, [e.clientX, e.clientY]);
    if (g.pts.size === 2 && g.pinch) {
      const [[ax, ay], [bx, by]] = [...g.pts.values()];
      const [mx, my] = canvasPx(c, (ax + bx) / 2, (ay + by) / 2);
      zoomRecord(id, g.pinch.z * (Math.hypot(ax - bx, ay - by) / g.pinch.d), mx, my);
      g.moved = true;
    } else if (g.pts.size === 1 && g.drag && (st.z || 1) > 1) {
      const k = c.width / c.getBoundingClientRect().width;
      const dx = (e.clientX - g.drag.x) * k, dy = (e.clientY - g.drag.y) * k;
      if (Math.hypot(dx, dy) > 6 * k) g.moved = true;
      if (g.moved) {
        panRecord(st, c.width, g.drag.view.vx - dx / g.drag.view.z, g.drag.view.vy - dy / g.drag.view.z);
        drawRecord(c);
      }
    }
    return;
  }
  // up or cancel
  g.pts.delete(e.pointerId);
  if (g.moved) { g.quietUntil = Date.now() + 350; if (!g.pts.size) g.pinch = null; return; }
  if (e.type !== 'pointerup' || g.pts.size) return;
  const now = Date.now(), last = g.lastTap;
  if (last && now - last.t < 320 && Math.hypot(e.clientX - last.x, e.clientY - last.y) < 30) {
    const [fx, fy] = canvasPx(c, e.clientX, e.clientY);
    zoomRecord(id, (st.z || 1) > 1.5 ? 1 : 3, fx, fy);
    g.lastTap = null;
    g.quietUntil = now + 350;
  } else {
    g.lastTap = { t: now, x: e.clientX, y: e.clientY };
  }
}
['pointerdown', 'pointermove', 'pointerup', 'pointercancel'].forEach((t) => document.addEventListener(t, recPointer, { passive: true }));

/** Finding an artist on the record: suggestions as you type. */
function onArtistSearch(input) {
  const id = input.dataset.asearch;
  const st = RECS[id];
  const box = input.parentElement.querySelector('.sugg');
  const q = norm(input.value.trim());
  if (!st || !q) { box.hidden = true; return; }
  const hits = [];
  st.rec.artists.forEach((n, k) => { if (hits.length < 6 && norm(n).includes(q)) hits.push(k); });
  box.innerHTML = hits.length ? hits.map((k) => `<button data-pick="${k}"><span>${esc(st.rec.artists[k])}</span><small>${plural(st.rec.plays[k], 'play')}</small></button>`).join('')
    : `<button disabled><span>Nobody by that name in this period</span></button>`;
  box.hidden = false;
}

// ---- phases: one ridge per artist, ordered by when they peaked ----

function pulseViz(p) {
  const rows = (p && p.rows) || [];
  if (rows.length < 2 || p.bins < 2) return '';
  const W = 340, labelW = 96, rowH = 19, peakH = 34, top = 30;
  const H = top + rows.length * rowH + 20;
  const max = Math.max(1, ...rows.flatMap((r) => r.counts));
  const sx = (i) => labelW + (i / (p.bins - 1)) * (W - labelW);
  const sy = (c) => Math.sqrt(c / max) * peakH;
  const trunc = (s) => (s.length > 15 ? s.slice(0, 14) + '…' : s);
  let svg = '';
  rows.forEach((r, k) => {
    const base = top + (k + 1) * rowH;
    const pts = r.counts.map((c, i) => `${sx(i).toFixed(1)},${(base - sy(c)).toFixed(1)}`).join('L');
    const area = `M${labelW},${base}L${pts}L${W},${base}Z`;
    svg += `<g class="row" data-ridge="${k}"><path class="bg" d="${area}"/><path class="fill" d="${area}"/><path class="line" d="M${pts}"/>
      <rect class="hit" x="${labelW}" y="${base - rowH}" width="${W - labelW}" height="${rowH}"/>
      <text class="nm" x="0" y="${base - 4}" data-artist="${esc(r.artist)}">${esc(trunc(r.artist))}</text></g>`;
  });
  svg += `<text x="${labelW}" y="${H - 4}">${esc(periodLabel(p.starts[0], p.unit, true))}</text><text x="${W}" y="${H - 4}" text-anchor="end">${esc(periodLabel(p.starts[p.bins - 1], p.unit, true))}</text>`;
  return `<div class="viz"><h2>Phases</h2><span class="sub">Plays per ${p.unit} of your ${rows.length} most played artists, ordered by when they peaked. Tap a name for the artist.</span>
    <div class="ridges"><svg viewBox="0 0 ${W} ${H}">${svg}</svg></div><div class="readout" id="ro-pulse">Tap a ridge for its peak.</div></div>`;
}

function pulseText(p, k) {
  const r = p.rows[k];
  return `<b>${esc(r.artist)}</b>: peaked ${p.unit === 'day' ? 'on' : 'in'} ${esc(periodLabel(p.starts[r.peak], p.unit))} with ${plural(r.counts[r.peak], 'play')}; ${plural(r.plays, 'play')} in this period`;
}

// ---- listening elsewhere: TIDAL, Spotify and co ----

/** Streaming services, searched for an artist, an album or a song. Spotify has its own app link; the others open web pages their apps may take over. */
const SERVICES = {
  tidal: { name: 'TIDAL', web: (q) => `https://tidal.com/search?q=${encodeURIComponent(q)}` },
  spotify: { name: 'Spotify', app: (q) => `spotify:search:${encodeURIComponent(q)}`, web: (q) => `https://open.spotify.com/search/${encodeURIComponent(q)}` },
  ytmusic: { name: 'YouTube Music', web: (q) => `https://music.youtube.com/search?q=${encodeURIComponent(q)}` },
  apple: { name: 'Apple Music', web: (q) => `https://music.apple.com/search?term=${encodeURIComponent(q)}` },
  deezer: { name: 'Deezer', web: (q) => `https://www.deezer.com/search/${encodeURIComponent(q)}` },
};
function services() {
  try {
    const v = JSON.parse(localStorage.getItem('services') || 'null');
    if (Array.isArray(v)) return v.filter((k) => SERVICES[k]);
  } catch (_) { /* preview without storage */ }
  return ['tidal', 'spotify'];
}
function setServices(list) {
  try { localStorage.setItem('services', JSON.stringify(list)); } catch (_) { /* preview without storage */ }
}
/** "Listen on" chips for a song ("tracks"), an album ("albums") or an artist ("artists"). */
function listenRow(kind, artist, title = '') {
  const sv = services();
  if (!sv.length) return '';
  const q = title ? `${artist} ${title}` : artist;
  return `<div class="listen">${sv.map((k) => `<button class="chip link" data-listen="${k}" data-q="${esc(q)}" data-kind="${kind}" data-a="${esc(artist)}" data-ti="${esc(title)}">${icon('play')}${esc(SERVICES[k].name)}</button>`).join('')}</div>`;
}
function openListen(el) {
  const k = el.dataset.listen, q = el.dataset.q;
  const s = SERVICES[k];
  if (!s) return;
  // With the user's TIDAL app set up, the exact song, album or artist; otherwise a search.
  if (k === 'tidal' && (ST.tidal || {}).hasApp) send({ t: 'tidalOpen', kind: el.dataset.kind, artist: el.dataset.a, title: el.dataset.ti, fallback: s.web(q) });
  else if (Native && s.app) send({ t: 'openApp', uri: s.app(q), url: s.web(q) });
  else openUrl(s.web(q));
}

/** Attributes that open a song or an album page when tapped (see the click handler in build). */
const trackAttrs = (artist, title, extra = {}) => `data-track="${esc(title)}" data-a="${esc(artist)}"${extra.album ? ` data-al="${esc(extra.album)}"` : ''}${extra.cover ? ` data-cover="${esc(extra.cover)}"` : ''}`;
const albumAttrs = (artist, title) => `data-album="${esc(title)}" data-a="${esc(artist)}"`;

const missingNote = () => ((ST.history || {}).count > 0
  ? 'Not in your Last.fm history on this phone yet. New scrobbles arrive here when you open the Stats tab.'
  : 'Import your Last.fm history on the Stats tab to see its story here.');

// ---- the look: the color glow behind the app, the back of the sleeve, the turntable ----

/** Where the glow behind the top of the app takes its colors: "records" (the record spinning, else the last one played), "wallpaper" (Material You) or "off". */
function ambientMode() {
  try { return localStorage.getItem('ambient') || 'records'; } catch (_) { return 'records'; }
}
/** Settings → Look → Style: 'soft' (raised shapes in the cover's color) or 'glass' (the cover behind frosted controls); see looks.css. */
function lookMode() {
  try { return localStorage.getItem('look') === 'glass' ? 'glass' : 'soft'; } catch (_) { return 'soft'; }
}
document.documentElement.dataset.look = lookMode();
let ambientUrl = '';
let ambientSet = '';
/** The mode and the glow's colors go on the root, so each write restyles the whole page: only when they change. */
function setAmbient(mode, colors) {
  const key = mode + '|' + colors.join('|');
  if (key === ambientSet) return;
  ambientSet = key;
  const root = document.documentElement;
  root.dataset.ambient = mode;
  colors.forEach((c, i) => root.style.setProperty(`--amb${i + 1}`, c));
}
function applyAmbient() {
  const mode = ambientMode();
  if (mode === 'off') { setAmbient(mode, []); return; }
  if (mode === 'wallpaper') { setAmbient(mode, ['var(--md-primary)', 'var(--md-tertiary)', 'var(--md-secondary)']); return; }
  const sp = ST.spin;
  const pn = sp ? null : (ST.phone || {}).now;
  const now = pn && pn.title && pn.playing ? pn : null;
  const e = sp || now ? null : (ACT || []).slice().sort((a, b) => b.ts - a.ts)[0];
  const src = sp ? { cover: sp.cover, seed: sp.album + sp.artist }
    : now ? { cover: now.cover || '', seed: (now.album || now.title) + now.artist }
      : e ? { cover: e.cover, seed: (e.album || e.track) + e.artist } : { cover: '', seed: 'Heavy Rotation' };
  ambientUrl = src.cover || '';
  const c = ambientUrl ? TINTS[ambientUrl] : null;
  if (ambientUrl && !c) send({ t: 'palette', url: ambientUrl });
  let h1, h2, sat;
  if (c) {
    const v = hexHsl(c.vibrant), d = hexHsl(c.dominant);
    [h1, h2, sat] = [v[0], d[0], Math.max(v[1], d[1])];
  } else [h1, h2, sat] = [hue(src.seed), (hue(src.seed) + 48) % 360, 0.6];
  const k = sat < 0.12 ? 0 : 1; // black-and-white covers glow gray
  setAmbient(mode, [`hsl(${h1.toFixed(0)} ${70 * k}% 46%)`, `hsl(${h2.toFixed(0)} ${60 * k}% 40%)`, `hsl(${((h1 + 320) % 360).toFixed(0)} ${55 * k}% 36%)`]);
}

/** Your record of an album (any edition, "Deluxe" or not), found on the shelf by artist and title; 0 if you don't own it. */
function shelfIdOf(artist, title) {
  const a = looseKey(artist), t = looseKey(title);
  if (!a || !t) return 0;
  const r = COLL.find((x) => looseKey(x.title) === t && (looseKey(x.artist) === a || looseKey(x.artist).startsWith(a + ' ') || a.startsWith(looseKey(x.artist) + ' ')));
  return r ? r.id : 0;
}

/** The backs of shelf records on album and song pages: their tracklist, once the release is loaded (asked for once). */
const relAsked = new Set();
function fillSleeveBacks() {
  $$('.holo[data-rel] .sleeve-back').forEach((sb) => {
    const id = +sb.closest('.holo').dataset.rel;
    if (!id || sb.closest('#recHolo')) return; // the record page fills its own
    const base = COLL.find((x) => x.id === id) || {};
    const r = REL[id];
    const rel = r && r.tracks ? r : null;
    if (!rel && !relAsked.has(id)) { relAsked.add(id); send({ t: 'openRelease', id }); }
    const html = sleeveBackInner(base, rel, (rel && rel.plays) || base.plays || 0);
    if (sb.dataset.filled !== html) { sb.innerHTML = html; sb.dataset.filled = html; }
  });
}

/**
 * Puts [html] into [el] changing only what differs: elements that stay keep their state, and
 * pictures already on screen aren't loaded and decoded again. (Rebuilding a whole page each time
 * a cover, a photo or a count arrived made its pictures blink.) Children are matched in order by
 * tag and id or data-key; a block put in or taken out is noticed (looking a few blocks ahead by
 * tag, class and heading), so the blocks after it still meet their old selves instead of each
 * being rebuilt into its neighbor. [keep](old, fresh) may claim an element and leave it as it is.
 */
function morph(el, html, keep) {
  const t = document.createElement('template');
  t.innerHTML = html;
  morphKids(el, t.content, keep);
}
const sameKind = (a, b) => a.nodeType === b.nodeType && a.nodeName === b.nodeName
  && (a.nodeType !== 1 || (a.id === b.id && a.getAttribute('data-key') === b.getAttribute('data-key')));
/** What tells blocks apart when one comes or goes: tag, id, key, class and a heading of their own. */
function blockSig(n) {
  const h = n.firstElementChild;
  return `${n.nodeName}#${n.id}|${n.getAttribute('data-key') || ''}|${n.getAttribute('class') || ''}|${h && /^H\d$/.test(h.nodeName) ? h.textContent : ''}`;
}
const LOOK_AHEAD = 12;
/** Whether an element with signature [s] comes up among the next few elements from [n] on. */
function comesUp(n, s) {
  for (let k = 0; n && k < LOOK_AHEAD; n = n.nextSibling) if (n.nodeType === 1) { if (blockSig(n) === s) return n; k++; }
  return null;
}
function morphKids(a, b, keep) {
  let x = a.firstChild, y = b.firstChild;
  while (y) {
    const next = y.nextSibling;
    if (!x) { a.appendChild(y); y = next; continue; }
    // Text and elements out of step: a new text goes in, an old one goes.
    if (y.nodeType !== 1 && x.nodeType === 1) { a.insertBefore(y, x); y = next; continue; }
    if (y.nodeType === 1 && x.nodeType !== 1) { const after = x.nextSibling; x.remove(); x = after; continue; }
    if (y.nodeType === 1 && blockSig(x) !== blockSig(y)) {
      const sx = blockSig(x), at = comesUp(x.nextSibling, blockSig(y));
      if (at && !comesUp(next, sx)) {
        // Blocks before [y]'s old self were taken out.
        while (x !== at) { const after = x.nextSibling; x.remove(); x = after; }
      } else if (comesUp(next, sx)) {
        // [y] is new: in it goes, and [x] waits for its own new self.
        a.insertBefore(y, x);
        y = next;
        continue;
      }
    }
    if (sameKind(x, y)) { morphNode(x, y, keep); x = x.nextSibling; }
    else { const after = x.nextSibling; a.replaceChild(y, x); x = after; }
    y = next;
  }
  while (x) { const after = x.nextSibling; x.remove(); x = after; }
}
function morphNode(x, y, keep) {
  if (x.nodeType !== 1) { if (x.nodeValue !== y.nodeValue) x.nodeValue = y.nodeValue; return; }
  if (keep && keep(x, y)) return;
  for (const { name } of [...x.attributes]) if (!y.hasAttribute(name)) x.removeAttribute(name);
  for (const { name, value } of y.attributes) if (x.getAttribute(name) !== value) x.setAttribute(name, value);
  morphKids(x, y, keep);
}

/**
 * Re-rendering a page keeps its moving sleeve (turned over or not, record out) when it shows the
 * same record; a better picture arriving is swapped into it instead of building a new one.
 */
function keepSleeve(old, fresh) {
  if (!old.hasAttribute('data-holo') || old.dataset.key !== fresh.dataset.key) return false;
  if (old.dataset.cover !== fresh.dataset.cover) {
    const oc = old.querySelector('.sleeve .cover'), nc = fresh.querySelector('.sleeve .cover');
    if (oc && nc) oc.replaceWith(nc);
    const ol = old.querySelector('.vinyl .lbl'), nl = fresh.querySelector('.vinyl .lbl');
    if (ol && nl) ol.setAttribute('style', nl.getAttribute('style') || '');
    old.dataset.cover = fresh.dataset.cover;
  }
  return true;
}
function keepHolo(body, html) {
  morph(body, html, keepSleeve);
  fillSleeveBacks();
  Tilt.refresh();
}

/** Turns a sleeve over; the tilt holds still while it turns, so the phone only has to move the two faces. */
function turnSleeve(el) {
  try { localStorage.setItem('turnHint', '9'); } catch (_) { /* preview without storage */ }
  el.classList.remove('nudge');
  el.classList.add('turning');
  el.classList.toggle('flipped');
  clearTimeout(el._turn);
  // The record rolls (about .35 s) and the sleeve turns (.6 s), one after the other.
  el._turn = setTimeout(() => el.classList.remove('turning'), 1100);
}
// A record that has slid out of its sleeve stays out when the page draws again.
document.addEventListener('animationend', (e) => { if (e.animationName === 'discOut') { const h = e.target.closest('.holo'); if (h) h.classList.add('settled'); } });

/** What the back of a record's sleeve says: the tracklist by side, label and format, and your plays as a stamp. */
function sleeveBackInner(base, rel, plays) {
  const tracks = (rel && rel.tracks) || [];
  const sides = [];
  tracks.forEach((t) => {
    let s = sides.find((x) => x.side === t.side);
    if (!s) sides.push(s = { side: t.side, list: [] });
    s.list.push(t);
  });
  const row = (t) => `<p><span>${esc(t.pos)}</span><b>${esc(t.title)}</b><em>${fmtDur(lenOf(t))}</em></p>`;
  // Up to a dozen songs fit one full-width column (A1, B1 tell the sides apart); longer records get two.
  const one = tracks.length <= 12;
  const half = Math.ceil(sides.length / 2);
  const cols = sides.length > 2 ? [sides.slice(0, half), sides.slice(half)] : sides.map((s) => [s]);
  const list = !tracks.length ? '<p class="sb-empty">The tracklist is on its way…</p>'
    : one ? `<div>${sides.map((s) => s.list.map(row).join('')).join('<hr>')}</div>`
      : cols.map((c) => `<div>${c.map((s) => `<h6>Side ${esc(s.side)}</h6>${s.list.map(row).join('')}`).join('')}</div>`).join('');
  const info = rel || base;
  return `<div class="sb-head"><b>${esc(info.title || base.title || '')}</b><i>${esc(info.artist || base.artist || '')}<br>${info.year || base.year || ''}</i></div>
    <div class="sb-cols${one ? ' one' : ''}">${list}</div>
    <div class="sb-foot"><small>${esc([labelText(info.label || base.label), info.format || base.format].filter(Boolean).join(', '))}</small><span class="barcode"></span></div>
    ${plays ? `<div class="sb-notes">played ${num(plays)}×</div>` : ''}`;
}

/** Now spinning as a turntable: the record on the platter with its cover as the label. */
function turntableHtml(sp) {
  const label = sp.cover ? `style="background-image:url('${cssUrl(sp.cover)}')"` : '';
  return `<div class="tt" data-key="${esc(sp.id)}" style="--h:${hue(sp.album + sp.artist)}"><div class="tt-plinth">
    <i class="tt-led"></i><div class="tt-platter"><div class="tt-strobe"></div><div class="tt-disc"><div class="tt-label" ${label}>${sp.cover ? '' : esc(sp.album)}</div></div><i class="tt-spindle"></i></div>
    <i class="tt-counter"></i><div class="tt-arm"><i class="tube"></i><i class="head"></i></div><i class="tt-pivot"></i>
    <div class="tt-speed"><i class="on">33</i><i>45</i></div><i class="tt-start"></i></div></div>`;
}
/** The tonearm travels through the side as it plays (outer groove at 25°, the label at 44°); the platter turns while a track plays. */
function tickTurntable(n, now) {
  const tt = $('#spinBody .tt');
  if (!tt) return;
  const side = n.item ? n.live.filter((it) => it.side === n.item.side) : [];
  let arm = 0;
  if (side.length) {
    const a = side[0].start, b = Math.max(...side.map((it) => it.start + it.dur * 1000));
    arm = 25 + Math.max(0, Math.min(1, (now - a) / Math.max(1, b - a))) * 19;
  }
  tt.style.setProperty('--arm', `${arm.toFixed(2)}deg`);
  tt.classList.toggle('spinning', !!n.item);
}

// ---- page colors: song, album and artist pages take theirs from the cover or the photo ----

/** Colors per picture URL from the app (onPalette); a page without them keeps the wallpaper's colors. */
const TINTS = {};
function hexHsl(hex) {
  const n = parseInt(String(hex).slice(1), 16);
  const r = ((n >> 16) & 255) / 255, g = ((n >> 8) & 255) / 255, b = (n & 255) / 255;
  const hi = Math.max(r, g, b), lo = Math.min(r, g, b), l = (hi + lo) / 2;
  if (hi === lo) return [0, 0, l];
  const d = hi - lo;
  const h = hi === r ? ((g - b) / d + 6) % 6 : hi === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h * 60, d / (1 - Math.abs(2 * l - 1)), l];
}
/**
 * CSS variables for a tinted page, a light and a dark step of each (see .tinted in app.css):
 * --tint for marks (charts, accents) from the most vivid color, --glow for the header from the
 * most common one. Black-and-white covers give gray.
 */
function tintStyle(colors, seedHue) {
  let h, s, gh, gs;
  if (colors) {
    [h, s] = hexHsl(colors.vibrant);
    [gh, gs] = hexHsl(colors.dominant);
  } else if (seedHue != null) {
    h = gh = seedHue;
    s = gs = 0.45;
  } else return '';
  const sat = s < 0.12 ? 0 : Math.round(Math.min(0.78, Math.max(0.4, s)) * 100);
  const gsat = gs < 0.12 ? 0 : Math.round(Math.min(0.55, Math.max(0.25, gs)) * 100);
  // The light step carries white text (filled buttons): yellows, greens and cyans are bright at 38%,
  // so they go darker until white on them reaches 4.5:1 (luminance .183, a little less for rounding).
  const hd = +h.toFixed(0);
  let ll = sat ? 38 : 32;
  while (ll > 20 && hslLuminance(hd, sat, ll) > 0.175) ll--;
  return `--tint-d:hsl(${hd} ${sat}% ${sat ? 70 : 78}%);--tint-l:hsl(${hd} ${sat}% ${ll}%);`
    + `--glow-d:hsl(${gh.toFixed(0)} ${gsat}% 24%);--glow-l:hsl(${gh.toFixed(0)} ${gsat}% 86%)`;
}
/** WCAG relative luminance of hsl([h], [s]%, [l]%). */
function hslLuminance(h, s, l) {
  const a = (s / 100) * Math.min(l, 100 - l) / 100;
  const ch = (n) => {
    const k = (n + h / 30) % 12;
    const v = l / 100 - a * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * ch(0) + 0.7152 * ch(8) + 0.0722 * ch(4);
}
/**
 * The page behind [el] takes the picture at [url] as its background: shrunk to a few dozen pixels
 * on a canvas once (softened and made more colorful there), then simply stretched over the page,
 * which blurs it for free. No filter runs while the page scrolls or the sleeve tilts.
 */
/** The small softened pictures made so far, by address: a page seen before gets its background at once. */
const BACKDROPS = new Map();
function setBackdrop(el, url) {
  const page = el && el.closest('.page');
  if (!page) return;
  if (!url) { page.classList.remove('has-backdrop'); return; }
  let bd = page.querySelector(':scope > .backdrop');
  if (!bd) {
    bd = document.createElement('div');
    bd.className = 'backdrop';
    bd.setAttribute('aria-hidden', 'true');
    bd.innerHTML = '<canvas width="48" height="72"></canvas>';
    page.prepend(bd);
  }
  page.classList.add('has-backdrop');
  if (bd.dataset.url === url) return;
  bd.dataset.url = url;
  bd.classList.remove('ready', 'known');
  const cv = bd.firstChild;
  const ctx = cv.getContext('2d');
  // The page held another record's picture: it goes now, not after the new one has loaded.
  ctx.clearRect(0, 0, cv.width, cv.height);
  const show = (pic, known) => {
    ctx.clearRect(0, 0, cv.width, cv.height);
    ctx.drawImage(pic, 0, 0);
    bd.classList.toggle('known', known);
    bd.classList.add('ready');
  };
  if (BACKDROPS.has(url)) { show(BACKDROPS.get(url), true); return; }
  const img = new Image();
  img.onload = () => {
    if (bd.dataset.url !== url) return;
    const small = document.createElement('canvas');
    small.width = cv.width;
    small.height = cv.height;
    const sx = small.getContext('2d');
    sx.imageSmoothingQuality = 'high';
    sx.filter = 'saturate(1.6)';
    // The upper middle of the cover, stretched to the page's shape.
    const side = Math.min(img.width, img.height);
    sx.drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, small.width, small.height);
    const soft = document.createElement('canvas');
    soft.width = cv.width;
    soft.height = cv.height;
    const fx = soft.getContext('2d');
    fx.filter = 'blur(3px)';
    fx.drawImage(small, 0, 0);
    if (BACKDROPS.size > 80) BACKDROPS.delete(BACKDROPS.keys().next().value);
    BACKDROPS.set(url, soft);
    show(soft, false);
  };
  img.src = url;
}
/** Tints [el] by the picture at [url] (asking the app for its colors once), or by a hue from [seed] without a picture. */
function tint(el, url, seed) {
  if (!el) return;
  setBackdrop(el, url);
  el.classList.add('tinted');
  el.dataset.tintUrl = url || '';
  const c = url ? TINTS[url] : null;
  paintTint(el, tintStyle(c, url ? null : hue(seed || '')));
  if (url && !c) send({ t: 'palette', url });
}
/** Puts the tint on [el], and on its page as well, whose own background takes it in the soft look. */
function paintTint(el, css) {
  el.setAttribute('style', css);
  const page = el.closest('.page');
  if (!page) return;
  page.classList.add('tinted');
  for (const k of ['--tint-d', '--tint-l', '--glow-d', '--glow-l']) page.style.removeProperty(k);
  for (const d of css.split(';')) {
    const i = d.indexOf(':');
    if (i > 0) page.style.setProperty(d.slice(0, i), d.slice(i + 1));
  }
}
window.onPalette = (p) => {
  TINTS[p.url] = p;
  if (p.url === ambientUrl) applyAmbient();
  if (p.url === storyTintUrl && !$('#story').hidden) applyStoryTint();
  $$('.tinted').forEach((el) => { if (el.dataset.tintUrl === p.url) paintTint(el, tintStyle(p)); });
};

// ---- charts for songs, albums and artists ----

let svgSeq = 0;
/** A smooth line through [[x, y], …] that never swings past its points (monotone cubic), as an SVG path. */
function smoothPath(pts) {
  const n = pts.length;
  if (n < 2) return n ? `M${pts[0][0]},${pts[0][1]}` : '';
  const dx = [], m = [];
  for (let i = 0; i < n - 1; i++) {
    dx[i] = pts[i + 1][0] - pts[i][0];
    m[i] = (pts[i + 1][1] - pts[i][1]) / dx[i];
  }
  const t = [m[0]];
  for (let i = 1; i < n - 1; i++) t[i] = m[i - 1] * m[i] <= 0 ? 0 : (m[i - 1] + m[i]) / 2;
  t[n - 1] = m[n - 2];
  for (let i = 0; i < n - 1; i++) {
    if (m[i] === 0) { t[i] = 0; t[i + 1] = 0; continue; }
    const a = t[i] / m[i], b = t[i + 1] / m[i], q = a * a + b * b;
    if (q > 9) { const k = 3 / Math.sqrt(q); t[i] = k * a * m[i]; t[i + 1] = k * b * m[i]; }
  }
  let d = `M${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}`;
  for (let i = 0; i < n - 1; i++) {
    const h = dx[i] / 3;
    d += `C${(pts[i][0] + h).toFixed(1)},${(pts[i][1] + t[i] * h).toFixed(1)} ${(pts[i + 1][0] - h).toFixed(1)},${(pts[i + 1][1] - t[i + 1] * h).toFixed(1)} ${pts[i + 1][0].toFixed(1)},${pts[i + 1][1].toFixed(1)}`;
  }
  return d;
}

/**
 * Plays over time as a smooth area in the page's tint, fading out, with its peak marked. The
 * strip of [col] cells over it takes taps (see onVizPointer).
 */
function areaViz(vals, opts) {
  const W = 320, H = 120, n = vals.length;
  const max = Math.max(1, ...vals);
  const x = (i) => (n === 1 ? W / 2 : (i / (n - 1)) * W);
  const y = (v) => H - 2 - (v / max) * (H - 10);
  const line = smoothPath(vals.map((v, i) => [x(i), y(v)]));
  const id = `ag${++svgSeq}`;
  let peak = 0;
  vals.forEach((v, i) => { if (v > vals[peak]) peak = i; });
  const px = (x(peak) / W) * 100, py = (y(vals[peak]) / H) * 100;
  const avg = opts.avg != null ? `<div class="avg" style="bottom:${(opts.avg / max) * ((H - 10) / H) * 100 + (2 / H) * 100}%"><span>avg ${opts.avg}</span></div>` : '';
  return `<div class="area">
    <svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" aria-hidden="true">
      <defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:var(--tint);stop-opacity:.55"/><stop offset="1" style="stop-color:var(--tint);stop-opacity:0"/></linearGradient></defs>
      <path d="${line} L${W},${H} L0,${H} Z" fill="url(#${id})"/>
      <path d="${line}" fill="none" style="stroke:var(--tint)" stroke-width="2.5" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
    </svg>${avg}
    <i class="peak" style="left:${px}%;top:${py}%"></i>
    <span class="peak-label${px > 62 ? ' end' : px < 20 ? ' start' : ''}" style="left:${px}%;top:${py}%">${opts.peakLabel(peak)}</span>
    <div class="hits">${vals.map((v, i) => `<div data-col="${opts.col}" data-i="${i}"></div>`).join('')}</div>
  </div>`;
}

/** The 24 hours as a ring around the cover or photo: longer, stronger wedges for more plays, midnight on top. */
function clockRing(hours, img, seed) {
  const max = Math.max(1, ...hours), C = 120, r0 = 60, band = 40;
  const pt = (r, a) => `${(C + r * Math.sin(a)).toFixed(1)},${(C - r * Math.cos(a)).toFixed(1)}`;
  const wedges = hours.map((v, i) => {
    const a0 = ((i * 15 + 1) * Math.PI) / 180, a1 = (((i + 1) * 15 - 1) * Math.PI) / 180;
    const r1 = r0 + 4 + (v / max) * band;
    return `<path data-col="ah" data-i="${i}" d="M${pt(r0 + 2, a0)} L${pt(r1, a0)} A${r1.toFixed(1)},${r1.toFixed(1)} 0 0 1 ${pt(r1, a1)} L${pt(r0 + 2, a1)} A${r0 + 2},${r0 + 2} 0 0 0 ${pt(r0 + 2, a0)} Z" style="fill:var(--tint);fill-opacity:${(0.22 + 0.78 * (v / max)).toFixed(2)}"/>`;
  }).join('');
  const id = `cr${++svgSeq}`;
  const center = img
    ? `<clipPath id="${id}"><circle cx="${C}" cy="${C}" r="${r0 - 4}"/></clipPath><circle cx="${C}" cy="${C}" r="${r0 - 4}" style="fill:hsl(${hue(seed || '')} 35% 40%)"/><image href="${esc(img)}" x="${C - r0 + 4}" y="${C - r0 + 4}" width="${(r0 - 4) * 2}" height="${(r0 - 4) * 2}" clip-path="url(#${id})" preserveAspectRatio="xMidYMid slice"/>`
    : `<circle cx="${C}" cy="${C}" r="${r0 - 4}" style="fill:hsl(${hue(seed || '')} 35% 40%)"/><text class="ini" x="${C}" y="${C + 17}" text-anchor="middle">${esc(String(seed || '?').trim().charAt(0).toUpperCase())}</text>`;
  const ticks = [0, 6, 12, 18].map((hh) => {
    const a = (hh * 15 * Math.PI) / 180;
    return `<text x="${(C + 112 * Math.sin(a)).toFixed(1)}" y="${(C - 112 * Math.cos(a) + 4).toFixed(1)}" text-anchor="middle">${hh}</text>`;
  }).join('');
  return `<svg class="ring" viewBox="0 0 240 240">${center}<g class="wedges">${wedges}</g>${ticks}</svg>`;
}

/** Every week since the first play as one thin line, stronger for more plays: a barcode of the habit. */
function barcodeViz(x, what) {
  const w = x.weeks || [];
  if (w.length < 3) return '';
  const g = Math.max(1, Math.ceil(w.length / 120));
  const bins = [];
  for (let i = 0; i < w.length; i += g) bins.push(w.slice(i, i + g).reduce((a, b) => a + b, 0));
  const max = Math.max(1, ...bins);
  return `<div class="viz"><h2>Your listening barcode</h2><span class="sub">One line per ${g === 1 ? 'week' : `${g} weeks`} since you first played ${what}: the stronger, the more plays</span>
    <div class="barcode">${bins.map((v, i) => `<i data-col="aw" data-i="${i}" data-g="${g}" style="opacity:${v ? (0.28 + 0.72 * Math.sqrt(v / max)).toFixed(2) : 0.07}"></i>`).join('')}</div>
    <div class="xl"><span>${esc(monthYear(x.weeksFrom))}</span><span>now</span></div><div class="readout">Tap a line for its week.</div></div>`;
}

/** The charts of a song, an album or an artist page: barcode, plays per month, and the clock ring around [img]. */
function lifeCharts(x, what, img, seed) {
  const mDate = (i) => { const d = new Date(x.monthsFrom); d.setMonth(d.getMonth() + i); return d; };
  const step = Math.max(1, Math.ceil(x.months.length / 4));
  const labels = x.months.map((v, i) => mDate(i)).filter((d, i) => i % step === 0).map((d) => d.toLocaleDateString([], { month: 'short', year: '2-digit' }));
  let peak = 0;
  x.hours.forEach((v, i) => { if (v > x.hours[peak]) peak = i; });
  const mood = peak >= 5 && peak < 10 ? 'a morning thing' : peak >= 10 && peak < 17 ? 'for the daytime' : peak >= 17 && peak < 22 ? 'an evening thing' : 'for the night';
  const area = x.months.length > 1 ? `<div class="viz"><h2>Plays per month</h2><span class="sub">Since you first played ${what}</span>
      ${areaViz(x.months, { col: 'am', peakLabel: (i) => `${esc(mDate(i).toLocaleDateString([], { month: 'short', year: 'numeric' }))}: ${num(x.months[i])}` })}
      <div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div><div class="readout">Tap a month.</div></div>` : '';
  return barcodeViz(x, what) + area + `<div class="viz"><h2>Around the clock</h2><span class="sub">When you play ${what}, hour by hour, midnight on top</span>
      ${clockRing(x.hours, img, seed)}<div class="readout">Mostly around <b>${pad(peak)}:00</b>: ${mood}.</div></div>`;
}

/** Shrinks a heading until it fits in [lines] lines and its longest word fits the width (long artist names). */
function fitText(el, lines = 3, max = 40, min = 18) {
  if (!el) return;
  let size = max;
  el.style.overflowWrap = 'normal';
  el.style.fontSize = `${size}px`;
  while (size > min && (el.scrollHeight > size * 1.08 * lines + 2 || el.scrollWidth > el.clientWidth + 1)) {
    size -= 2;
    el.style.fontSize = `${size}px`;
  }
  // Still too wide at the smallest size: one very long word may break.
  if (el.scrollWidth > el.clientWidth + 1) el.style.overflowWrap = 'anywhere';
}

// ---- discographies: everything an artist released (Deezer), with your plays and your shelf ----

const DISCO = {};
let discoFilter = 'all';
/** Titles without "(Deluxe Edition)", " - Remastered" and punctuation, to match across sources. */
const looseKey = (s) => norm(String(s || '').replace(/\s*[([][^)\]]*[)\]]/g, ' ').split(' - ')[0]).replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
const DISCO_TYPES = [['album', 'Albums', 'album'], ['ep', 'EPs', 'EP'], ['single', 'Singles', 'single'], ['compile', 'Compilations', 'compilation']];

function requestDiscography(name) {
  const d = DISCO[norm(name)];
  if (d && !d.error) return;
  DISCO[norm(name)] = { loading: true };
  send({ t: 'discography', name });
}
window.onDiscography = (d) => {
  DISCO[norm(d.name)] = d;
  const slot = $('#discoSlot');
  if (slot && ARTIST && norm(ARTIST.name) === norm(d.name)) slot.innerHTML = discoHtml(ARTIST.name);
  if (isOpen('albumPage')) albumNeedsDz();
};

const isNew = (date) => !!date && Date.now() - Date.parse(date) < 60 * 864e5;
function discoCard(x, artist) {
  return `<button class="xcard dcard${x.plays ? '' : ' unheard'}" ${albumAttrs(artist, x.title)} data-cover="${esc(x.cover)}" data-dz="${x.id}">
    <span class="dcover">${coverHtml(x.cover, x.title, artist)}${x.owned ? '<i class="owned-mark" aria-label="On your shelf"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="11"/><circle class="g" cx="12" cy="12" r="7.6"/><circle class="l" cx="12" cy="12" r="4"/><circle cx="12" cy="12" r="1"/></svg></i>' : ''}${isNew(x.date) ? '<i class="new">New</i>' : ''}</span>
    <b>${esc(x.title)}</b><small>${x.year || ''}${x.editions > 1 ? `, ${x.editions} editions` : ''}</small>
    <small class="meta">${x.plays ? plural(x.plays, 'play') : 'not heard yet'}</small></button>`;
}

/** The Discography section of an artist page: albums, EPs, singles and compilations, filterable. */
function discoHtml(name) {
  const d = DISCO[norm(name)];
  if (!d || d.loading) return `<div class="viz disco"><h2>Discography</h2><div class="lin inline indet"><i></i></div><p class="hint">Looking up everything they released…</p></div>`;
  if (d.error) return `<div class="viz disco"><h2>Discography</h2><p class="hint">${esc(d.error)}</p></div>`;
  const items = d.items || [];
  if (d.none || !items.length) return '';
  const played = items.filter((x) => x.plays > 0).length;
  const owned = items.filter((x) => x.owned).length;
  const keep = { played: (x) => x.plays > 0, unheard: (x) => !x.plays, owned: (x) => !!x.owned }[discoFilter] || (() => true);
  const counts = DISCO_TYPES.map(([t, , one]) => [one, items.filter((x) => x.type === t).length]).filter(([, n]) => n).map(([one, n]) => plural(n, one));
  const chips = [['all', 'All'], ['played', `Played (${played})`], ['unheard', `Not heard yet (${items.length - played})`], ['owned', `On your shelf (${owned})`]]
    .filter(([k]) => k !== 'owned' || owned)
    .map(([k, l]) => `<button class="chip ${discoFilter === k ? 'on' : ''}" data-dfilter="${k}">${esc(l)}</button>`).join('');
  const groups = DISCO_TYPES.map(([t, label]) => {
    const list = items.filter((x) => x.type === t && keep(x));
    return list.length ? `<h3>${label}</h3><div class="xcards${t === 'single' ? ' small' : ''}">${list.map((x) => discoCard(x, name)).join('')}</div>` : '';
  }).join('');
  return `<div class="viz disco"><div class="xhead"><h2>Discography</h2>${d.artist && d.artist.link ? `<button class="btn text small" data-url="${esc(d.artist.link)}">Deezer${icon('open')}</button>` : ''}</div>
    <span class="sub">${counts.join(', ')}. You've played ${played} of them${owned ? `; ${owned} ${owned === 1 ? 'is' : 'are'} on your shelf` : ''}.</span>
    <div class="chip-row">${chips}</div>${groups || '<p class="hint">Nothing with this filter.</p>'}</div>`;
}

/** An album page's tracklist from Deezer: by the id it was opened with, or found in the artist's discography. */
function albumNeedsDz() {
  const a = ALBUM;
  if (!a || a.dzInfo || a.dzAsked) return;
  let id = a.dz;
  if (!id) {
    const d = DISCO[norm(a.artist)];
    if (!d) { requestDiscography(a.artist); return; }
    const hit = (d.items || []).find((x) => looseKey(x.title) === looseKey(a.title));
    if (!hit) return;
    id = hit.id;
  }
  a.dzAsked = true;
  send({ t: 'dzAlbum', id, req: a.req });
}
window.onDzAlbum = (x) => {
  ALBUM = deliver('albumPage', x.req, ALBUM, (s, shown) => {
    const next = { ...s, dzInfo: x };
    if (shown) { ALBUM = next; renderAlbum(); }
    return next;
  });
};

/** The album's songs in order, with your plays of each (songs you never played are dimmed). */
function tracklistHtml(a, cover) {
  const dz = a.dzInfo && !a.dzInfo.error ? a.dzInfo : null;
  if (!dz || !dz.tracks || !dz.tracks.length) return '';
  const plays = {};
  (a.trackList || []).forEach((t) => { plays[looseKey(t.title)] = (plays[looseKey(t.title)] || 0) + t.plays; });
  const max = Math.max(1, ...Object.values(plays));
  const total = dz.tracks.reduce((n, t) => n + (t.sec || 0), 0);
  const heard = dz.tracks.filter((t) => plays[looseKey(t.title)]).length;
  return `<div class="viz"><h2>Tracklist</h2><span class="sub">${plural(dz.tracks.length, 'song')}, ${Math.round(total / 60)} minutes${heard ? `; you've played ${heard} of them` : ''}</span>
    <div class="rank">${dz.tracks.map((t, i) => {
      const p = plays[looseKey(t.title)] || 0;
      return `<button class="r${p ? '' : ' unheard'}" ${trackAttrs(a.artist, t.title, { album: a.title, cover })}><span class="n">${i + 1}</span><span class="t"><b>${esc(t.title)}</b><small>${fmtDur(t.sec)}</small></span>
        <span class="v${p ? ' cnt' : ''}">${p ? `${num(p)}×` : ''}</span>${p ? `<div class="bar"><i style="width:${(p / max) * 100}%"></i></div>` : ''}</button>`;
    }).join('')}</div></div>`;
}

// ---- shelf filters: decade and genre or style, with a summary of the selection ----

const genreKeyOf = (s) => String(s).toLowerCase().replace(/[-_]/g, ' ').trim().replace(/\s+/g, ' ');
const decadeOf = (r) => (r.year ? Math.floor(r.year / 10) * 10 : 0);
function shelfFilterOk(r, skip) {
  if (skip !== 'decade' && shelf.decade && decadeOf(r) !== shelf.decade) return false;
  if (skip !== 'genre' && shelf.genre) {
    const kind = shelf.genre.slice(0, 1), v = shelf.genre.slice(2);
    if (!((kind === 'g' ? r.genres : r.styles) || []).includes(v)) return false;
  }
  return true;
}
const shelfFiltered = () => !!(shelf.decade || shelf.genre);

function renderFilterChips() {
  const dec = shelf.decade ? `<button class="chip on" data-fclear="decade">${shelf.decade}s${icon('close', 'i small')}</button>` : '';
  const gen = shelf.genre ? `<button class="chip on" data-fclear="genre">${esc(shelf.genre.slice(2))}${icon('close', 'i small')}</button>` : '';
  $('#filterChips').innerHTML = dec + gen;
  $('#filterChips').hidden = !shelfFiltered();
}

/** Sort: every order and its reverse, one tap each. */
function openSortSheet() {
  openSheet(`<div class="sheet-title">Sort</div><div class="sort-grid">${SORTS.map((s) => s.labels.map((l, k) => {
    const on = shelf.sort === s.id && shelf.rev === !!k;
    return `<button class="chip${on ? ' on' : ''}" data-sort="${s.id}" data-rev="${k}">${esc(l)}</button>`;
  }).join('')).join('')}</div>`);
  $('#sheetContent').onclick = (e) => {
    const b = e.target.closest('[data-sort]');
    if (!b) return;
    shelf.sort = b.dataset.sort;
    shelf.rev = b.dataset.rev === '1';
    closeSheet();
    renderShelf();
    try { localStorage.setItem('shelfSort', JSON.stringify({ sort: shelf.sort, rev: shelf.rev })); } catch (_) { /* preview without storage */ }
  };
}
/** Filter: by decade or by genre and style (the pickers are openFilterSheet). */
function openFilterChoice() {
  openSheet(`<div class="sheet-title">Filter</div><div class="btn-row">
    <button class="btn tonal" data-fpick="decade">${icon('calendar')}${shelf.decade ? `${shelf.decade}s` : 'Decade'}</button>
    <button class="btn tonal" data-fpick="genre">${icon('album')}${shelf.genre ? esc(shelf.genre.slice(2)) : 'Genre or style'}</button></div>
    ${shelfFiltered() ? `<button class="btn text" id="fClearAll">${icon('close')}Show all records</button>` : ''}`);
  $('#sheetContent').onclick = (e) => {
    if (!e.target.closest('#fClearAll')) return;
    shelf.decade = 0;
    shelf.genre = '';
    closeSheet();
    renderShelf();
  };
}

/** The sheet to pick a decade, or a genre and its styles, with how many records each has (within the other filter). */
function openFilterSheet(which) {
  const base = COLL.filter((r) => shelfFilterOk(r, which));
  const count = (keys) => { const m = {}; base.forEach((r) => keys(r).forEach((k) => { if (k) m[k] = (m[k] || 0) + 1; })); return Object.entries(m); };
  const chip = (val, label, n) => `<button class="chip" data-fset="${esc(val)}">${esc(label)}<small class="n">${n}</small></button>`;
  let h;
  if (which === 'decade') {
    const ds = count((r) => [decadeOf(r)]).filter(([d]) => +d > 0).sort((a, b) => a[0] - b[0]);
    h = `<div class="sheet-title">Decade</div><div class="chips">${ds.map(([d, n]) => chip(`decade:${d}`, `${d}s`, n)).join('')}</div>`;
  } else {
    const gs = count((r) => r.genres || []).sort((a, b) => b[1] - a[1]);
    const ss = count((r) => r.styles || []).sort((a, b) => b[1] - a[1]);
    h = `<div class="sheet-title">Genre or style</div><p class="label">Genres</p><div class="chips">${gs.map(([g, n]) => chip(`g:${g}`, g, n)).join('')}</div>
      <p class="label">Styles</p><div class="chips">${ss.map(([g, n]) => chip(`s:${g}`, g, n)).join('')}</div>`;
  }
  openSheet(h);
  $('#sheetContent').onclick = (e) => {
    const b = e.target.closest('[data-fset]');
    if (!b) return;
    const v = b.dataset.fset;
    if (v.startsWith('decade:')) shelf.decade = +v.slice(7);
    else shelf.genre = v;
    closeSheet();
    renderShelf();
  };
}

/** What's in the selection: records, artists, plays, the most played, and how it splits by style or decade. */
function shelfSummaryHtml(list) {
  if (!shelfFiltered()) return '';
  const name = [shelf.decade ? `${shelf.decade}s` : '', shelf.genre ? shelf.genre.slice(2) : ''].filter(Boolean).join(' ');
  if (!list.length) return `<div class="card sel-sum"><p class="body">No ${esc(name)} records on your shelf.</p></div>`;
  const artists = new Set(list.map((r) => r._artist)).size;
  const plays = list.reduce((n, r) => n + (r.plays || 0), 0);
  const never = list.filter((r) => !r.plays).length;
  const top = list.filter((r) => r.plays).sort((a, b) => b.plays - a.plays).slice(0, 3);
  const split = {};
  list.forEach((r) => (shelf.genre ? [decadeOf(r) ? `${decadeOf(r)}s` : ''] : r.styles || []).forEach((k) => { if (k) split[k] = (split[k] || 0) + 1; }));
  const bars = Object.entries(split).sort((a, b) => b[1] - a[1]).slice(0, 5);
  const max = bars.length ? bars[0][1] : 1;
  const gk = shelf.genre ? genreKeyOf(shelf.genre.slice(2)) : '';
  return `<div class="card sel-sum"><div class="card-title">${icon('album')}${esc(name)} on your shelf</div>
    <div class="sum-nums"><div><b>${list.length}</b><small>${list.length === 1 ? 'record' : 'records'}</small></div><div><b>${artists}</b><small>${artists === 1 ? 'artist' : 'artists'}</small></div>
      <div><b>${compact(plays)}</b><small>plays on Last.fm</small></div><div><b>${never}</b><small>never played</small></div></div>
    ${top.length ? `<p class="body">Most played: ${top.map((r) => `<b>${esc(r.title)}</b>`).join(', ')}</p>` : ''}
    ${bars.length > 1 ? `<p class="label">${shelf.genre ? 'By decade' : 'By style'}</p><div class="mini-bars">${bars.map(([k, n]) => `<div><span>${esc(k)}</span><i><b style="width:${(n / max) * 100}%"></b></i><small>${n}</small></div>`).join('')}</div>` : ''}
    ${gk && (ST.history || {}).count ? `<div class="btn-row"><button class="btn tonal" data-statsgenre="${esc(gk)}">${icon('chart')}Your ${esc(shelf.genre.slice(2))} listening</button></div>` : ''}</div>`;
}

// ---- the Stats tab for one genre ----

let statsGenre = '';
let statsDecade = 0;
/** "in Post Punk", "from the 1970s", "in Post Punk from the 1970s", or "" without filters. */
const filterWords = (genre, decade) => [genre ? `in ${genreLabel(genre)}` : '', decade ? `from the ${decade}s` : ''].filter(Boolean).join(' ');
function genreLabel(key) {
  for (const o of (STATS && STATS.genreOptions) || []) {
    if (o.key === key) return o.label;
    const s = (o.subs || []).find((x) => x.key === key);
    if (s) return s.label;
  }
  return key.replace(/^[a-z]+:/, '').replace(/\b\w/g, (c) => c.toUpperCase());
}
/** The genre and decade the stats are narrowed to, each with its ✕; the filter button picks them. */
function renderGenreRow() {
  const row = $('#genreRow');
  if (!row) return;
  const g = statsGenre ? `<button class="chip on" data-gclear>${icon('album', 'i small')}${esc(genreLabel(statsGenre))}${icon('close', 'i small')}</button>`
    + `<button class="chip link" data-genre="${esc(statsGenre)}" data-glabel="${esc(genreLabel(statsGenre))}">About ${esc(genreLabel(statsGenre))} ›</button>` : '';
  const d = statsDecade ? `<button class="chip on" data-dclear>${icon('calendar', 'i small')}${statsDecade}s${icon('close', 'i small')}</button>` : '';
  row.innerHTML = g + d;
  row.hidden = !(g || d);
  $('#statsFilter').classList.toggle('on', !!(g || d));
}
function openStatsFilter() {
  openSheet(`<div class="sheet-title">Stats for…</div><div class="btn-row">
    <button class="btn tonal" data-gpick>${icon('album')}${statsGenre ? esc(genreLabel(statsGenre)) : 'One genre'}</button>
    <button class="btn tonal" data-dpick>${icon('calendar')}${statsDecade ? `${statsDecade}s` : 'One decade'}</button></div>`);
  $('#sheetContent').onclick = null;
}

/** The sheet to pick a decade of release, with how many plays each has and how much of the history has a known year. */
function openDecadeSheet() {
  const o = (STATS && STATS.decadeOptions) || { decades: [], known: 0, total: 0 };
  const h = ST.history || {};
  const share = o.total ? Math.round((o.known / o.total) * 100) : 0;
  const busy = h.yearsBusy ? ` Looking up more: ${h.yearsDone} of ${plural(h.yearsTotal, 'artist')}.` : '';
  openSheet(`<div class="sheet-title">Stats for one decade</div>
    <p class="body">Everything on the Stats tab and in the story, for the music that came out in that decade. Release years are known for ${share}% of your plays (songs without an album have none).${busy}</p>
    <div class="chips">${o.decades.map((d) => `<button class="chip" data-dset="${d.decade}">${d.decade}s<small class="n">${compact(d.plays)}</small></button>`).join('') || '<p class="hint">Release years are still being looked up.</p>'}</div>`);
  $('#sheetContent').onclick = (e) => {
    const b = e.target.closest('[data-dset]');
    if (!b) return;
    closeSheet();
    setStatsDecade(+b.dataset.dset);
  };
}
function setStatsDecade(d) {
  statsDecade = d;
  renderGenreRow();
  requestStats();
}

/** Plays of the period by the decade the music came out, as bars; tap a decade to see only its plays. */
function decadesViz(x) {
  const ds = (x && x.decades) || [];
  if (ds.length < 2 && !statsDecade) return '';
  const max = Math.max(1, ...ds.map((d) => d.plays));
  const share = x.total ? Math.round((x.known / x.total) * 100) : 0;
  return `<div class="viz"><h2>Decades</h2><span class="sub">When the music you played came out (${share}% of plays have a known year). Tap one for more.</span>
    <div class="decades">${ds.map((d) => `<button class="dec${statsDecade === d.decade ? ' on' : ''}" data-decade="${d.decade}"><span>${d.decade}s</span><i><b style="width:${(d.plays / max) * 100}%"></b></i><small>${compact(d.plays)}</small></button>`).join('')}</div></div>`;
}
/** The sheet to pick a genre for the stats: the families, each opening to the styles inside it. */
function openGenreSheet() {
  const opts = (STATS && STATS.genreOptions) || [];
  const open = new Set(statsGenre ? [statsGenre.split(':')[0]] : []);
  const draw = () => `<div class="sheet-title">Stats for one genre</div><p class="body">Everything on the Stats tab and in the story, for the plays whose main genre, or style in it, is this one.</p>
    <div class="gfams">${opts.map((o, k) => `<div class="gfam${statsGenre === o.key ? ' on' : ''}"><i class="sw" style="background:${familyColor(k + 1)}"></i>
      <button class="gfam-name" data-gset="${esc(o.key)}">${esc(o.label)}<small class="n">${compact(o.plays)}</small></button>
      ${(o.subs || []).length ? `<button class="icon-btn small${open.has(o.key) ? ' open' : ''}" data-gexp="${esc(o.key)}" aria-label="Styles in ${esc(o.label)}">${icon('chevron-right')}</button>` : '<span></span>'}</div>
      ${open.has(o.key) ? `<div class="chips gsubs">${o.subs.map((s) => `<button class="chip${statsGenre === s.key ? ' on' : ''}" data-gset="${esc(s.key)}">${esc(s.label)}<small class="n">${compact(s.plays)}</small></button>`).join('')}</div>` : ''}`).join('')
      || '<p class="hint">Genres show up once they are looked up.</p>'}</div>`;
  openSheet(draw());
  $('#sheetContent').onclick = (e) => {
    const x = e.target.closest('[data-gexp]');
    if (x) {
      const k = x.dataset.gexp;
      if (open.has(k)) open.delete(k); else open.add(k);
      $('#sheetContent').innerHTML = draw();
      return;
    }
    const b = e.target.closest('[data-gset]');
    if (!b) return;
    closeSheet();
    setStatsGenre(b.dataset.gset);
  };
}
function setStatsGenre(key) {
  statsGenre = key;
  renderGenreRow();
  requestStats();
}

// ---- your genres: a map of all of them, each with the styles inside it ----

/** Squarified treemap: [items] (each with .v) laid into a [W]×[H] box, biggest first; each gets x, y, w, h. */
function squarify(items, W, H) {
  const total = items.reduce((a, b) => a + b.v, 0) || 1;
  let rest = items.map((it) => ({ ...it, a: (it.v / total) * W * H }));
  const out = [];
  let x = 0, y = 0, w = W, h = H;
  const worst = (row, side) => {
    const s = row.reduce((a, b) => a + b.a, 0), mx = Math.max(...row.map((r) => r.a)), mn = Math.min(...row.map((r) => r.a));
    return Math.max((side * side * mx) / (s * s), (s * s) / (side * side * mn));
  };
  while (rest.length) {
    const side = Math.min(w, h);
    let k = 1;
    while (k < rest.length && worst(rest.slice(0, k + 1), side) <= worst(rest.slice(0, k), side)) k++;
    const row = rest.slice(0, k), thick = row.reduce((a, b) => a + b.a, 0) / side;
    let off = 0;
    for (const it of row) {
      const len = it.a / thick;
      out.push(w >= h ? { ...it, x, y: y + off, w: thick, h: len } : { ...it, x: x + off, y, w: len, h: thick });
      off += len;
    }
    if (w >= h) { x += thick; w -= thick; } else { y += thick; h -= thick; }
    rest = rest.slice(k);
  }
  return out;
}
/** A family's color: the six biggest have the genre drift's colors (same order: all time), the rest grays. */
const familyColor = (rank) => (rank >= 1 && rank <= GENRE_COLORS.length ? GENRE_COLORS[rank - 1] : `color-mix(in srgb, var(--md-on-surface) ${Math.max(28, 52 - rank * 2)}%, var(--md-surface))`);
let GENRE_MORE = [];
/** Your genres as a map (Explore): every family by its share of all your plays; tap one for its page. */
function genreMapHtml(m) {
  const fams = (m && m.families) || [];
  if (fams.length < 2) return '';
  const total = m.known || fams.reduce((n, f) => n + f.plays, 0);
  const big = fams.filter((f, k) => k < 12 && f.plays / total >= 0.015);
  GENRE_MORE = fams.slice(big.length);
  const items = big.map((f, k) => ({ v: f.plays, f, color: familyColor(k + 1) }));
  const others = GENRE_MORE.reduce((n, f) => n + f.plays, 0);
  if (others) items.push({ v: others, others: true, color: 'var(--md-outline)' });
  const W = 1000, H = 860;
  const boxes = squarify(items, W, H).map((b) => {
    const pw = (b.w / W) * 100, ph = (b.h / H) * 100;
    const name = b.others ? `${GENRE_MORE.length} more` : b.f.label;
    const subs = pw > 30 && ph > 22 && !b.others && b.f.subs.length ? `<small class="gm-subs">${b.f.subs.slice(0, 3).map((s) => esc(s.label)).join(', ')}</small>` : '';
    const attrs = b.others ? 'data-genremore' : `data-genre="${esc(b.f.key)}" data-glabel="${esc(b.f.label)}"`;
    return `<button class="gm-box" ${attrs} aria-label="${esc(name)}" style="left:${((b.x / W) * 100).toFixed(2)}%;top:${((b.y / H) * 100).toFixed(2)}%;width:${pw.toFixed(2)}%;height:${ph.toFixed(2)}%;--c:${b.color}">
      <b>${esc(name)}</b><small>${Math.round((b.v / total) * 100)}%</small>${subs}</button>`;
  }).join('');
  return `<div class="viz genre-map-card"><h2>Your genres</h2><span class="sub">All your listening, sorted into genres: the bigger, the more you play it${m.all > total ? ` (${pct(total / m.all)} of your plays have a known genre)` : ''}. Tap one for its page.</span>
    <div class="genre-map">${boxes}</div><div class="chips gm-more" hidden></div></div>`;
}
/**
 * Fits each name to its box: wrapped between words, never cut. Too big, it gets smaller, then
 * drops its styles, then its share; a box too small for any name stays plain, and its genre is
 * listed under the map instead. Again whenever the map changes size (it has none while hidden).
 */
const FIT_STEPS = ['', 'fs1', 'fs1 nosubs', 'fs2 nosubs', 'fs2 nosubs nopct', 'quiet'];
function fitGenreMap() {
  const map = $('.genre-map');
  if (!map || !map.offsetWidth) return;
  const fits = (box) => box.scrollHeight <= box.clientHeight + 1 && [...box.children].every((c) => c.offsetParent === null || c.scrollWidth <= c.clientWidth + 1);
  const quiet = [];
  for (const box of map.querySelectorAll('.gm-box')) {
    for (const step of FIT_STEPS) {
      box.className = `gm-box ${step}`.trim();
      if (step === 'quiet' || fits(box)) break;
    }
    if (box.classList.contains('quiet') && box.dataset.genre) quiet.push(box);
  }
  const more = map.parentElement.querySelector('.gm-more');
  if (more) {
    more.innerHTML = quiet.map((b) => `<button class="chip" data-genre="${esc(b.dataset.genre)}" data-glabel="${esc(b.dataset.glabel)}"><i class="sw" style="background:${b.style.getPropertyValue('--c')}"></i>${esc(b.dataset.glabel)}</button>`).join('');
    more.hidden = !quiet.length;
  }
}
const gmSizes = new ResizeObserver(() => fitGenreMap());
function openGenreMore() {
  openSheet(`<div class="sheet-title">More genres</div><div class="chips">${GENRE_MORE.map((f) => `<button class="chip" data-genre="${esc(f.key)}" data-glabel="${esc(f.label)}">${esc(f.label)}<small class="n">${compact(f.plays)}</small></button>`).join('')}</div>`);
  $('#sheetContent').onclick = (e) => { if (e.target.closest('[data-genre]')) closeSheet(); };
}

// ---- one genre: its stats, your music in it, what to rediscover, your records ----

let GENRE = null;
function openGenre(key, label) {
  if (!key) return;
  openPage('genrePage');
  GENRE = { key, label: label || genreLabel(key), loading: true, req: ++REQ };
  renderGenre();
  send({ t: 'genre', key, req: GENRE.req });
}
window.onGenre = (g) => {
  GENRE = deliver('genrePage', g.req, GENRE, (s, shown) => {
    const next = { ...s, ...g, label: g.label || s.label, req: s.req, loading: false };
    if (shown) { GENRE = next; renderGenre(); }
    return next;
  });
};

/** Artists mostly in another genre who are partly this one too: their plays stay counted there. */
function alsoHtml(g) {
  const also = g.also || [];
  if (!also.length) return '';
  return `<div class="g-also"><span class="sub">Also partly ${esc(g.label)}; their plays count under their main genre</span>
    <div class="chips everyone">${also.map((a) => `<button class="chip" data-artist="${esc(a.name)}">${esc(a.name)}<small class="n">${compact(a.plays)}</small></button>`).join('')}</div></div>`;
}
function renderGenre() {
  const g = GENRE;
  if (!g || !isOpen('genrePage')) return;
  const shelfRecs = (g.shelf || []).map((id) => COLL.find((r) => r.id === id)).filter(Boolean);
  const fam = g.family || null, isSub = g.kind === 'sub';
  const crumbs = fam ? `<nav class="crumb"><button data-genremap>Genres</button><span>›</span>${isSub ? `<button data-genre="${esc(fam.key)}" data-glabel="${esc(fam.label)}">${esc(fam.label)}</button><span>›</span>` : ''}<b>${esc(g.label)}</b></nav>` : '<span class="k">Genre</span>';
  const meta = !g.plays ? '' : isSub
    ? `${pct(g.ofFamily)} of ${esc(fam.label)}, ${pct(g.share)} of everything you play`
    : `${pct(g.share)} of everything you play${g.rank ? `, your number ${g.rank} genre` : ''}`;
  let h = `<div class="genre-hero page-hero"><i class="glow"></i>${crumbs}<h1>${esc(g.label)}</h1>
    ${meta ? `<div class="meta">${meta}</div>` : ''}</div>`;
  const shelfHtml = shelfRecs.length ? `<div class="viz"><h2>On your shelf</h2><span class="sub">${plural(shelfRecs.length, 'record')} with ${esc(g.label)} on Discogs</span>
      <div class="xcards">${shelfRecs.map((r) => `<button class="xcard" data-rec="${r.id}">${coverHtml(r.cover || r.thumb, r.title, r.artist)}<b>${esc(r.title)}</b><small>${esc(r.artist)}</small><small class="meta">${r.year || ''}</small></button>`).join('')}</div></div>` : '';
  if (g.loading) h += '<div class="lin inline indet"><i></i></div>';
  else if (g.missing) h += `<div class="empty small"><p>None of your plays has ${esc(g.label)} as its main genre${shelfRecs.length ? ', but some of your records do' : ''}.</p></div>`;
  else {
    const top = g.top || {};
    const P = {};
    P.tiles = `<div class="tiles">
      ${tile('Plays', num(g.plays), `on ${plural(g.days, 'day')}`, '', 'music')}
      ${tile('Artists', num(g.artists), (top.artists || [])[0] ? `most: ${top.artists[0].name}` : '', '', 'heart')}
      ${tile('Since', esc(monthYear(g.first)), `last played ${esc(dateLabel(g.last))}`, '', 'history')}
      ${tile('Biggest day', plural(g.bestDay.count, 'play'), esc(dateLabel(g.bestDay.day)), '', 'fire')}
    </div>
    <div class="btn-row"><button class="btn filled" data-statsgenre="${esc(g.key)}" aria-label="Stats for ${esc(g.label)}">${icon('chart')}Stats</button>
      <button class="btn tonal" data-genrepl>${icon('playlist')}Make a playlist</button></div>`;
    P.inside = genreInsideHtml(g);
    if ((top.artists || []).length) {
      P.artists = `<div class="viz"><h2>Your ${esc(g.label)} artists</h2><span class="sub">Most played, all time</span>
        <div class="xcards">${top.artists.slice(0, 10).map((a) => `<button class="xcard" data-artist="${esc(a.name)}">${coverHtml(a.photo, a.name, a.name, 'round')}<b>${esc(a.name)}</b><small class="meta">${plural(a.plays, 'play')}</small></button>`).join('')}</div>${alsoHtml(g)}</div>`;
    }
    const lost = g.lost || [], lostAlbums = g.lostAlbums || [];
    if (lost.length || lostAlbums.length) {
      P.lost = `<div class="viz"><h2>Rediscover</h2><span class="sub">${esc(g.label)} you loved, quiet for half a year or more</span>
        <div class="xcards">${lost.map((a) => `<button class="xcard" data-artist="${esc(a.name)}">${coverHtml(a.photo || a.cover, a.name, a.name, 'round')}<b>${esc(a.name)}</b><small>last ${esc(monthYear(a.last))}</small><small class="meta">${plural(a.plays, 'play')}</small></button>`).join('')}
        ${lostAlbums.map((a) => `<button class="xcard" ${albumAttrs(a.artist, a.title)} data-cover="${esc(a.cover)}">${coverHtml(a.cover, a.title, a.artist)}<b>${esc(a.title)}</b><small>${esc(a.artist)}</small><small class="meta">last ${esc(monthYear(a.last))}</small></button>`).join('')}</div></div>`;
    }
    if ((top.albums || []).length) {
      P.albums = `<div class="viz"><h2>Albums</h2><div class="xcards">${top.albums.slice(0, 10).map((a) => `<button class="xcard" ${albumAttrs(a.artist, a.title)} data-cover="${esc(a.cover)}">${coverHtml(a.cover, a.title, a.artist)}<b>${esc(a.title)}</b><small>${esc(a.artist)}</small><small class="meta">${plural(a.plays, 'play')}</small></button>`).join('')}</div></div>`;
    }
    if ((top.tracks || []).length) {
      const max = Math.max(1, ...top.tracks.map((t) => t.plays));
      P.songs = `<div class="viz"><h2>Songs</h2><div class="rank">${top.tracks.slice(0, topN('genre')).map((t, i) => `<button class="r" ${trackAttrs(t.artist, t.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}</small></span><span class="v cnt">${num(t.plays)}×</span><div class="bar"><i style="width:${(t.plays / max) * 100}%"></i></div></button>`).join('')}</div>${topMore('genre', top.tracks.length)}</div>`;
    }
    const all = g.all || [];
    if (all.length > (top.artists || []).length) {
      const shown = g.showAll ? all : all.slice(0, 30);
      P.everyone = `<div class="viz"><h2>Everyone in ${esc(g.label)}</h2><span class="sub">All ${num(g.artists)} artists you played, most first</span>
        <div class="chips everyone">${shown.map((a) => `<button class="chip" data-artist="${esc(a.name)}">${esc(a.name)}<small class="n">${compact(a.plays)}</small></button>`).join('')}</div>
        ${all.length > shown.length ? `<button class="btn text" data-genreall>Show all ${num(all.length)}</button>` : ''}</div>`;
    }
    const ds = (g.decades && g.decades.decades) || [];
    if (ds.length > 1) {
      const max = Math.max(1, ...ds.map((d) => d.plays));
      P.decades = `<div class="viz"><h2>Decades</h2><span class="sub">When your ${esc(g.label)} came out</span><div class="decades">${ds.map((d) => `<div class="dec"><span>${d.decade}s</span><i><b style="width:${(d.plays / max) * 100}%"></b></i><small>${compact(d.plays)}</small></div>`).join('')}</div></div>`;
    }
    P.life = lifeCharts(g, 'it', '', g.label + g.label);
    P.shelf = shelfHtml;
    h += tabbedHtml('genrePage', g.tab, GENRE_TABS, GENRE_LONG, P);
  }
  if (shelfRecs.length && (g.loading || g.missing)) h += shelfHtml;
  morph($('#genreBody'), h);
  tint($('#genreBody'), '', (g.family ? g.family.label : g.label) + g.label);
  syncPageTabs($('#genrePage'));
}

/**
 * The styles inside a genre (on its page) or next to a style (on a style's page): one bar of
 * their shares in the family's color, and a chip for each to open it.
 */
function genreInsideHtml(g) {
  const inside = g.inside || [], fam = g.family;
  if (!fam || !inside.length) return '';
  const isSub = g.kind === 'sub';
  const total = Math.max(1, fam.plays);
  const color = familyColor(fam.rank);
  const shown = g.showSubs ? inside : inside.slice(0, 8);
  const rest = inside.slice(shown.length).reduce((n, s) => n + s.plays, 0) + (g.general || 0);
  const shade = (k) => `color-mix(in srgb, ${color} ${Math.max(28, 100 - k * 18)}%, var(--md-surface))`;
  const segs = shown.map((s, k) => `<i style="flex:${s.plays} 1 0;background:${shade(k)}"${s.key === g.key ? ' class="on"' : ''}></i>`).join('')
    + (rest ? `<i style="flex:${rest} 1 0" class="rest"></i>` : '');
  const chips = shown.map((s, k) => `<button class="chip${s.key === g.key ? ' on' : ''}" ${s.key === g.key ? '' : `data-genre="${esc(s.key)}" data-glabel="${esc(s.label)}"`}><i class="sw" style="background:${shade(k)}"></i>${esc(s.label)}<small class="n">${Math.round((s.plays / total) * 100)}%</small></button>`).join('');
  const more = inside.length > shown.length ? `<button class="chip" data-genresubs>${inside.length - shown.length} more</button>` : '';
  return `<div class="viz"><h2>${isSub ? 'Next to it' : 'Inside it'}</h2>
    <span class="sub">${isSub ? `The other styles in ${esc(fam.label)}` : 'The styles and tags it is made of'}${g.general ? `; ${pct(g.general / total)} of its plays name no style` : ''}. Tap one for its page.</span>
    <div class="gbar">${segs}</div><div class="chips gchips">${chips}${more}</div></div>`;
}

// ---- one song ----

let TRACK = null;
function openTrack(artist, title, extra = {}) {
  if (!artist || !title) return;
  openPage('trackPage');
  TRACK = { loading: true, artist, title, albumTitle: extra.album || '', cover: extra.cover || '', req: ++REQ };
  renderTrack();
  send({ t: 'track', artist, title, range: statsRange, req: TRACK.req });
}
/** Whether a page's new data is the same as what it shows (the app often answers twice, e.g. once a picture arrived elsewhere). */
const sameData = (a, b) => JSON.stringify(a) === JSON.stringify(b);
window.onTrack = (t) => {
  TRACK = deliver('trackPage', t.req, TRACK, (s, shown) => {
    const next = { ...s, ...t, req: s.req, loading: false };
    if (shown) { const changed = !sameData(next, s); TRACK = next; if (changed) renderTrack(); }
    return next;
  });
};

/** Genre chips that open their genre's page. */
const genreChips = (list) => (list && list.length ? `<div class="tags">${list.map((g) => `<button class="chip link" data-genre="${esc(genreKeyOf(g))}" data-glabel="${esc(g)}">${esc(g)}</button>`).join('')}</div>` : '');

/**
 * What you played of an artist or album in the Stats period you came from: the page itself is
 * about all your time with them, this is the period's part ([noun]: "artist" or "album").
 */
function periodBlock(r, list, noun, attrs, extra = '') {
  if (!r || r.kind === 'all' || !r.plays || !list || !list.length) return '';
  const max = Math.max(1, ...list.map((x) => x.plays));
  return `<div class="viz"><h2>${esc(periodName(r))}</h2><span class="sub">${plural(r.plays, 'play')}${r.rank ? `, your #${r.rank} ${noun} then` : ''}. The rest of this page is all time.</span>
    <div class="rank">${list.map((x, i) => `<button class="r" ${attrs(x)}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b></span><span class="v">${num(x.plays)}</span><div class="bar"><i style="width:${(x.plays / max) * 100}%"></i></div></button>`).join('')}</div>${extra}</div>`;
}

function renderTrack() {
  const t = TRACK;
  if (!t || !isOpen('trackPage')) return;
  const al = t.album && typeof t.album === 'object' ? t.album : null;
  const albumTitle = al ? al.title : t.albumTitle;
  const cover = (al && al.cover) || t.cover;
  const shelfId = (al && al.owned) || (albumTitle ? shelfIdOf(al ? al.artist : t.artist, albumTitle) : 0);
  let h = `<div class="hero page-hero"><i class="glow"></i>${holoCover(cover, albumTitle || t.title, t.artist, { owned: !!shelfId, back: !!shelfId, rel: shelfId, key: `track|${t.artist}|${t.title}|${shelfId}` })}
    <h1>${esc(t.title)}</h1>
    <div class="artist"><button class="artist-link" data-artist="${esc(t.artist)}">${esc(t.artist)}</button>${albumTitle ? `<span class="dot">,</span><button class="artist-link" ${albumAttrs(al ? al.artist : t.artist, albumTitle)}>${esc(albumTitle)}</button>` : ''}</div>
    ${genreChips(t.genres)}
    ${listenRow('tracks', t.artist, t.title)}</div>`;
  if (t.loading) h += '<div class="lin inline indet"><i></i></div>';
  else if (t.missing) h += `<div class="empty"><p>${esc(missingNote())}</p></div>`;
  else {
    const r = t.range || {};
    const focusOf = { artist: t.artist, title: t.title, album: albumTitle, albumArtist: al ? al.artist : t.artist };
    h += `<div class="tiles">
      ${tile('All time', num(t.plays), `plays, #${t.rank} of ${compact(t.tracks)} songs`, '', 'music', rankAttrs('tracks', 'all', focusOf))}
      ${tile(periodName(r), r.plays ? `#${r.rank}` : '–', r.plays ? plural(r.plays, 'play') : 'no plays', '', 'calendar', r.plays ? rankAttrs('tracks', r.id, focusOf) : '')}
      ${tile('First heard', esc(monthYear(t.first)), `last played ${esc(dateLabel(t.last))}`, '', 'history')}
      ${tile('Biggest day', plural(t.bestDay.count, 'play'), esc(dateLabel(t.bestDay.day)), '', 'fire')}
    </div>
    <p class="hint center">${pct(t.shareOfArtist)} of your ${esc(t.artist)} plays, on ${plural(t.days, 'day')}.</p>`;
    h += vsBlock(t.global, 'it', t.globalRank ? ` It's ${esc(t.artist)}'s #${t.globalRank} most played song there.` : '');
    h += lifeCharts(t, 'it', cover, (albumTitle || t.title) + t.artist);
    h += sitsViz(t);
    if (t.next && t.next.length) {
      h += `<div class="viz"><h2>What comes next</h2><span class="sub">What you played right after it</span><div class="rank">${t.next.map((x, i) =>
        `<button class="r" ${trackAttrs(x.artist, x.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)}</small></span><span class="v">${x.count}×</span></button>`).join('')}</div></div>`;
    }
    h += repeatViz(t);
  }
  keepHolo($('#trackBody'), h);
  tint($('#trackBody'), cover, (albumTitle || t.title) + t.artist);
}

// ---- one album ----

let ALBUM = null;
function openAlbum(artist, title, extra = {}) {
  if (!artist || !title) return;
  openPage('albumPage');
  ALBUM = { loading: true, artist, title, cover: extra.cover || '', dz: +extra.dz || 0, req: ++REQ };
  renderAlbum();
  send({ t: 'album', artist, title, range: statsRange, req: ALBUM.req });
  albumNeedsDz();
}
window.onAlbum = (a) => {
  ALBUM = deliver('albumPage', a.req, ALBUM, (s, shown) => {
    const next = { ...s, ...a, req: s.req, loading: false };
    if (shown) { const changed = !sameData(next, s); ALBUM = next; if (changed) renderAlbum(); }
    return next;
  });
};

function renderAlbum() {
  const a = ALBUM;
  if (!a || !isOpen('albumPage')) return;
  const info = a.info || {};
  const cover = info.cover || a.cover;
  const shelfId = info.owned || shelfIdOf(a.artist, a.title);
  let h = `<div class="hero page-hero"><i class="glow"></i>${holoCover(cover, a.title, a.artist, { owned: !!shelfId, back: !!shelfId, rel: shelfId, key: `album|${a.artist}|${a.title}|${shelfId}` })}
    <h1>${esc(a.title)}</h1>
    <div class="artist"><button class="artist-link" data-artist="${esc(a.artist)}">${esc(a.artist)}</button></div>
    ${badgeLine(shelfId)}
    ${genreChips(a.genres)}
    ${listenRow('albums', a.artist, a.title)}
    ${a.dzInfo && a.dzInfo.date ? `<div class="meta">${esc(a.dzInfo.date.slice(0, 4))}${a.dzInfo.label ? `, ${esc(a.dzInfo.label)}` : ''}</div>` : ''}
    ${shelfId ? `<div class="stats"><button class="chip link" data-rec="${shelfId}">${icon('album')}On your shelf</button></div>` : ''}</div>`;
  if (a.loading) h += '<div class="lin inline indet"><i></i></div>';
  else if (a.missing) {
    h += `<div class="empty small"><p>${(ST.history || {}).count > 0 ? 'You haven\'t played this one yet.' : esc(missingNote())}</p></div>`;
    h += tracklistHtml(a, cover);
  } else {
    const r = a.range || {};
    const focusOf = { artist: a.artist, title: a.title };
    const P = {};
    P.tiles = `<div class="tiles">
      ${tile('All time', num(a.plays), `plays, #${a.rank} of ${compact(a.albums)} albums`, '', 'music', rankAttrs('albums', 'all', focusOf))}
      ${tile(periodName(r), r.plays ? `#${r.rank}` : '–', r.plays ? plural(r.plays, 'play') : 'no plays', '', 'calendar', r.plays ? rankAttrs('albums', r.id, focusOf) : '')}
      ${tile('First heard', esc(monthYear(a.first)), `last played ${esc(dateLabel(a.last))}`, '', 'history')}
      ${tile('Biggest day', plural(a.bestDay.count, 'play'), esc(dateLabel(a.bestDay.day)), '', 'fire')}
    </div>`;
    const songs = a.dzInfo && !a.dzInfo.error && a.dzInfo.tracks ? a.dzInfo.tracks.length : 0;
    if (songs) P.tiles += `<p class="hint center">Plays count songs, not whole albums: ${num(a.plays)} song plays are about ${a.plays >= songs ? `${num(Math.round(a.plays / songs))} times` : 'less than once'} through its ${plural(songs, 'song')}.</p>`;
    const tl = a.trackList || [];
    P.period = periodBlock(r, a.rangeTracks, 'album', (x) => trackAttrs(a.artist, x.title, { album: a.title, cover }));
    P.shape = trackShapeViz(a);
    P.songs = tracklistHtml(a, cover);
    if (!P.songs && tl.length) {
      const max = Math.max(1, ...tl.map((x) => x.plays));
      P.songs = `<div class="viz"><h2>The songs you play</h2><span class="sub">${plural(tl.length, 'different song')} from this album</span><div class="rank">${tl.map((x, i) =>
        `<button class="r" ${trackAttrs(a.artist, x.title, { album: a.title, cover })}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b></span><span class="v cnt">${num(x.plays)}×</span><div class="bar"><i style="width:${(x.plays / max) * 100}%"></i></div></button>`).join('')}</div></div>`;
    }
    P.vs = vsBlock(a.global, 'it');
    P.clicked = clickedViz(a);
    P.river = a.river ? riverViz(a.river, { title: 'Its songs over time', other: 'Its other songs',
      sub: (s) => `Plays per ${a.river.unit} of each song since you first played it; the top ${a.river.artists.length} are <b>${s}%</b> of them.`,
      link: (n) => trackAttrs(a.artist, n, { album: a.title, cover }) }) : '';
    P.life = lifeCharts(a, 'it', cover, a.title + a.artist);
    h += tabbedHtml('albumPage', a.tab, ALBUM_TABS, ALBUM_LONG, P);
  }
  keepHolo($('#albumBody'), h);
  tint($('#albumBody'), cover, a.title + a.artist);
  syncPageTabs($('#albumPage'));
}

// ---- album and song pages: the album's shape for you, when it clicked, where a song sits, its runs on repeat ----

/** Your plays of each song of the album, in its order (Deezer's tracklist): [{ title, plays }]. */
function albumShape(a) {
  const dz = a.dzInfo && !a.dzInfo.error ? a.dzInfo : null;
  if (!dz || !dz.tracks) return [];
  const plays = {};
  (a.trackList || []).forEach((t) => { plays[looseKey(t.title)] = (plays[looseKey(t.title)] || 0) + t.plays; });
  return dz.tracks.map((t) => ({ title: t.title, plays: plays[looseKey(t.title)] || 0 }));
}
/** Track by track: a column per song in the album's order, the favorite in full color. */
function trackShapeViz(a) {
  const s = albumShape(a);
  const total = s.reduce((n, x) => n + x.plays, 0);
  if (s.length < 3 || total < 5) return '';
  const max = Math.max(...s.map((x) => x.plays));
  const fav = s.findIndex((x) => x.plays === max);
  const never = s.filter((x) => !x.plays).length;
  const half = Math.floor(s.length / 2);
  const front = s.slice(0, half).reduce((n, x) => n + x.plays, 0), back = s.slice(s.length - half).reduce((n, x) => n + x.plays, 0);
  const lean = front >= back * 1.6 ? 'you play its first half far more than its second'
    : back >= front * 1.6 ? 'you play its second half more than its first'
      : never ? (never === 1 ? 'one song you\'ve never played' : `${never} songs you've never played`) : 'you\'ve played every song on it';
  const step = s.length > 16 ? 5 : 1;
  return `<div class="viz"><h2>Track by track</h2><span class="sub">Your plays of each song, in the album's order: its shape for you</span>
    <div class="shape">${s.map((x, i) => `<span data-col="tt" data-i="${i}"${i === fav ? ' class="top"' : ''}><i style="height:${x.plays ? (6 + (x.plays / max) * 94).toFixed(1) : 0}%"${x.plays ? '' : ' class="none"'}></i></span>`).join('')}</div>
    <div class="shape-n">${s.map((x, i) => `<span>${(i + 1) % step === 0 || i === 0 ? i + 1 : ''}</span>`).join('')}</div>
    <div class="readout"><b>${esc(s[fav].title)}</b> (${fav + 1}) is your favorite, ${plural(max, 'play')}; ${lean}.</div></div>`;
}
const shapeText = (a, i) => { const x = albumShape(a)[i]; return x ? `${i + 1}. <b>${esc(x.title)}</b>: ${x.plays ? plural(x.plays, 'play') : 'never played'}` : ''; };

/** The week [i] weeks after the one of [from] (calendar days, so a DST change doesn't move it). */
const weekAfter = (from, i) => { const d = new Date(from); return new Date(d.getFullYear(), d.getMonth(), d.getDate() + i * 7); };
/** The first week an album really took off: half its busiest week, three plays at least. */
function clickWeek(w) {
  const max = Math.max(...w);
  return w.findIndex((x) => x >= Math.max(3, max * 0.5));
}
/** When it clicked: all your plays of the album added up since the first one, and the week it took off. */
function clickedViz(a) {
  const w = a.weeks || [];
  let c = 0;
  const cum = w.map((x) => (c += x));
  if (w.length < 6 || c < 12) return '';
  const k = clickWeek(w);
  const W = 320, H = 120, n = w.length;
  const x = (i) => (i / (n - 1)) * W, y = (v) => H - 3 - (v / c) * (H - 12);
  const line = smoothPath(cum.map((v, i) => [x(i), y(v)]));
  const id = `cg${++svgSeq}`;
  const when = (i) => weekAfter(a.weeksFrom, i).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
  const span = k < 9 ? plural(k, 'week') : plural(Math.round(k / 4.35), 'month');
  const sub = k <= 1 ? `It clicked right away: <b>${plural(w[0] + (k === 1 ? w[1] : 0), 'play')}</b> in its first ${k ? 'two weeks' : 'week'}.`
    : `It sat for ${span} after your first play (${plural(cum[k] - w[k], 'play')}), then <b>${plural(w[k], 'play')}</b> in the week of ${esc(when(k))}.`;
  const px = (x(k) / W) * 100, py = (y(cum[k]) / H) * 100;
  const step = Math.max(1, Math.ceil(n / 4));
  return `<div class="viz"><h2>When it clicked</h2><span class="sub">All your plays added up, week by week since the first one</span>
    <div class="area">
      <svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" aria-hidden="true">
        <defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:var(--tint);stop-opacity:.4"/><stop offset="1" style="stop-color:var(--tint);stop-opacity:0"/></linearGradient></defs>
        <path d="${line} L${W},${H} L0,${H} Z" fill="url(#${id})"/>
        <path d="${line}" fill="none" style="stroke:var(--tint)" stroke-width="2.5" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
      </svg>
      <i class="peak" style="left:${px}%;top:${py}%"></i>
      <span class="peak-label${px > 62 ? ' end' : px < 20 ? ' start' : ''}" style="left:${px}%;top:${py}%">${k <= 1 ? 'right away' : `clicked: ${esc(weekAfter(a.weeksFrom, k).toLocaleDateString([], { month: 'short', year: 'numeric' }))}`}</span>
      <div class="hits">${w.map((v, i) => `<div data-col="ck" data-i="${i}"></div>`).join('')}</div>
    </div>
    <div class="xl">${w.map((v, i) => i).filter((i) => i % step === 0).map((i) => `<span>${esc(weekAfter(a.weeksFrom, i).toLocaleDateString([], { month: 'short', year: '2-digit' }))}</span>`).join('')}</div>
    <div class="readout">${sub}</div></div>`;
}
function clickedText(a, i) {
  const w = a.weeks || [];
  const cum = w.slice(0, i + 1).reduce((n, v) => n + v, 0);
  return `Week of ${esc(weekAfter(a.weeksFrom, i).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' }))}: <b>${plural(w[i] || 0, 'play')}</b>, ${num(cum)} in all by then`;
}

/** Where it sits: how often a song opens your listening sessions, sits in their middle or closes them. */
function sitsViz(t) {
  const s = t.sits;
  const n = s ? s.open + s.middle + s.close : 0;
  if (n < 5) return '';
  const open = s.open / n, close = s.close / n;
  const sub = open >= Math.max(0.15, 2 * s.baseOpen) ? `<b>Often your opener</b>: ${pct(open)} of its plays start a listening session; a song usually does ${pct(s.baseOpen)}.`
    : close >= Math.max(0.15, 2 * s.baseClose) ? `<b>Often your closer</b>: ${pct(close)} of its plays end a listening session; a song usually does ${pct(s.baseClose)}.`
      : 'Mostly <b>somewhere in the middle</b> of a listening session.';
  const row = (label, v, base, on) => `<div class="sit${on ? ' on' : ''}"><span>${label}</span><span class="sbar"><i style="width:${(v * 100).toFixed(1)}%"></i>${base != null ? `<b style="left:${(base * 100).toFixed(1)}%" title="a song usually"></b>` : ''}</span><span class="v">${pct(v)}</span></div>`;
  const most = Math.max(open, close, s.middle / n);
  return `<div class="viz"><h2>Where it sits</h2><span class="sub">In your listening sessions (a break of half an hour ends one); the tick: any song</span>
    <div class="sits">${row('Opener', open, s.baseOpen, open === most)}${row('In the middle', s.middle / n, null, s.middle / n === most)}${row('Closer', close, s.baseClose, close === most)}</div>
    <div class="readout">${sub}${s.alone ? ` ${plural(s.alone, 'time')} it was all you played.` : ''}</div></div>`;
}

const RUNS_SHOWN = 40;
/** Repeat runs: each time it played back to back, a column of its plays in a row (the latest 40). */
function repeatViz(t) {
  const runs = t.runs || [];
  if (!runs.length) return '';
  const best = runs.reduce((m, r) => (r.n > m.n ? r : m));
  const days = new Set(runs.map((r) => new Date(r.at).toDateString())).size;
  const shown = runs.slice(-RUNS_SHOWN);
  const tall = Math.min(12, Math.max(...shown.map((r) => r.n)));
  const sub = runs.length === 1 ? `<b>${best.n} times in a row</b> on ${esc(dateLabel(best.at))}.`
    : `<b>${best.n} times in a row</b> on ${esc(dateLabel(best.at))}, your longest; on repeat on ${plural(days, 'day')}.`;
  return `<div class="viz"><h2>Repeat runs</h2><span class="sub">Each time it played back to back${runs.length > RUNS_SHOWN ? ` (the latest ${RUNS_SHOWN})` : ''}: one block per play</span>
    <div class="runs" style="--rows:${tall}">${shown.map((r, i) => `<span data-col="rr" data-i="${i}"${r.n === best.n ? ' class="top"' : ''}>${'<i></i>'.repeat(Math.min(r.n, 12))}${r.n > 12 ? `<em>${r.n}</em>` : ''}</span>`).join('')}</div>
    <div class="readout">${sub}</div></div>`;
}
const runText = (t, i) => { const r = (t.runs || []).slice(-RUNS_SHOWN)[i]; return r ? `${esc(dateLabel(r.at))}: <b>${r.n} times in a row</b>` : ''; };

// ---- the atlas: your artists as a universe, how you found them, the landscape of your top ones ----

let ATLAS = null;
let atlasView = 'universe';
const TREE_OPEN = new Set();
let landSel = -1;
/** The universe on its canvas: the camera, what is picked, and the fingers on it. */
const UNI = { cam: { x: 0, y: 0, k: 1 }, sel: -1, cluster: -1, year: 0, src: null, nodes: [], adj: [], w: 0, h: 0, dpr: 1, raf: 0, fly: null, pts: new Map(), gesture: null, lastTap: null };
/** Constellations take the record's inks, in order; loose stars are gray. */
const starColor = (c) => (c >= 0 && c < INKS_ON_VINYL.length ? INKS_ON_VINYL[c] : '#8b8a99');

function openAtlas(view) {
  openPage('atlasPage');
  if (view) atlasView = view;
  renderAtlas();
  if (!ATLAS) send({ t: 'atlas' });
}
window.onAtlas = (a) => {
  ATLAS = a;
  if (isOpen('atlasPage')) renderAtlas();
};

function renderAtlas() {
  if (!isOpen('atlasPage')) return;
  const body = $('#atlasBody');
  $$('#atlasSeg button').forEach((b) => b.classList.toggle('on', b.dataset.av === atlasView));
  const a = ATLAS;
  if (!a) { body.innerHTML = '<div class="lin inline indet"><i></i></div>'; return; }
  if (a.empty) { body.innerHTML = '<div class="empty"><p>Import your Last.fm history on the Stats tab first.</p></div>'; return; }
  if (atlasView === 'tree') body.innerHTML = treeHtml(a.tree);
  else if (atlasView === 'land') body.innerHTML = landHtml(a.landscape);
  else {
    body.innerHTML = universeHtml(a.universe);
    setupUniverse(a.universe);
  }
}

// -- the universe --

function universeHtml(u) {
  if (!u || !u.nodes.length) return '<div class="empty"><p>Not enough plays yet for a universe.</p></div>';
  const cl = u.clusters || [];
  const tot = Math.max(1, u.plays);
  const big = cl[0];
  const bridges = u.bridges || [];
  const years = [...new Set(u.nodes.flatMap((n) => Object.keys(n.years || {})))].sort();
  return `<p class="uni-intro">Your ${num(u.nodes.length)} most played artists as stars${cl.length ? ` in ${plural(cl.length, 'constellation')}` : ''}: close together when you play them back to back or in the same months.</p>
    <div class="uni-wrap" id="uniWrap"><canvas id="uniCanvas" aria-label="Your artists as a universe of stars"></canvas>
      <div class="uni-find">${icon('search')}<input id="uniFind" type="search" placeholder="Find an artist" autocomplete="off" enterkeyhint="search"><div id="uniFound"></div></div>
      <div class="uni-zoom"><button data-uz="in" aria-label="Zoom in">+</button><button data-uz="out" aria-label="Zoom out">−</button><button data-uz="fit" aria-label="Show everything">All</button></div>
      <p class="uni-hint">Drag, pinch, tap near a star</p>
      <div class="uni-card" id="uniCard" hidden></div>
    </div>
    ${cl.length ? `<div class="chip-row uni-cons">${cl.map((c, k) => `<button class="chip${UNI.cluster === k ? ' on' : ''}" data-uni-c="${k}"><i class="dot" style="background:${starColor(k)}"></i>${esc(c.name)} &amp; co.<small class="n">${pct(c.plays / tot)}</small></button>`).join('')}</div>` : ''}
    ${years.length > 1 ? `<div class="chip-row uni-years"><button class="chip${!UNI.year ? ' on' : ''}" data-uni-y="0">All years</button>${years.map((y) => `<button class="chip${UNI.year === +y ? ' on' : ''}" data-uni-y="${y}">${y}</button>`).join('')}</div>` : ''}
    <p class="uni-intro">${big ? `The biggest constellation, <b>${esc(big.name)} &amp; co.</b>${big.genre ? ` (mostly ${esc(big.genre)})` : ''}, holds ${pct(big.plays / tot)} of these plays.` : ''}${bridges.length ? ` Bridges between constellations: ${bridges.map((x) => `<button class="link" data-uni-go="${esc(x.name)}">${esc(x.name)}</button>`).join(', ')}.` : ''} Colors are constellations; a star's size is its plays.</p>`;
}

function setupUniverse(u) {
  const cv = $('#uniCanvas');
  const wrap = $('#uniWrap');
  if (!cv || !wrap) return;
  const W = wrap.clientWidth;
  const H = Math.round(Math.min(window.innerHeight * 0.6, W * 1.2));
  wrap.style.height = `${H}px`;
  const dpr = Math.min(2.5, window.devicePixelRatio || 1);
  cv.width = Math.round(W * dpr);
  cv.height = Math.round(H * dpr);
  cv.style.width = `${W}px`;
  cv.style.height = `${H}px`;
  Object.assign(UNI, { w: W, h: H, dpr });
  if (UNI.src !== u) {
    UNI.src = u;
    UNI.nodes = u.nodes;
    UNI.maxPlays = Math.max(1, ...u.nodes.map((n) => n.plays));
    UNI.adj = u.nodes.map(() => []);
    u.links.forEach(([a, b, t, s, w]) => { UNI.adj[a].push({ o: b, t, s, w }); UNI.adj[b].push({ o: a, t, s, w }); });
    UNI.adj.forEach((l) => l.sort((p, q) => q.w - p.w));
    UNI.cam = { x: 0, y: 0, k: 1 };
    UNI.sel = -1;
    UNI.cluster = -1;
    UNI.year = 0;
    // A sky of faint dust behind the stars, the same every time.
    let s = 7;
    const rnd = () => { s = (s * 16807) % 2147483647; return s / 2147483647; };
    UNI.dust = Array.from({ length: 140 }, () => [rnd(), rnd(), 0.3 + rnd() * 0.9, 0.15 + rnd() * 0.35]);
  }
  bindUniverse(cv);
  uniCard();
  drawUni();
}

const uniScale = () => (Math.min(UNI.w, UNI.h) / 2) * 0.9;
const uniToScreen = (x, y) => [UNI.w / 2 + (x - UNI.cam.x) * uniScale() * UNI.cam.k, UNI.h / 2 + (y - UNI.cam.y) * uniScale() * UNI.cam.k];
/** Plays that count for a star: all of them, or one year's. */
const uniPlays = (n) => (UNI.year ? (n.years || {})[UNI.year] || 0 : n.plays);
function uniRadius(n) {
  const p = uniPlays(n);
  if (!p) return 1.6;
  return (2 + 10 * Math.sqrt(p / UNI.maxPlays)) * (0.8 + 0.2 * Math.min(4, UNI.cam.k));
}
/** Whether a star is in focus: the picked one and its links, the picked constellation, and stars played in the picked year. */
function uniLit(i) {
  const n = UNI.nodes[i];
  if (UNI.year && !uniPlays(n)) return false;
  if (UNI.sel >= 0) return i === UNI.sel || UNI.adj[UNI.sel].some((l) => l.o === i);
  if (UNI.cluster >= 0) return n.c === UNI.cluster;
  return true;
}

function drawUni() { if (!UNI.raf) UNI.raf = requestAnimationFrame(uniFrame); }
function uniFrame(now) {
  UNI.raf = 0;
  const cv = $('#uniCanvas');
  if (!cv || !isOpen('atlasPage')) return;
  if (UNI.fly) {
    const f = UNI.fly;
    const p = Math.min(1, (now - f.t0) / f.dur);
    const e = 1 - Math.pow(1 - p, 3);
    UNI.cam = { x: f.from.x + (f.to.x - f.from.x) * e, y: f.from.y + (f.to.y - f.from.y) * e, k: f.from.k * Math.pow(f.to.k / f.from.k, e) };
    if (p < 1) drawUni(); else UNI.fly = null;
  }
  const { w: W, h: H, dpr, nodes } = UNI;
  const ctx = cv.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.fillStyle = '#0b0d17';
  ctx.fillRect(0, 0, W, H);
  // dust drifts a little with the camera, for depth
  for (const [dx, dy, r, a] of UNI.dust) {
    const x = (((dx * W - UNI.cam.x * 18) % W) + W) % W, y = (((dy * H - UNI.cam.y * 18) % H) + H) % H;
    ctx.fillStyle = `rgba(230,225,255,${a})`;
    ctx.fillRect(x, y, r, r);
  }
  const P = nodes.map((n) => uniToScreen(n.x, n.y));
  const lit = nodes.map((_, i) => uniLit(i));
  const R = nodes.map((n) => uniRadius(n));
  // links
  ctx.lineCap = 'round';
  for (const [a, b, , , wgt] of UNI.src.links) {
    const on = lit[a] && lit[b];
    const picked = UNI.sel >= 0 && (a === UNI.sel || b === UNI.sel);
    ctx.strokeStyle = picked ? 'rgba(255,240,215,0.75)' : on ? `rgba(200,205,255,${(0.06 + 0.26 * wgt).toFixed(3)})` : 'rgba(200,205,255,0.03)';
    ctx.lineWidth = picked ? 1.6 : 0.5 + wgt;
    ctx.beginPath();
    ctx.moveTo(P[a][0], P[a][1]);
    ctx.lineTo(P[b][0], P[b][1]);
    ctx.stroke();
  }
  // stars, small first so the big ones sit on top
  const order = nodes.map((_, i) => i).sort((p, q) => R[p] - R[q]);
  for (const i of order) {
    const [x, y] = P[i];
    const r = R[i];
    if (x < -40 || y < -40 || x > W + 40 || y > H + 40) continue;
    const col = starColor(nodes[i].c);
    ctx.globalAlpha = lit[i] ? 1 : 0.16;
    if (lit[i] && r > 3) {
      const g = ctx.createRadialGradient(x, y, 0, x, y, r * 3);
      g.addColorStop(0, col + '66');
      g.addColorStop(1, col + '00');
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(x, y, r * 3, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.fillStyle = col;
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = 'rgba(255,255,255,0.55)';
    ctx.beginPath();
    ctx.arc(x - r * 0.25, y - r * 0.25, r * 0.35, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.globalAlpha = 1;
  if (UNI.sel >= 0) {
    const [x, y] = P[UNI.sel];
    ctx.strokeStyle = '#fff6e4';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(x, y, R[UNI.sel] + 5, 0, Math.PI * 2);
    ctx.stroke();
  }
  // names: the picked star and its links first, then the biggest, wherever there is room
  ctx.font = '500 11px "DM Mono", ui-monospace, monospace';
  ctx.textBaseline = 'middle';
  const placed = [];
  const want = (i) => i === UNI.sel || (UNI.sel >= 0 && lit[i]) || (lit[i] && (R[i] >= 6.5 || (UNI.cam.k >= 2 && R[i] >= 3.5)));
  const labelOrder = nodes.map((_, i) => i).filter(want)
    .sort((p, q) => (q === UNI.sel) - (p === UNI.sel) || R[q] - R[p]);
  for (const i of labelOrder.slice(0, 60)) {
    const [x, y] = P[i];
    const t = nodes[i].name.length > 22 ? nodes[i].name.slice(0, 21) + '…' : nodes[i].name;
    const tw = ctx.measureText(t).width;
    const lx = x + R[i] + 5, ly = y;
    const box = [lx - 2, ly - 8, lx + tw + 2, ly + 8];
    if (box[2] > W - 4 || box[0] < 0 || ly < 10 || ly > H - 10) continue;
    if (placed.some((b) => b[0] < box[2] && box[0] < b[2] && b[1] < box[3] && box[1] < b[3])) continue;
    placed.push(box);
    ctx.lineWidth = 3;
    ctx.strokeStyle = 'rgba(11,13,23,0.85)';
    ctx.strokeText(t, lx, ly);
    ctx.fillStyle = i === UNI.sel ? '#fff6e4' : 'rgba(236,232,250,0.9)';
    ctx.fillText(t, lx, ly);
  }
}

/** Moves the camera to [to] smoothly. */
function uniFly(to) {
  UNI.fly = { from: { ...UNI.cam }, to, t0: performance.now(), dur: 480 };
  drawUni();
}
/** Picks a star and brings it to the upper part of the map, above the card. */
function uniSelect(i) {
  UNI.sel = i;
  UNI.cluster = -1;
  const n = UNI.nodes[i];
  const k = Math.max(UNI.cam.k, 1.8);
  uniFly({ x: n.x, y: n.y + (UNI.h * 0.17) / (uniScale() * k), k });
  uniCard();
  $$('.uni-cons .chip').forEach((c) => c.classList.remove('on'));
}
function uniClear() {
  UNI.sel = -1;
  UNI.cluster = -1;
  $$('.uni-cons .chip').forEach((c) => c.classList.remove('on'));
  uniCard();
  drawUni();
}
function uniShowCluster(c) {
  UNI.sel = -1;
  UNI.cluster = UNI.cluster === c ? -1 : c;
  $$('.uni-cons .chip').forEach((b) => b.classList.toggle('on', +b.dataset.uniC === UNI.cluster));
  if (UNI.cluster < 0) { uniCard(); uniFly({ x: 0, y: 0, k: 1 }); return; }
  const m = UNI.nodes.filter((n) => n.c === c);
  const xs = m.map((n) => n.x), ys = m.map((n) => n.y);
  const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const S = uniScale();
  const k = Math.max(0.8, Math.min(6, (UNI.w * 0.8) / ((x1 - x0) * S + 1), (UNI.h * 0.5) / ((y1 - y0) * S + 1)));
  uniCard();
  uniFly({ x: (x0 + x1) / 2, y: (y0 + y1) / 2 + (UNI.h * 0.17) / (S * k), k });
}
function uniZoomAt(x, y, f) {
  const S = uniScale();
  const k = Math.max(0.7, Math.min(10, UNI.cam.k * f));
  const wx = UNI.cam.x + (x - UNI.w / 2) / (S * UNI.cam.k), wy = UNI.cam.y + (y - UNI.h / 2) / (S * UNI.cam.k);
  UNI.fly = null;
  UNI.cam = { k, x: wx - (x - UNI.w / 2) / (S * k), y: wy - (y - UNI.h / 2) / (S * k) };
  drawUni();
}
/** A tap picks the nearest star within a finger's width, so stars never need to be hit exactly. */
function uniTap(x, y) {
  const now = performance.now();
  const last = UNI.lastTap;
  UNI.lastTap = { x, y, t: now };
  if (last && now - last.t < 320 && Math.hypot(x - last.x, y - last.y) < 30) { UNI.lastTap = null; uniZoomAt(x, y, 2); return; }
  let best = -1, bd = Infinity;
  UNI.nodes.forEach((n, i) => {
    const [sx, sy] = uniToScreen(n.x, n.y);
    const d = Math.hypot(sx - x, sy - y) - uniRadius(n) * (uniLit(i) ? 1 : 0.3);
    if (d < bd) { bd = d; best = i; }
  });
  if (best >= 0 && bd <= 30) uniSelect(best);
  else if (UNI.sel >= 0 || UNI.cluster >= 0) uniClear();
}
function bindUniverse(cv) {
  const at = (e) => { const r = cv.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };
  const two = () => [...UNI.pts.values()];
  cv.onpointerdown = (e) => {
    cv.setPointerCapture(e.pointerId);
    const p = at(e);
    UNI.pts.set(e.pointerId, { ...p, x0: p.x, y0: p.y, t0: performance.now() });
    UNI.fly = null;
    if (UNI.pts.size === 1) UNI.gesture = { kind: 'pan', cam: { ...UNI.cam }, moved: false };
    else if (UNI.pts.size === 2) {
      const [a, b] = two();
      UNI.gesture = { kind: 'pinch', cam: { ...UNI.cam }, d0: Math.max(10, Math.hypot(a.x - b.x, a.y - b.y)), m0: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, moved: true };
    }
  };
  cv.onpointermove = (e) => {
    const p = UNI.pts.get(e.pointerId);
    const g = UNI.gesture;
    if (!p || !g) return;
    Object.assign(p, at(e));
    const S = uniScale();
    if (g.kind === 'pan' && UNI.pts.size === 1) {
      const dx = p.x - p.x0, dy = p.y - p.y0;
      if (!g.moved && Math.hypot(dx, dy) > 7) g.moved = true;
      if (g.moved) {
        UNI.cam = { k: g.cam.k, x: g.cam.x - dx / (S * g.cam.k), y: g.cam.y - dy / (S * g.cam.k) };
        drawUni();
      }
    } else if (g.kind === 'pinch' && UNI.pts.size === 2) {
      const [a, b] = two();
      const k = Math.max(0.7, Math.min(10, (g.cam.k * Math.hypot(a.x - b.x, a.y - b.y)) / g.d0));
      const m = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      const wx = g.cam.x + (g.m0.x - UNI.w / 2) / (S * g.cam.k), wy = g.cam.y + (g.m0.y - UNI.h / 2) / (S * g.cam.k);
      UNI.cam = { k, x: wx - (m.x - UNI.w / 2) / (S * k), y: wy - (m.y - UNI.h / 2) / (S * k) };
      drawUni();
    }
  };
  cv.onpointerup = cv.onpointercancel = (e) => {
    const p = UNI.pts.get(e.pointerId);
    const g = UNI.gesture;
    UNI.pts.delete(e.pointerId);
    if (e.type === 'pointerup' && g && g.kind === 'pan' && !g.moved && p && performance.now() - p.t0 < 500) uniTap(p.x, p.y);
    if (!UNI.pts.size) UNI.gesture = null;
    else {
      // One finger left after a pinch: carry on panning from here.
      const q = two()[0];
      Object.assign(q, { x0: q.x, y0: q.y });
      UNI.gesture = { kind: 'pan', cam: { ...UNI.cam }, moved: true };
    }
  };
  cv.onwheel = (e) => { e.preventDefault(); const p = at(e); uniZoomAt(p.x, p.y, e.deltaY < 0 ? 1.25 : 0.8); };
  const find = $('#uniFind');
  if (find) {
    find.oninput = () => {
      const q = norm(find.value.trim());
      const hits = q ? UNI.nodes.map((n, i) => [n, i]).filter(([n]) => norm(n.name).includes(q)).slice(0, 6) : [];
      $('#uniFound').innerHTML = hits.map(([n, i]) => `<button data-uni-i="${i}"><i class="dot" style="background:${starColor(n.c)}"></i>${esc(n.name)}<small>${compact(n.plays)}</small></button>`).join('');
    };
  }
}

/** The card over the bottom of the map: the picked star (its links as buttons to hop along) or the picked constellation. */
function uniCard() {
  const card = $('#uniCard');
  if (!card) return;
  const u = UNI.src;
  $('#uniWrap').classList.toggle('carded', UNI.sel >= 0 || UNI.cluster >= 0);
  if (UNI.sel >= 0) {
    const n = u.nodes[UNI.sel];
    const c = u.clusters[n.c];
    const o = n.origin || {};
    const how = o.how === 'after' ? `Found right after <button class="link" data-uni-go="${esc(o.from)}">${esc(o.from)}</button>, ${esc(dateLabel(o.at))}.`
      : o.how === 'start' ? 'With you since your history starts.' : `Out of the blue, ${esc(dateLabel(o.at))}.`;
    const ba = [n.before ? `after <b>${esc(n.before.name)}</b>` : '', n.after ? `before <b>${esc(n.after.name)}</b>` : ''].filter(Boolean).join(' and ');
    const near = UNI.adj[UNI.sel].slice(0, 8).map((l) => {
      const m = u.nodes[l.o];
      return `<button class="chip" data-uni-i="${l.o}"><i class="dot" style="background:${starColor(m.c)}"></i>${esc(m.name)}<small class="n">${l.t >= 2 ? `${l.t}× in a row` : 'same months'}</small></button>`;
    }).join('');
    card.innerHTML = `<button class="icon-btn x" data-uni-x aria-label="Close">${icon('close')}</button>
      <button class="uh" data-artist="${esc(n.name)}">${coverHtml(n.photo, n.name, n.name, 'tiny round')}<span><b>${esc(n.name)}</b><small>${[n.genre, plural(UNI.year ? uniPlays(n) : n.plays, 'play') + (UNI.year ? ` in ${UNI.year}` : ''), c ? `${c.name} & co.` : 'a loose star'].filter(Boolean).map(esc).join(', ')}</small></span>${icon('chevron-right')}</button>
      <p>${how}${ba ? ` Usually played ${ba}.` : ''}</p>
      ${near ? `<div class="chip-row">${near}</div>` : '<p class="hint">No strong links: a star of its own.</p>'}`;
    card.hidden = false;
  } else if (UNI.cluster >= 0) {
    const c = u.clusters[UNI.cluster];
    const members = u.nodes.map((n, i) => [n, i]).filter(([n]) => n.c === UNI.cluster);
    card.innerHTML = `<button class="icon-btn x" data-uni-x aria-label="Close">${icon('close')}</button>
      <div class="uh"><i class="dot big" style="background:${starColor(UNI.cluster)}"></i><span><b>${esc(c.name)} &amp; co.</b><small>${[plural(c.size, 'artist'), c.genre ? `mostly ${c.genre}` : '', `${pct(c.plays / Math.max(1, u.plays))} of these plays`].filter(Boolean).map(esc).join(', ')}</small></span></div>
      <div class="chip-row">${members.slice(0, 12).map(([n, i]) => `<button class="chip" data-uni-i="${i}">${esc(n.name)}<small class="n">${compact(n.plays)}</small></button>`).join('')}</div>`;
    card.hidden = false;
  } else card.hidden = true;
}

// -- the family tree --

let TREE = null;
function treeIndex(t) {
  if (TREE && TREE.src === t) return TREE;
  const byId = new Map(t.nodes.map((n) => [n.id, n]));
  const kids = new Map();
  t.nodes.forEach((n) => { if (n.p >= 0 && byId.has(n.p)) { if (!kids.has(n.p)) kids.set(n.p, []); kids.get(n.p).push(n.id); } });
  kids.forEach((l) => l.sort((a, b) => byId.get(b).plays - byId.get(a).plays));
  TREE = { src: t, byId, kids };
  if (!TREE_OPEN.size && t.gateways.length) TREE_OPEN.add(t.gateways[0].id);
  return TREE;
}
function treeRow(id, depth) {
  const T = TREE;
  const n = T.byId.get(id);
  const kids = T.kids.get(id) || [];
  const open = TREE_OPEN.has(id);
  const gw = depth === 0 ? (TREE.src.gateways.find((g) => g.id === id) || {}) : null;
  const meta = [depth ? `found ${monthYear(n.at)}` : '', n.kept ? plural(n.plays, 'play') : 'a stepping stone', n.d ? `led to ${n.d}` : ''].filter(Boolean).join(', ');
  return `<div class="tnode${n.kept ? '' : ' via'}" style="--depth:${Math.min(depth, 7)}">
    <div class="trow">${kids.length ? `<button class="tt" data-tree="${id}" aria-expanded="${open}" aria-label="${open ? 'Fold' : 'Unfold'}">${icon(open ? 'down' : 'chevron-right')}</button>` : '<span class="tt"></span>'}
      ${gw ? coverHtml(gw.photo, n.name, n.name, 'tiny round') : ''}<button class="tn" data-artist="${esc(n.name)}"><b>${esc(n.name)}</b><small>${esc(meta)}</small></button></div>
    ${open && kids.length ? `<div class="tkids">${kids.map((k) => treeRow(k, depth + 1)).join('')}</div>` : ''}</div>`;
}
function treeHtml(t) {
  if (!t || !t.nodes || !t.nodes.length) return '<div class="empty"><p>Not enough plays yet for a family tree.</p></div>';
  treeIndex(t);
  const intro = `Of the ${num(t.kept)} artists you kept playing (5 plays or more), <b>${num(t.after)}</b> came right after another artist in the same listening session, ${num(t.blue)} came out of the blue (a search, an album, a tip), and ${num(t.start)} were with you from the start of your history.`;
  return `<p class="uni-intro">${intro}</p>
    <div class="viz"><h2>Gateways</h2><span class="sub">The artists that led you to the most others you kept playing. "Right after" means the very next song; with radio or autoplay, that was the app's pick.</span>
    <div class="tree">${t.gateways.map((g) => treeRow(g.id, 0)).join('') || '<p class="hint">No artist has led you to two or more others yet.</p>'}</div></div>`;
}

// -- the landscape --

const LAND_KINDS = {
  obsession: ['var(--a2)', 'Obsession', 'most plays within a few months'],
  companion: ['var(--a3)', 'Companion', 'with you for a year or more'],
  comeback: ['var(--a7)', 'Comeback', 'back after six quiet months or more'],
  phase: ['var(--a1)', 'Phase', 'a stretch of months, then less'],
};
function spanWords(m) {
  const y = Math.floor(m / 12), r = m % 12;
  return [y ? plural(y, 'year') : '', r ? plural(r, 'month') : ''].filter(Boolean).join(' and ') || 'a month';
}
function landKindText(s) {
  if (s.kind === 'obsession') return `${s.months <= 1 ? 'a one-month' : `a ${s.months}-month`} obsession, ${monthYear(s.peakAt)}`;
  if (s.kind === 'companion') return `with you for ${spanWords(s.months)}`;
  if (s.kind === 'comeback') return `back after ${s.months} months away`;
  return `a phase of ${plural(s.months, 'busy month')}`;
}
function landHtml(l) {
  const S = (l && l.series) || [];
  if (!S.length) return '<div class="empty"><p>Not enough plays yet for a landscape.</p></div>';
  const n = Math.max(2, l.months);
  const W = 360, peakH = 70, gap = 25, top = 22;
  const H = top + peakH + gap * (S.length - 1) + 26;
  const X = (m) => 10 + (m / (n - 1)) * (W - 20);
  const smooth = (c) => c.map((v, i) => 0.25 * (c[i - 1] ?? v) + 0.5 * v + 0.25 * (c[i + 1] ?? v));
  const max = Math.max(1, ...S.map((s) => Math.max(...smooth(s.counts))));
  let svg = '', names = '';
  S.forEach((s, i) => {
    const base = top + peakH + i * gap;
    const c = smooth(s.counts);
    const pts = c.map((v, m) => [X(m), base - (v / max) * peakH]);
    const d = `${smoothPath(pts)} L${X(n - 1)},${base} L${X(0)},${base} Z`;
    const [col] = LAND_KINDS[s.kind] || LAND_KINDS.phase;
    const dim = landSel >= 0 && landSel !== i;
    const haze = 0.45 + 0.5 * (i / Math.max(1, S.length - 1));
    const pk = c.indexOf(Math.max(...c));
    const lx = Math.max(34, Math.min(W - 34, X(pk)));
    svg += `<g class="mtn${landSel === i ? ' on' : ''}" data-land="${i}"><path d="${d}" class="occl"/><path d="${d}" style="fill:${col};fill-opacity:${(dim ? 0.12 : haze).toFixed(2)};stroke:${col};stroke-opacity:${dim ? 0.3 : 1}"/></g>`;
    // Names go on top of all the mountains, so the ones in front don't hide them.
    names += `<text x="${lx.toFixed(1)}" y="${(base - (c[pk] / max) * peakH - 5).toFixed(1)}" class="${dim ? 'dim' : ''}" data-land="${i}">${esc(s.name.length > 18 ? s.name.slice(0, 17) + '…' : s.name)}</text>`;
  });
  const y0 = new Date(l.monthsFrom).getFullYear();
  const m0 = new Date(l.monthsFrom).getMonth();
  let ticks = '';
  // Each January gets its year; the first month too when the next January is far enough away.
  for (let m = 0; m < n; m++) if ((m0 + m) % 12 === 0 || (m === 0 && m0 <= 8)) ticks += `<text x="${X(m).toFixed(1)}" y="${H - 6}" class="yr">${y0 + Math.floor((m0 + m) / 12)}</text>`;
  const kinds = Object.entries(LAND_KINDS).map(([k, [col, name, what]]) => [k, col, name, what, S.filter((s) => s.kind === k).length]).filter((x) => x[4]);
  const intro = `Your ${S.length} most played artists as mountains: height is plays per month, from your first scrobble to now, the earliest peaks at the back. ${kinds.map(([, , name, , cnt]) => `${cnt} ${name.toLowerCase()}${cnt === 1 ? '' : 's'}`).join(', ')}.`;
  return `<p class="uni-intro">${intro}</p>
    <div class="chip-row land-kinds">${kinds.map(([, col, name, what]) => `<span class="chip static"><i class="dot" style="background:${col}"></i>${name}<small class="n">${what}</small></span>`).join('')}</div>
    <div class="viz land"><svg viewBox="0 0 ${W} ${H}" class="landscape">${svg}${names}${ticks}</svg>
    <div class="rank land-rows">${S.map((s, i) => `<button class="r${landSel === i ? ' on' : ''}" data-land="${i}"><span class="n"><i class="dot" style="background:${(LAND_KINDS[s.kind] || LAND_KINDS.phase)[0]}"></i></span><span class="t"><b>${esc(s.name)}</b><small>${esc(landKindText(s))}</small></span><span class="v">${compact(s.plays)}</span></button>
      ${landSel === i ? `<div class="land-det">Peaked in ${esc(monthYear(s.peakAt))} with ${plural(s.peak, 'play')}; first played ${esc(monthYear(s.from))}. <button class="link" data-artist="${esc(s.name)}">Open ${esc(s.name)}</button></div>` : ''}`).join('')}</div></div>`;
}

/** How you found an artist, back along the chain of first plays, and who they led you to. */
function originHtml(a) {
  const o = a.origin;
  if (!o) return '';
  const line = o.how === 'after' ? `Right after <button class="link" data-artist="${esc(o.from)}">${esc(o.from)}</button>, ${esc(dateLabel(o.at))}.`
    : o.how === 'start' ? 'With you since your Last.fm history starts.' : `Out of the blue on ${esc(dateLabel(o.at))}: nothing played right before.`;
  const chain = (o.chain || []).slice().reverse();
  const path = chain.length ? `<div class="lineage">${chain.map((c) => `<button class="chip" data-artist="${esc(c.name)}">${esc(c.name)}</button>`).join('<span class="arr">→</span>')}<span class="arr">→</span><span class="chip static on">${esc(a.name)}</span></div>` : '';
  const led = (a.ledTo || []).length ? `<p class="hint">Led you to ${a.ledTo.map((x) => `<button class="link" data-artist="${esc(x.name)}">${esc(x.name)}</button>`).join(', ')}.</p>` : '';
  return `<div class="viz origin"><h2>How you found them</h2><span class="sub">${line}</span>${path}${led}<button class="btn text" data-atlas="tree">${icon('atlas')}Your family tree</button></div>`;
}

// ---- you against everyone on Last.fm (all time) ----

let VS = null;
let vsBusy = false;
/** "23×", "1.4×", "0.6×", "<0.1×": your plays over the average listener's (rounded first, so 9.97 is 10×). Plain text. */
function fmtX(r) {
  const t = Math.round(r * 10) / 10;
  return t < 0.1 ? '<0.1×' : `${t >= 10 ? Math.round(r) : t}×`;
}
/** "1 in 230" for a share of everyone's plays. */
const oneIn = (share) => `1 in ${num(Math.max(1, Math.round(1 / Math.max(share, 1e-9))))}`;
const VS_TIERS = [['Household names', '1M+ listeners'], ['Well known', '250K–1M'], ['Known in their scene', '50K–250K'], ['Cult', '10K–50K'], ['Underground', 'under 10K']];
const tierColor = (k) => `color-mix(in srgb, var(--md-primary) ${[100, 72, 48, 30, 16][k]}%, var(--md-surface-container-highest))`;

/** You (the bar) against the average listener (the tick), on one scale. */
function vsBar(mine, avg) {
  const top = Math.max(mine, avg, 1);
  return `<div class="vsbar" aria-hidden="true"><i style="width:${((mine / top) * 100).toFixed(1)}%"></i><b style="left:${((avg / top) * 100).toFixed(1)}%"></b></div>`;
}

/** The block on artist, song and album pages: everyone's counts and you against the average listener. */
function vsBlock(g, what, extra = '') {
  if (!g) return '';
  return `<div class="viz vs"><h2>You vs everyone</h2><span class="sub">On Last.fm, all time</span>
    <div class="tiles">
      ${tile('Listeners', compact(g.listeners), `${compact(g.plays)} plays in all`, '', 'people')}
      ${tile('You', fmtX(g.ratio), `average: ${num(Math.round(g.avg))}`, '', 'heart')}
    </div>
    ${vsBar(g.mine, g.avg)}
    <p class="hint">You've played ${what} ${plural(g.mine, 'time')}; the average listener ${num(Math.round(g.avg))}. ${oneIn(g.share)} of all its plays on Last.fm are yours.${extra}</p></div>`;
}

/** Everyone's most played songs by an artist, and how often you played each. */
function globalTopHtml(a) {
  const top = a.globalTop || [];
  if (!top.length) return '';
  const heard = top.filter((x) => x.mine > 0).length;
  return `<div class="viz"><h2>Their most played songs</h2><span class="sub">On Last.fm, everyone's top ${top.length}; you've played ${heard} of them</span>
    <div class="rank">${top.map((x) => `<button class="r${x.mine ? '' : ' unheard'}" ${trackAttrs(a.name, x.title)}><span class="n">${x.rank}</span><span class="t"><b>${esc(x.title)}</b><small>${compact(x.plays)} plays worldwide</small></span><span class="v${x.mine ? ' cnt' : ''}">${x.mine ? `${num(x.mine)}×` : 'not yet'}</span></button>`).join('')}</div></div>`;
}

function openVs() {
  openPage('vsPage');
  renderVs();
  send({ t: 'vs' });
}
window.onVs = (v) => {
  VS = v;
  if (isOpen('vsPage')) renderVs();
  renderVsCard();
};
let vsRetry = 0;
/**
 * While the counts are being looked up, the page shows how far; when that ends it asks again. A lookup
 * that got nowhere (Last.fm down) ends at once, and asking right away would start the next: a minute later.
 */
function vsStateTick() {
  const hs = ST.history || {};
  const busy = !!hs.globalsBusy;
  if (vsBusy && !busy && (isOpen('vsPage') || VS)) {
    clearTimeout(vsRetry);
    if (hs.globalsDone > 0) send({ t: 'vs' });
    else vsRetry = setTimeout(() => { if (isOpen('vsPage')) send({ t: 'vs' }); }, 60e3);
  }
  vsBusy = busy;
  if (isOpen('vsPage') && busy) renderVs();
}

/** Little pictures for the two tiles on top of Explore, in the theme's colors. */
const ATLAS_ART = `<svg viewBox="0 0 120 52" aria-hidden="true"><g style="stroke:var(--md-outline);stroke-width:1;fill:none;opacity:.7"><path d="M14 36 L38 18 L62 30 L88 12 L108 26"/><path d="M38 18 L50 42 L62 30"/><path d="M88 12 L96 40"/></g>
  <g><circle cx="14" cy="36" r="4" style="fill:var(--md-tertiary)"/><circle cx="38" cy="18" r="6" style="fill:var(--md-primary)"/><circle cx="50" cy="42" r="3" style="fill:var(--md-tertiary)"/><circle cx="62" cy="30" r="5" style="fill:var(--md-primary)"/><circle cx="88" cy="12" r="4" style="fill:var(--md-secondary)"/><circle cx="96" cy="40" r="3" style="fill:var(--md-secondary)"/><circle cx="108" cy="26" r="5" style="fill:var(--md-tertiary)"/></g></svg>`;
const VS_ART = `<svg viewBox="0 0 120 52" aria-hidden="true"><g style="fill:var(--md-surface-container-highest)"><rect x="4" y="8" width="112" height="7" rx="3.5"/><rect x="4" y="23" width="112" height="7" rx="3.5"/><rect x="4" y="38" width="112" height="7" rx="3.5"/></g>
  <g style="fill:var(--md-primary)"><rect x="4" y="8" width="104" height="7" rx="3.5"/><rect x="4" y="23" width="78" height="7" rx="3.5" style="fill:var(--md-tertiary)"/><rect x="4" y="38" width="56" height="7" rx="3.5" style="fill:var(--md-secondary)"/></g>
  <g style="fill:var(--md-on-surface)"><rect x="22" y="5" width="2" height="13" rx="1"/><rect x="30" y="20" width="2" height="13" rx="1"/><rect x="40" y="35" width="2" height="13" rx="1"/></g></svg>`;
function renderVsCard() {
  const line = $('#vsTileLine');
  if (line && VS && VS.median) line.textContent = `Your middle artist: ${compact(VS.median)} listeners`;
}

function renderVs() {
  const body = $('#vsBody');
  if (!isOpen('vsPage')) return;
  const hs = ST.history || {};
  const v = VS;
  const p = hs.globalsTotal ? Math.round((hs.globalsDone / hs.globalsTotal) * 100) : 0;
  const busy = hs.globalsBusy ? `<div class="card"><div class="card-title">${icon('download')}Asking Last.fm: ${num(hs.globalsDone)} of ${num(hs.globalsTotal)}</div>
    <p>Everyone's play counts for your top artists and songs, a few a second. The first time takes a minute or two; after that only new ones, once a month.</p><div class="lin inline"><i style="--p:${p}%"></i></div></div>` : '';
  if (!v) { body.innerHTML = busy + '<div class="lin inline indet"><i></i></div>'; return; }
  if (v.empty) { body.innerHTML = `<div class="empty"><p>Import your Last.fm history on the Stats tab first.</p></div>`; return; }
  if (!v.lastfm) { body.innerHTML = `<div class="empty"><p>Connect Last.fm in Settings first: the comparisons use your Last.fm API key.</p></div>`; return; }
  if (!v.knownArtists) { body.innerHTML = busy || `<div class="empty"><p>Looking up your artists on Last.fm…</p></div>`; return; }
  const cover = v.knownPlays / Math.max(1, v.plays);
  let h = `<div class="vs-hero page-hero"><i class="glow"></i><span class="k">You vs everyone</span>
    <h1>Half of your plays go to artists with fewer than <em>${compact(v.median)}</em> listeners</h1>
    <div class="meta">Against everyone on Last.fm, all time. Counts are known for ${pct(cover)} of your plays.</div></div>${busy}`;
  // How mainstream: tiers from household names to the underground
  const tiers = v.tiers || [];
  const tot = Math.max(1, tiers.reduce((s, t) => s + t.plays, 0));
  h += `<div class="viz"><h2>How mainstream your artists are</h2><span class="sub">Your plays by how many people listen to the artist</span>
    <div class="tierbar">${tiers.map((t, k) => (t.plays ? `<i style="flex:${t.plays};background:${tierColor(k)}" title="${esc(VS_TIERS[k][0])}"></i>` : '')).join('')}</div>
    <div class="tierlist">${tiers.map((t, k) => `<div class="tr"><span class="sw" style="background:${tierColor(k)}"></span><span class="nm"><b>${VS_TIERS[k][0]}</b><small>${VS_TIERS[k][1]}, ${plural(t.artists, 'artist')}${t.top ? `, most: <button class="link" data-artist="${esc(t.top)}">${esc(t.top)}</button>` : ''}</small></span><span class="pc">${pct(t.plays / tot)}</span></div>`).join('')}</div></div>`;
  // Bigger fan than most
  const fans = v.fans || [];
  if (fans.length) {
    h += `<div class="viz"><h2>A bigger fan than most</h2><span class="sub">Your plays against the average listener's (the tick)</span>
      <div class="rank vs-rank">${fans.map((a) => `<button class="r with-cover" data-artist="${esc(a.name)}">${coverHtml(a.photo, a.name, a.name, 'tiny round')}<span class="t"><b>${esc(a.name)}</b><small>you ${num(a.mine)}, average ${num(Math.round(a.avg))}</small></span><span class="v x">${esc(fmtX(a.ratio))}</span>${vsBar(a.mine, a.avg)}</button>`).join('')}</div></div>`;
  }
  // Your share of everyone's plays
  const shares = v.shares || [];
  if (shares.length) {
    h += `<div class="viz"><h2>Your share</h2><span class="sub">Of all their plays on Last.fm, this many are yours</span>
      <div class="xcards">${shares.map((a) => `<button class="xcard share" data-artist="${esc(a.name)}">${coverHtml(a.photo, a.name, a.name, 'round')}<b class="big">${oneIn(a.share)}</b><b>${esc(a.name)}</b><small class="meta">${compact(a.plays)} plays in all</small></button>`).join('')}</div></div>`;
  }
  // Favorites few people know
  const gems = v.gems || [];
  if (gems.length) {
    h += `<div class="viz"><h2>Favorites few people know</h2><span class="sub">Artists you play a lot with the fewest listeners</span>
      <div class="xcards">${gems.map((a) => `<button class="xcard" data-artist="${esc(a.name)}">${coverHtml(a.photo, a.name, a.name, 'round')}<b>${esc(a.name)}</b><small>${compact(a.listeners)} listeners</small><small class="meta">you: ${compact(a.mine)}</small></button>`).join('')}</div></div>`;
  }
  // Songs
  const songs = v.songs || [];
  if (songs.length) {
    h += `<div class="viz"><h2>Songs you play more than anyone</h2><span class="sub">Against the average listener of each song</span>
      <div class="rank vs-rank">${songs.map((t, i) => `<button class="r" ${trackAttrs(t.artist, t.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}, you ${num(t.mine)}, average ${num(Math.round(t.avg))}</small></span><span class="v x">${esc(fmtX(t.ratio))}</span>${vsBar(t.mine, t.avg)}</button>`).join('')}</div></div>`;
  }
  // Your favorite song by each top artist against everyone's
  const taste = v.taste || [];
  if (taste.length) {
    h += `<div class="viz"><h2>Your favorite vs everyone's</h2><span class="sub">Your most played song by each of your top artists, and theirs on Last.fm</span>
      <div class="taste">${taste.map((x) => {
        const same = x.mine.rank === 1;
        const where = same ? 'their most played too' : x.mine.rank ? `their #${x.mine.rank} on Last.fm` : 'not in their top 50 on Last.fm';
        return `<div class="tc"><button class="th" data-artist="${esc(x.artist)}">${coverHtml(x.photo, x.artist, x.artist, 'tiny round')}<span class="tn"><b>${esc(x.artist)}</b><small>you've heard ${x.heard} of their top ${x.of}</small></span></button>
          <button class="tl" ${trackAttrs(x.artist, x.mine.title)}><span class="lb">Yours</span><b>${esc(x.mine.title)}</b><small>${plural(x.mine.plays, 'play')}, ${where}</small></button>
          ${same ? '' : `<button class="tl" ${trackAttrs(x.artist, x.theirs.title)}><span class="lb">Everyone's</span><b>${esc(x.theirs.title)}</b><small>${compact(x.theirs.plays)} plays, ${x.theirs.mine ? `you: ${plural(x.theirs.mine, 'play')}` : 'you haven\'t played it'}</small></button>`}</div>`;
      }).join('')}</div></div>`;
  }
  h += `<p class="hint center">Counts from Last.fm, all time, for everyone (you included), looked up again once a month. The average listener is all plays divided by all listeners; a few heavy listeners pull that up, so most people played less.</p>`;
  morph(body, h);
  tint(body, '', 'You vs everyone');
}

// ---- explore: the long tail ----

let EXPLORE = null;
function requestExplore() {
  if (!(ST.history || {}).count) { EXPLORE = null; renderExplore(); return; }
  if (!EXPLORE) renderExplore();
  send({ t: 'explore' });
  if ((ST.discover || {}).on !== false) send({ t: 'discover' });
}
window.onExplore = (x) => {
  EXPLORE = x;
  renderExplore();
  // Your taste on Stats is drawn from the same answer.
  if (tab === 'stats' && STATS && !STATS.empty) renderStats();
};

const plButton = (kind, label = 'Make a playlist') => `<button class="btn tonal small" data-pl="${kind}">${icon('playlist')}${label}</button>`;
function xSection(title, sub, kind, inner) {
  return `<div class="viz explore"><div class="xhead"><h2>${title}</h2>${kind ? plButton(kind, 'Playlist') : ''}</div><span class="sub">${sub}</span>${inner}</div>`;
}
const monthYear = (ms) => new Date(ms).toLocaleDateString([], { month: 'short', year: 'numeric' });

function renderExplore() {
  const body = $('#exploreBody');
  if (!(ST.history || {}).count) {
    body.innerHTML = `<div class="empty">${icon('explore', 'i big')}<p>Import your Last.fm history on the Stats tab, then come back here to dig through it.</p><button class="btn filled" id="exploreGoStats">Go to Stats</button></div>`;
    return;
  }
  const x = EXPLORE;
  if (!x) { body.innerHTML = '<div class="lin inline indet"><i></i></div>'; return; }
  if (x.empty) { body.innerHTML = `<div class="empty"><p>Nothing to explore yet.</p></div>`; return; }
  // The page in parts: one after the other, or as rows (Settings → Look). Your taste is at the end of Stats.
  const P = {};
  P.dig = `<button class="btn tonal xp-dig" data-pl="dig">${icon('shuffle')}Crate dig: a random playlist</button>`;
  P.foryou = forYouHtml();
  const la = x.lostArtists || [];
  if (la.length) {
    P.lost = xSection('Lost favorites', 'Artists you played a lot that have gone quiet for half a year or more', 'lost',
      `<div class="xcards">${la.map((a) => `<button class="xcard" data-artist="${esc(a.name)}">${coverHtml(a.photo || a.cover, a.name, a.name)}<b>${esc(a.name)}</b>
        <small>${plural(a.plays, 'play')}, last ${esc(monthYear(a.last))}</small><small class="meta">Big in ${esc(monthYear(a.peak.start))}</small></button>`).join('')}</div>`);
  }
  const lb = x.lostAlbums || [];
  if (lb.length) {
    P.lostAlbums = xSection('Albums to rediscover', 'Albums you played through, then left on the shelf', 'lostAlbums',
      `<div class="xcards">${lb.map((a) => `<button class="xcard" ${albumAttrs(a.artist, a.title)} data-cover="${esc(a.cover)}">${coverHtml(a.cover, a.title, a.artist)}<b>${esc(a.title)}</b>
        <small>${esc(a.artist)}</small><small class="meta">${plural(a.plays, 'play')}, last ${esc(monthYear(a.last))}</small></button>`).join('')}</div>`);
  }
  const rg = x.regulars || [];
  if (rg.length) {
    P.regulars = xSection('Quiet regulars', 'Never on top, always around: artists you come back to month after month', 'regulars',
      `<div class="rank">${rg.map((a) => `<button class="r with-cover" data-artist="${esc(a.name)}">${coverHtml(a.photo || a.cover, a.name, a.name, 'tiny round')}<span class="t"><b>${esc(a.name)}</b><small>${a.track ? `${esc(a.track)}, ` : ''}since ${esc(monthYear(a.first))}</small></span><span class="v">${a.months} months</span></button>`).join('')}</div>`);
  }
  const od = x.oneDay || [];
  if (od.length) {
    P.oneDay = xSection('One-day wonders', 'Played all day once, never again. Worth another try?', 'oneDay',
      `<div class="rank">${od.map((a) => `<button class="r with-cover" data-artist="${esc(a.name)}">${coverHtml(a.photo || a.cover, a.name, a.name, 'tiny round')}<span class="t"><b>${esc(a.name)}</b><small>${esc(dateLabel(a.first))}${a.track ? `, ${esc(a.track)}` : ''}</small></span><span class="v">${plural(a.plays, 'play')}</span></button>`).join('')}</div>`);
  }
  const dc = x.deepCuts || [];
  if (dc.length) {
    P.deepCuts = xSection('Deep cuts', 'Songs by your favorite artists you\'ve played only once or twice', 'deepCuts',
      `<div class="rank">${dc.map((t) => `<button class="r" ${trackAttrs(t.artist, t.title, { album: t.album })}><span class="n">${icon('music', 'i small')}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}${t.album ? `, ${esc(t.album)}` : ''}</small></span><span class="v">${t.plays}×</span></button>`).join('')}</div>`);
  }
  const otd = x.onThisDay || [];
  if (otd.length) {
    P.onThisDay = xSection('On this day', `What you played on ${esc(new Date().toLocaleDateString([], { day: 'numeric', month: 'long' }))} in earlier years`, 'onThisDay',
      otd.map((y) => `<div class="otd"><div class="yr"><b>${y.year}</b><small>${plural(y.plays, 'play')}, ${y.artists.map(esc).join(', ')}</small></div>
        <div class="rank">${y.tracks.map((t) => `<button class="r" ${trackAttrs(t.artist, t.title, { album: t.album })}><span class="n">${icon('music', 'i small')}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}</small></span><span class="v">${t.plays > 1 ? `${t.plays}×` : ''}</span></button>`).join('')}</div></div>`).join(''));
  }
  morph(body, mainCompact() ? exploreRows(x, P) : EXPLORE_LONG.map((k) => P[k] || '').join(''));
}

// ---- for you: music you haven't played, from what your artists point to ----

let DISCOVER = null;
let discoverAll = false;
window.onDiscover = (d) => {
  DISCOVER = d;
  if (tab === 'explore') renderExplore();
  renderForYou();
};
const REACH = [['0', 'Close'], ['1', 'In between'], ['2', 'Further out']];
const fansLabel = (n) => (n >= 1000 ? `${compact(n)} fans` : '');
/** "Because you play A, B and C" */
const becauseText = (x) => {
  const n = (x.because || []).slice(0, 3).map((b) => b.name);
  if (x.via) return `Like ${x.via}, another of your suggestions`;
  return n.length ? `Because you play ${n.length > 1 ? `${n.slice(0, -1).join(', ')} and ${n[n.length - 1]}` : n[0]}` : '';
};
const discoverOn = () => (ST.discover || {}).on !== false && !(DISCOVER && DISCOVER.on === false);
/** Still looking for the first suggestions, or it failed: what to say instead of them ('' once there are some). */
function discoverWait(d) {
  if (d && (d.built || (d.artists || []).length)) return '';
  if (d && d.error) return `<p class="hint">${esc(d.error)}</p><button class="btn tonal small" data-dagain>Try again</button>`;
  const p = d && d.total ? Math.round((d.done / d.total) * 100) : 0;
  return `<span class="sub">Looking up who Last.fm's and Deezer's listeners find alike to the artists you play${d && d.total ? `: ${d.done} of ${d.total}` : ''}. This takes a minute the first time.</span>
    <div class="lin inline${p ? '' : ' indet'}"><i style="${p ? `width:${p}%` : ''}"></i></div>`;
}
const mixCard = (d) => `<button class="fy-mix" data-pl="discover"><span class="stack">${(d.artists || []).slice(0, 3).map((a, i) => `<i style="left:${i * 9}px;top:${i * 9}px">${coverHtml(a.photo, a.name, a.name, 'tiny')}</i>`).join('')}</span>
    <span class="txt"><b>Discovery mix</b><small>${d.mix * 2} songs you haven't played, new every Monday</small></span></button>`;
const reachChips = (d) => `<div class="chips fy-reach">${REACH.map(([v, l]) => `<button class="chip${String(d.reach) === v ? ' on' : ''}" data-reach="${v}">${l}</button>`).join('')}</div>`;
/** Suggestions as rows with their reason and Not for me, for the full list and the long page. */
const suggestionRows = (arts) => arts.map((a) => `<div class="r with-cover fy-row" data-why="${esc(a.name)}">${coverHtml(a.photo, a.name, a.name, 'tiny round')}<span class="t"><b>${esc(a.name)}</b>
    <small class="fy-why">${esc(becauseText(a))}</small>${a.genre || a.fans ? `<small>${esc([a.genre, fansLabel(a.fans)].filter(Boolean).join(', '))}</small>` : ''}</span>
    <button class="icon-btn small" data-nope="${esc(a.name)}" aria-label="Not for me: ${esc(a.name)}">${icon('close')}</button></div>`).join('');
const releaseCards = (d) => (d.releases || []).map((r) => `<button class="xcard" ${albumAttrs(r.artist, r.title)} data-cover="${esc(r.cover)}">${coverHtml(r.cover, r.title, r.artist)}<b>${esc(r.title)}</b>
    <small>${esc(r.artist)}</small><small class="meta">${esc(releaseWhen(r))}${r.plays ? `, ${plural(r.plays, 'play')} so far` : ''}</small></button>`).join('');
function forYouHtml() {
  const d = DISCOVER;
  if (!discoverOn()) return '';
  const wait = discoverWait(d);
  if (wait) return `<div class="viz fy-wait"><h2>Finding music for you</h2>${wait}</div>`;
  const arts = d.artists || [];
  const shown = discoverAll ? arts : arts.slice(0, 8);
  const more = arts.length > shown.length ? `<button class="btn text" data-dmore>Show all ${arts.length}</button>` : '';
  const rel = releaseCards(d);
  return `${arts.length >= 4 ? mixCard(d) : ''}<div class="viz"><div class="xhead"><h2>Artists you might like</h2></div><span class="sub">Artists you haven't played that your listening points to. Further out brings less known ones. Tap one to see why.</span>
      ${reachChips(d)}${arts.length ? `<div class="rank fy-list">${suggestionRows(shown)}</div>${more}` : '<p class="hint">Nothing new to suggest from here. Try another setting above.</p>'}</div>
    ${rel ? `<div class="viz explore"><div class="xhead"><h2>New from your artists</h2></div><span class="sub">Albums, EPs and singles out in the last eight weeks, or coming soon</span><div class="xcards">${rel}</div></div>` : ''}
    ${d.busy ? `<p class="hint center">Looking for more: ${d.done} of ${d.total}</p>` : ''}`;
}

/** All the suggestions, with how far they go, their reasons and Not for me. */
function openForYou() {
  LIKE = null;
  openPage('forYouPage');
  renderForYou();
}
/**
 * More like them: artists you haven't played that are like the period's new artists (the most played since first), found
 * the way the suggestions are, on the same page.
 */
let LIKE = null;
let likeReq = 0;
function openLikeThem() {
  const na = (STATS && STATS.newArtists) || {};
  const seeds = (na.found || []).slice(0, 8).map((f) => ({ name: f.name, plays: f.plays }));
  if (!seeds.length) return;
  if (isOpen('sheet')) closeSheet();
  LIKE = { seeds, loading: true, req: ++likeReq, period: STATS.range };
  openPage('forYouPage');
  renderForYou();
  send({ t: 'discoverLike', seeds, req: likeReq });
}
window.onDiscoverLike = (d) => {
  if (!LIKE || d.req !== LIKE.req) return;
  LIKE = { ...LIKE, ...d, loading: false };
  renderForYou();
};
function likeHtml() {
  const names = LIKE.seeds.map((x) => x.name);
  const who = names.length > 1 ? `${names.slice(0, -1).map(esc).join(', ')} and ${esc(names[names.length - 1])}` : esc(names[0]);
  const list = LIKE.artists || [];
  const body = LIKE.loading ? `<p class="hint">Looking up who Last.fm's and Deezer's listeners find alike to them.</p><div class="lin inline indet"><i></i></div>`
    : LIKE.error ? `<p class="hint">${esc(LIKE.error)}</p>`
      : list.length ? `<div class="rank fy-list">${suggestionRows(list)}</div>` : '<p class="hint">Nothing new to suggest from them yet.</p>';
  return `<h1 class="page-h">More like your new artists</h1><p class="hint">Artists you haven't played that are like the ones you found ${esc(inPeriod(LIKE.period))}: ${who}. Tap one to see why; ✕ means not for you.</p>${body}`;
}
function renderForYou() {
  if (!isOpen('forYouPage')) return;
  if (LIKE) { $('#forYouBody').innerHTML = likeHtml(); return; }
  const d = DISCOVER || {};
  const wait = discoverWait(d);
  const arts = d.artists || [];
  $('#forYouBody').innerHTML = `<h1 class="page-h">Artists you might like</h1><p class="hint">Artists you haven't played that your listening points to. Tap one to see why; ✕ means not for you.</p>
    ${wait || `${reachChips(d)}<p class="hint">Further out brings less known artists, and ones a step further from yours.</p>
      ${arts.length ? `<div class="rank fy-list">${suggestionRows(arts)}</div>` : '<p class="hint">Nothing new to suggest from here. Try another setting above.</p>'}`}`;
}

// ---- Explore as rows (Settings → Look): every list one row you scroll sideways ----

/**
 * One row: its heading (with a playlist or "all" button), what it is, and its cards. Empty without cards. [all]: the
 * list it opens in full (its heading and an "All" card at the end), with [n] in it when known.
 */
const xrow = (title, more, cards, sub = '', all = '', n = 0) => {
  if (!cards) return '';
  const head = all ? `<button class="xrow-title" data-xlist="${all}"><h2>${title}</h2>${icon('chevron-right', 'i small')}</button>` : `<h2>${title}</h2>`;
  const end = all ? `<button class="xcard xall" data-xlist="${all}"><span class="xall-c">${icon('chevron-right')}</span><b>${n ? `All ${num(n)}` : 'All'}</b></button>` : '';
  return `<section class="xrow"><div class="xrow-head">${head}${more || ''}</div>${sub ? `<span class="sub">${sub}</span>` : ''}<div class="xcards">${cards}${end}</div></section>`;
};
const xrowPl = (kind) => `<button class="xrow-more" data-pl="${kind}">${icon('playlist', 'i small')}Playlist</button>`;
const artistCard = (a, line) => `<button class="xcard round" data-artist="${esc(a.name)}">${coverHtml(a.photo || a.cover, a.name, a.name, 'round')}<b>${esc(a.name)}</b><small class="meta">${line}</small></button>`;
const songCard = (t, line) => `<button class="xcard" ${trackAttrs(t.artist, t.title, { album: t.album, cover: t.cover })}>${coverHtml(t.cover, t.album || t.title, t.artist)}<b>${esc(t.title)}</b><small>${esc(t.artist)}</small><small class="meta">${line}</small></button>`;
const timesPlayed = (n) => (n === 1 ? 'played once' : n === 2 ? 'played twice' : `played ${n} times`);
/** Explore's lists: their name, what they are, their cards in a row and their rows in full. */
const albumCard = (a) => `<button class="xcard" ${albumAttrs(a.artist, a.title)} data-cover="${esc(a.cover)}">${coverHtml(a.cover, a.title, a.artist)}<b>${esc(a.title)}</b>
    <small>${esc(a.artist)}</small><small class="meta">last ${esc(monthYear(a.last))}</small></button>`;
const artistRow = (a, line) => `<button class="r with-cover" data-artist="${esc(a.name)}">${coverHtml(a.photo || a.cover, a.name, a.name, 'tiny round')}<span class="t"><b>${esc(a.name)}</b><small>${line}</small></span><span class="v">${num(a.plays)}</span></button>`;
const songRow = (t, line) => `<button class="r with-cover" ${trackAttrs(t.artist, t.title, { album: t.album, cover: t.cover })}>${coverHtml(t.cover, t.album || t.title, t.artist, 'tiny')}<span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}${line ? `, ${line}` : ''}</small></span></button>`;
const XLIST = {
  lost: { title: 'Lost favorites', sub: 'Played a lot, quiet for half a year or more', cards: (x) => (x.lostArtists || []).map((a) => artistCard(a, `last ${esc(monthYear(a.last))}`)).join(''),
    rows: (l) => l.items.map((a) => artistRow(a, `last ${esc(monthYear(a.last))}, big in ${esc(monthYear(a.peak.start))}`)).join('') },
  lostAlbums: { title: 'Albums to rediscover', sub: 'Played through, then left on the shelf', cards: (x) => (x.lostAlbums || []).map(albumCard).join(''),
    rows: (l) => l.items.map((a) => `<button class="r with-cover" ${albumAttrs(a.artist, a.title)} data-cover="${esc(a.cover)}">${coverHtml(a.cover, a.title, a.artist, 'tiny')}<span class="t"><b>${esc(a.title)}</b><small>${esc(a.artist)}, last ${esc(monthYear(a.last))}</small></span><span class="v">${num(a.plays)}</span></button>`).join('') },
  regulars: { title: 'Quiet regulars', sub: 'Never on top, always around', cards: (x) => (x.regulars || []).map((a) => artistCard(a, `${a.months} months`)).join(''),
    rows: (l) => l.items.map((a) => artistRow(a, `${a.months} months${a.track ? `, ${esc(a.track)}` : ''}`)).join('') },
  oneDay: { title: 'One-day wonders', sub: 'Played all day once, never again', cards: (x) => (x.oneDay || []).map((a) => artistCard(a, esc(dateLabel(a.first)))).join(''),
    rows: (l) => l.items.map((a) => artistRow(a, `${esc(dateLabel(a.first))}${a.track ? `, ${esc(a.track)}` : ''}`)).join('') },
  deepCuts: { title: 'Deep cuts', sub: 'Songs by your favorite artists you\'ve hardly played', cards: (x) => (x.deepCuts || []).map((t) => songCard(t, timesPlayed(t.plays))).join(''),
    rows: (l) => l.items.map((t) => songRow(t, timesPlayed(t.plays))).join('') },
  onThisDay: { title: 'On this day', sub: () => `What you played on ${esc(new Date().toLocaleDateString([], { day: 'numeric', month: 'long' }))} in earlier years`,
    cards: (x) => (x.onThisDay || []).flatMap((y) => y.tracks.map((t) => songCard(t, `${y.year}`))).join(''),
    rows: (l) => (l.years || []).map((y) => `<button class="xlist-year" data-day="${periodId('day', +new Date(y.year, new Date().getMonth(), new Date().getDate()))}"><h3>${y.year}<small>${plural(y.plays, 'play')}</small></h3>${icon('chevron-right', 'i small')}</button><div class="rank">${y.tracks.map((t) => songRow(t, t.plays > 1 ? `${t.plays}×` : '')).join('')}</div>`).join('') },
};
/** An Explore list in full, as far as it goes (up to 100). */
let XL = null;
let xlReq = 0;
function openXList(kind) {
  XL = { kind, loading: true, req: ++xlReq };
  openPage('xlistPage');
  renderXList();
  send({ t: 'exploreList', kind, req: xlReq });
}
window.onExploreList = (l) => {
  if (!XL || l.kind !== XL.kind || l.req !== XL.req) return;
  XL = { ...l, req: XL.req };
  renderXList();
};
function renderXList() {
  if (!XL || !isOpen('xlistPage')) return;
  const d = XLIST[XL.kind];
  const sub = typeof d.sub === 'function' ? d.sub() : d.sub;
  const rows = XL.loading ? '<div class="lin inline indet"><i></i></div>' : XL.kind === 'onThisDay' ? d.rows(XL) : `<div class="rank">${d.rows(XL)}</div>`;
  morph($('#xlistBody'), `<h1 class="page-h">${d.title}</h1><p class="hint">${sub}${XL.items ? `. ${num(XL.items.length)} in all${XL.items.length >= 100 ? ', the first 100 shown' : ''}.` : ''}</p>
    <div class="btn-row">${plButton(XL.kind)}</div>${rows}`);
}
function exploreRows(x, P) {
  const d = DISCOVER, on = discoverOn();
  const dig = `<button class="fy-mix dig" data-pl="dig"><span class="stack">${(x.lostAlbums || []).slice(0, 3).map((a, i) => `<i style="left:${i * 9}px;top:${i * 9}px">${coverHtml(a.cover, a.title, a.artist, 'tiny')}</i>`).join('')}</span>
    <span class="txt"><b>Crate dig</b><small>Songs you played a few times and forgot, shuffled</small></span></button>`;
  const mix = on && d && (d.artists || []).length >= 4 ? mixCard(d) : '';
  let h = `<div class="mixrow${mix ? '' : ' solo'}">${mix}${dig}</div>`;
  if (on) {
    const arts = (d && d.artists) || [];
    const wait = discoverWait(d);
    h += wait ? `<section class="xrow"><div class="xrow-head"><h2>Artists you might like</h2></div>${wait}</section>`
      : xrow('Artists you might like', arts.length ? `<button class="xrow-more" data-dmore>All ${arts.length}</button>` : '',
        arts.slice(0, 12).map((a) => `<button class="xcard round" data-why="${esc(a.name)}">${coverHtml(a.photo, a.name, a.name, 'round')}<b>${esc(a.name)}</b><small class="meta">${esc(a.via ? `Like ${a.via}` : a.because && a.because[0] ? `Like ${a.because[0].name}` : a.genre || '')}</small></button>`).join(''),
        'Artists you haven\'t played that your listening points to. Tap one to see why.');
    if (d) h += xrow('New from your artists', '', releaseCards(d));
  }
  const n = x.counts || {};
  for (const k of ['lost', 'lostAlbums', 'regulars', 'oneDay', 'deepCuts']) h += xrow(XLIST[k].title, xrowPl(k), XLIST[k].cards(x), XLIST[k].sub, k, n[k]);
  h += xrow(XLIST.onThisDay.title, xrowPl('onThisDay'), XLIST.onThisDay.cards(x), XLIST.onThisDay.sub(), 'onThisDay');
  return h;
}
/** "3 weeks ago", "this week", "out 14 Nov" */
function releaseWhen(r) {
  const d = new Date(`${r.date}T12:00:00`);
  const days = Math.round((Date.now() - d.getTime()) / 864e5);
  const type = { album: 'album', ep: 'EP', single: 'single' }[r.type] || 'release';
  if (days < 0) return `${type}, out ${d.toLocaleDateString([], { day: 'numeric', month: 'short' })}`;
  if (days < 7) return `${type}, this week`;
  return `${type}, ${days < 14 ? 'last week' : `${Math.floor(days / 7)} weeks ago`}`;
}
/** Not for me: gone from the suggestions, and the artists that led there count a little less. Undo brings it back. */
function discoverNope(name) {
  if (LIKE && LIKE.artists) LIKE.artists = LIKE.artists.filter((a) => a.name !== name);
  if (DISCOVER && DISCOVER.artists) DISCOVER.artists = DISCOVER.artists.filter((a) => a.name !== name);
  renderExplore();
  renderForYou();
  send({ t: 'discoverNo', name });
  toast(`${name} won't be suggested again`, { label: 'Undo', run: () => send({ t: 'discoverUndo', name }) });
}

/** Why an artist is suggested: which of your artists point to them and how strongly, and where to start. */
let WHY = null;
let whyReq = 0;
function openWhy(name) {
  const x = ((DISCOVER || {}).artists || []).find((a) => a.name === name) || { name };
  WHY = { ...x, loading: true, req: ++whyReq };
  openSheet(whyHtml());
  wireWhy();
  send({ t: 'discoverWhy', name, req: whyReq });
}
window.onDiscoverWhy = (j) => {
  if (!WHY || j.req !== WHY.req) return;
  WHY = { ...WHY, ...j, loading: false };
  if (sheetOpen()) { $('#sheetContent').innerHTML = whyHtml(); wireWhy(); }
};
const likeWord = (v) => (v >= 0.6 ? 'very alike' : v >= 0.3 ? 'alike' : 'a little alike');
function whyHtml() {
  const x = WHY;
  const bars = (x.because || []).map((b) => `<div class="whybar"><span>${esc(b.name)}</span><i><b style="width:${Math.max(6, Math.round(b.v * 100))}%"></b></i><small>${likeWord(b.like)}</small></div>`).join('');
  const songs = (x.songs || []).map((t, i) => `<div class="r"><span class="n">${i + 1}</span><span class="t"><b>${esc(t)}</b></span></div>`).join('');
  return `<div class="why-sheet"><div class="why-hero">${coverHtml(x.photo, x.name, x.name, 'tiny round')}<div><h2>${esc(x.name)}</h2><div class="meta">${esc([x.genre, fansLabel(x.fans)].filter(Boolean).join(', ')) || 'Not in your history yet'}</div></div></div>
    ${bars ? `<div class="row-label"><span>What points to them</span></div>${bars}
      <p class="hint">Each bar: how alike listeners on Last.fm and Deezer find the two, weighed by how much you play your artist lately.${(x.because || []).length > 1 ? ` ${plural(x.because.length, 'artist')} of yours point here, so they rank high.` : ''}</p>` : x.via ? `<p class="body">${esc(becauseText(x))}.</p>` : ''}
    <div class="row-label"><span>Start with</span></div>
    ${x.loading ? '<div class="lin inline indet"><i></i></div>' : songs ? `<div class="rank">${songs}</div>` : '<p class="hint">Last.fm has no songs of theirs listed.</p>'}
    <div class="btn-row">
      ${(x.songs || []).length ? `<button class="btn filled" data-pl="artistBest" data-artist="${esc(x.name)}">${icon('playlist')}Best-of playlist</button>` : ''}
      <button class="btn tonal" data-artist="${esc(x.name)}">Artist page</button>
      ${x.known ? '' : `<button class="btn text" data-nope="${esc(x.name)}">Not for me</button>`}
    </div></div>`;
}
function wireWhy() {
  $('#sheetContent').onclick = (e) => {
    const no = e.target.closest('[data-nope]');
    if (no) { closeSheet(); discoverNope(no.dataset.nope); return; }
    if (e.target.closest('[data-artist], [data-pl]')) closeSheet();
  };
}
/** The discovery mix (the week's, or another one) and an artist's best-known songs, as playlists. */
function openDiscoverMix(seed = 0) {
  openPage('playlistPage');
  PLAYLIST = { loading: true, kind: 'discover', name: 'Discovery mix', size: 30, seed, reach: (DISCOVER || {}).reach };
  TIDALJOB = null;
  PLNAME = null;
  renderPlaylist();
  send({ t: 'playlist', kind: 'discover', size: 30, seed });
}
function openArtistBest(artist) {
  openPage('playlistPage');
  PLAYLIST = { loading: true, kind: 'artistBest', artist };
  TIDALJOB = null;
  PLNAME = null;
  renderPlaylist();
  send({ t: 'playlist', kind: 'artistBest', artist });
}
function discoverPlControls(p) {
  return `<div class="pl-ctl" id="plCtl"><div class="chips">${REACH.map(([v, l]) => `<button class="chip${String(p.reach) === v ? ' on' : ''}" data-plreach="${v}">${l}</button>`).join('')}
    <button class="chip" data-plagain>${icon('shuffle', 'i small')}Make another</button></div></div>`;
}

// ---- playlists ----

let PLAYLIST = null;
let TIDALJOB = null;
let PLNAME = null; // the name and description you gave the playlist: { name, note }, until you reset them
/** The playlist's name and description: yours, else the ones it came with. */
const plTitle = (p) => (PLNAME ? PLNAME.name : p.name);
const plNote = (p) => (PLNAME ? PLNAME.note : `${p.note}. Made with Heavy Rotation.`);
function openPlaylist(kind, range, el) {
  if (kind === 'discover') { openDiscoverMix(); return; }
  if (kind === 'artistBest') { openArtistBest(el && el.dataset.artist); return; }
  openPage('playlistPage');
  PLAYLIST = { loading: true, kind };
  TIDALJOB = null;
  PLNAME = null;
  renderPlaylist();
  send({ t: 'playlist', kind, range: range || statsRange });
}
/** A genre's playlist: 30 songs, mixed, until you pick another size or mix. */
function openGenrePlaylist(key, label) {
  openPage('playlistPage');
  PLAYLIST = { loading: true, kind: 'genre', key, label, name: `${label} mix`, size: 30, mode: 'mix', seed: 0 };
  TIDALJOB = null;
  PLNAME = null;
  renderPlaylist();
  askGenrePlaylist();
}
function askGenrePlaylist() {
  const p = PLAYLIST;
  send({ t: 'playlist', kind: 'genre', key: p.key, size: p.size, mode: p.mode, seed: p.seed });
}
/** Size, mix and "make another" for a genre's playlist. */
function genrePlControls(p) {
  const modes = [['yours', 'Your favorites'], ['mix', 'Mix'], ['discover', 'Discover']];
  return `<div class="pl-ctl" id="plCtl"><div class="chips">${modes.map(([v, l]) => `<button class="chip${p.mode === v ? ' on' : ''}" data-plmode="${v}">${l}</button>`).join('')}</div>
    <div class="chips">${[20, 30, 50, 100].map((n) => `<button class="chip${p.size === n ? ' on' : ''}" data-plsize="${n}">${n} songs</button>`).join('')}
      <button class="chip" data-plagain>${icon('shuffle', 'i small')}Make another</button></div></div>`;
}
function wireGenrePlControls() {
  const box = $('#plCtl');
  if (!box) return;
  if (PLAYLIST && PLAYLIST.kind === 'discover') {
    box.onclick = (e) => {
      const r = e.target.closest('[data-plreach]'), again = e.target.closest('[data-plagain]');
      if (!r && !again) return;
      const p = PLAYLIST;
      if (r) send({ t: 'discoverReach', v: +r.dataset.plreach });
      PLAYLIST = { ...p, loading: true, reach: r ? +r.dataset.plreach : p.reach, seed: again ? Date.now() % 1e9 : p.seed };
      TIDALJOB = null;
      renderPlaylist();
      send({ t: 'playlist', kind: 'discover', size: p.size, seed: PLAYLIST.seed });
    };
    return;
  }
  box.onclick = (e) => {
    const m = e.target.closest('[data-plmode]'), s = e.target.closest('[data-plsize]'), again = e.target.closest('[data-plagain]');
    if (!m && !s && !again) return;
    const p = PLAYLIST;
    PLAYLIST = { ...p, loading: true, mode: m ? m.dataset.plmode : p.mode, size: s ? +s.dataset.plsize : p.size, seed: again ? Date.now() % 1e9 : p.seed };
    TIDALJOB = null;
    renderPlaylist();
    askGenrePlaylist();
  };
}
window.onPlaylist = (p) => {
  // A genre's playlist that is no longer the one asked for (another size or mix since): left out.
  if (p.kind === 'genre' && PLAYLIST && PLAYLIST.kind === 'genre' && (p.key !== PLAYLIST.key || p.size !== PLAYLIST.size || p.mode !== PLAYLIST.mode || p.seed !== PLAYLIST.seed)) return;
  if (p.kind === 'discover' && PLAYLIST && (PLAYLIST.kind !== 'discover' || p.seed !== PLAYLIST.seed)) return;
  if (p.kind === 'artistBest' && PLAYLIST && (PLAYLIST.kind !== 'artistBest' || p.artist !== PLAYLIST.artist)) return;
  if (p.kind === 'top' && p.range) { p.name = `${periodName(p.range)} on record`; p.note = `Your most played songs of ${periodName(p.range)}`; }
  PLAYLIST = p;
  renderPlaylist();
};
const playlistText = (p) => p.tracks.map((t) => `${t.artist} - ${t.title}`).join('\n');

/** A sheet to name the playlist and say what it is, for TIDAL and for sharing. */
function editPlaylistName() {
  const p = PLAYLIST;
  if (!p || p.loading) return;
  openSheet(`<div class="sheet-title">Name and description</div>
    <label class="field"><span>Name</span><input type="text" id="plnName" maxlength="200" value="${esc(plTitle(p))}"></label>
    <label class="field"><span>Description</span><textarea id="plnNote" rows="3" maxlength="500">${esc(plNote(p))}</textarea></label>
    <p class="hint">TIDAL gets both with the playlist; Share puts them above the songs.</p>
    <div class="sheet-actions">${PLNAME ? '<button class="btn text" id="plnReset">Reset</button>' : ''}<button class="btn text" id="plnNo">Cancel</button><button class="btn filled" id="plnYes">Save</button></div>`);
  $('#plnNo').onclick = closeSheet;
  if ($('#plnReset')) $('#plnReset').onclick = () => { PLNAME = null; closeSheet(); renderPlaylist(); };
  $('#plnYes').onclick = () => {
    PLNAME = { name: $('#plnName').value.trim() || p.name, note: $('#plnNote').value.trim() };
    closeSheet();
    renderPlaylist();
  };
}

/** Creating the playlist in TIDAL: the button, its progress, or what came of it. */
function tidalBlock() {
  const td = ST.tidal || {};
  const j = TIDALJOB;
  if (j && j.state === 'finding') {
    return `<div class="card tidal-job"><div class="card-title">${icon('playlist')}Finding the songs on TIDAL…</div>
      <div class="lin inline"><i style="width:${(j.done / Math.max(1, j.total)) * 100}%"></i></div>
      <p class="hint">${j.done} of ${j.total} looked up, ${j.found} found</p></div>`;
  }
  if (j && j.state === 'done') {
    const miss = j.missing || [];
    return `<div class="card tidal-job ok"><div class="card-title">${icon('done-all')}In your TIDAL now</div>
      <p class="body">"${esc(j.name)}": ${j.added} of ${plural(j.total, 'song')}.${miss.length ? ` TIDAL doesn't have ${miss.length === 1 ? 'one of them' : `${miss.length} of them`}:` : ''}</p>
      ${miss.length ? `<p class="hint missing">${miss.map((m) => `${esc(m.artist)} - ${esc(m.title)}`).join('<br>')}</p>` : ''}
      <div class="btn-row"><button class="btn filled" data-url="${esc(j.link)}">${icon('open')}Open in TIDAL</button></div></div>`;
  }
  let h = '';
  if (j && j.state === 'error') h = `<p class="error">${esc(j.msg)}</p>`;
  if (td.loggedIn) return `${h}<button class="btn filled wide tidal-go" id="plTidal">${icon('playlist')}Create in TIDAL</button>`;
  if (td.hasApp) return `${h}<button class="btn tonal wide tidal-go" id="plTidalLogin">${icon('open')}Sign in to TIDAL to create it there</button>`;
  return `<button class="btn tonal wide tidal-go" data-go="tdSection">${icon('playlist')}Connect TIDAL to create it there</button>`;
}
window.onTidalJob = (j) => {
  TIDALJOB = j;
  const el = $('#plTidalBlock');
  if (el && isOpen('playlistPage')) el.innerHTML = tidalBlock();
};

function renderPlaylist() {
  const p = PLAYLIST;
  if (!p || !isOpen('playlistPage')) return;
  const body = $('#playlistBody');
  if (p.loading && p.kind === 'genre') {
    body.innerHTML = `<div class="pl-head"><div class="pl-icon">${icon('playlist')}</div><div><h1>${esc(plTitle(p))}</h1><p>Mixing ${p.size} songs…</p></div></div>${genrePlControls(p)}<div class="lin inline indet"><i></i></div>`;
    wireGenrePlControls();
    return;
  }
  if (p.loading && p.kind === 'discover') {
    body.innerHTML = `<div class="pl-head"><div class="pl-icon">${icon('playlist')}</div><div><h1>Discovery mix</h1><p>Picking ${p.size} songs…</p></div></div>${discoverPlControls(p)}<div class="lin inline indet"><i></i></div>`;
    wireGenrePlControls();
    return;
  }
  if (p.loading) { body.innerHTML = '<div class="lin inline indet"><i></i></div>'; return; }
  if (p.empty || !p.tracks || !p.tracks.length) { body.innerHTML = `<div class="empty">${icon('playlist', 'i big')}<p>No songs for this playlist yet.</p></div>`; return; }
  const about = PLNAME ? PLNAME.note : p.note;
  body.innerHTML = `<div class="pl-head"><div class="pl-icon">${icon('playlist')}</div><div class="pl-txt" id="plName"><h1>${esc(plTitle(p))}</h1><p>${about ? `${esc(about)}, ` : ''}${plural(p.tracks.length, 'song')}</p></div>
      <button class="icon-btn pl-edit" id="plEdit" aria-label="Name and description">${icon('edit')}</button></div>
    <div class="btn-row">
      <button class="btn filled" id="plCopy">${icon('copy')}Copy list</button>
      <button class="btn tonal" id="plShare">${icon('share')}Share</button>
      ${p.kind === 'dig' ? `<button class="btn text" data-pl="dig">${icon('shuffle')}Dig again</button>` : ''}
    </div>
    ${p.kind === 'genre' ? genrePlControls(p) : p.kind === 'discover' ? discoverPlControls(p) : ''}
    <div id="plTidalBlock">${tidalBlock()}</div>
    <div class="card how">
      <div class="card-title">${icon('open')}${(ST.tidal || {}).loggedIn ? 'Or with TuneMyMusic or Soundiiz (also for Spotify)' : 'Into TIDAL (or Spotify)'}</div>
      <ol class="steps">
        <li>Tap <b>Copy list</b>: one song per line, "Artist - Title".</li>
        <li>Open TuneMyMusic or Soundiiz, pick importing from text, and paste.</li>
        <li>Choose TIDAL as the destination. Both are free for a playlist this size.</li>
      </ol>
      <div class="btn-row"><button class="btn text small link" data-url="https://www.tunemymusic.com/transfer">TuneMyMusic</button><button class="btn text small link" data-url="https://soundiiz.com/">Soundiiz</button></div>
    </div>
    <div class="rank">${p.tracks.map((t, i) => `<button class="r" ${trackAttrs(t.artist, t.title, { album: t.album })}><span class="n">${i + 1}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}${t.album ? `, ${esc(t.album)}` : ''}${t.note ? `, ${esc(t.note)}` : ''}</small></span>${t.source !== 'new' ? `<span class="v">${t.plays}×</span>` : p.kind === 'discover' || p.kind === 'artistBest' ? '' : '<span class="v new-tag">new</span>'}</button>`).join('')}</div>
    ${p.kind === 'genre' && p.tracks.some((t) => t.source === 'new') ? '<p class="hint">"New" songs are ones Last.fm finds similar to your favorites here, that you haven\'t played.</p>' : ''}
    ${p.kind === 'discover' ? '<p class="hint">Each artist\'s best-known songs on Last.fm, two at most of each. "Make another" picks other artists and songs; the week\'s mix stays until Monday.</p>' : ''}
    ${p.kind === 'setlist' ? `<p class="hint">"New" are songs you haven't played yet. Setlist from <button class="link-inline" data-url="${esc(p.url || 'https://www.setlist.fm/')}">setlist.fm</button>.</p>` : ''}`;
  wireGenrePlControls();
  $('#plEdit').onclick = $('#plName').onclick = editPlaylistName;
  $('#plTidalBlock').onclick = (e) => {
    if (e.target.closest('#plTidalLogin')) send({ t: 'tidalLogin' });
    if (e.target.closest('#plTidal')) {
      TIDALJOB = { state: 'finding', done: 0, total: p.tracks.length, found: 0, name: plTitle(p) };
      $('#plTidalBlock').innerHTML = tidalBlock();
      send({ t: 'tidalPlaylist', name: plTitle(p), note: plNote(p), tracks: p.tracks.map((t) => ({ artist: t.artist, title: t.title })) });
    }
  };
  $('#plCopy').onclick = () => {
    const text = playlistText(p);
    if (Native) send({ t: 'copyText', text, label: plTitle(p) });
    else if (navigator.clipboard) navigator.clipboard.writeText(text).then(() => toast('Copied'), () => toast('Copying failed'));
  };
  $('#plShare').onclick = () => {
    const note = plNote(p);
    const text = `${plTitle(p)}${note ? `\n${note}` : ''}\n\n${playlistText(p)}`;
    if (Native) send({ t: 'shareText', text, subject: plTitle(p) });
    else if (navigator.share) navigator.share({ title: plTitle(p), text }).catch(() => {});
    else toast('Sharing works in the app');
  };
}

// ---- your concerts, from setlist.fm ----

let CONCERTS = null;
const CONCERT_BY_ID = {};
const TICKET_ART = `<svg viewBox="0 0 120 52" aria-hidden="true"><g style="fill:var(--md-surface-container-highest)"><path d="M10 10h66a6 6 0 0 0 12 0h22v32H88a6 6 0 0 0-12 0H10z"/></g>
  <g style="stroke:var(--md-outline);stroke-width:1.2;fill:none;stroke-dasharray:2 3"><path d="M82 16v20"/></g><g style="fill:var(--md-primary)"><rect x="18" y="18" width="40" height="5" rx="2.5"/><rect x="18" y="28" width="26" height="4" rx="2" opacity=".5"/><circle cx="98" cy="26" r="5"/></g></svg>`;
function openConcerts() {
  openPage('concertsPage');
  if (!CONCERTS) CONCERTS = { loading: true };
  renderConcerts();
  send({ t: 'concerts' });
}
window.onConcerts = (c) => {
  CONCERTS = c;
  if (isOpen('concertsPage')) renderConcerts();
};
/** What a concert did to your listening: your plays of the artist in the four weeks after it, and before. */
function glowText(c) {
  if (c.after == null || (!c.after && !c.before)) return '';
  return c.running ? `${plural(c.after, 'play')} of them since, ${c.before} in the four weeks before` : `${plural(c.after, 'play')} of them in the four weeks after, ${c.before} before`;
}
/** A concert's row: the date as a stub, then the artist (or, on their own page, the place). */
function concertRow(c, byArtist) {
  CONCERT_BY_ID[c.id] = c;
  const d = new Date(`${c.date}T12:00:00`);
  const place = [c.venue, c.city].filter(Boolean).join(', ');
  const glow = glowText(c);
  const hot = c.after != null && !c.running && c.after >= 5 && c.after >= 2 * c.before;
  return `<button class="r concert" data-concert="${esc(c.id)}"><span class="cdate"><b>${d.getDate()}</b><small>${esc(d.toLocaleDateString([], { month: 'short' }))}</small></span>
    <span class="t"><b>${esc(byArtist ? place || c.artist : c.artist)}</b><small>${esc(byArtist ? `${d.getFullYear()}${c.songs.length ? `, ${plural(c.songs.length, 'song')}` : ''}` : place)}</small>${glow ? `<small class="glow${hot ? ' hot' : ''}">${hot ? icon('fire', 'i inl') : ''}${esc(glow)}</small>` : ''}</span>${icon('chevron-right')}</button>`;
}
/** On an artist's page: the shows of theirs you were at. */
/** A show as a ticket stub: the date torn off on the left, the place and a line about it. */
function ticketHtml(date, place, line, attrs) {
  const d = new Date(`${date}T12:00:00`), thisYear = d.getFullYear() === new Date().getFullYear();
  return `<button class="tk" ${attrs}><span class="tkd"><b>${d.getDate()}</b><small>${esc(d.toLocaleDateString([], { month: 'short' }))}${thisYear ? '' : ` ${String(d.getFullYear()).slice(2)}`}</small></span>
    <span class="tki"><b>${esc(place)}</b><small>${esc(line)}</small></span></button>`;
}
function concertsBlock(list) {
  if (!list || !list.length) return '';
  if (pageTabs()) {
    return `<div class="viz"><h2>You saw them live</h2><span class="sub">${list.length === 1 ? 'Once' : `${list.length} times`}. Tap a show for that night's setlist.</span>
      <div class="tix">${list.map((c) => { CONCERT_BY_ID[c.id] = c; return ticketHtml(c.date, c.venue || c.city, [c.city, c.after != null ? `${plural(c.after, 'play')} after` : ''].filter(Boolean).join(', '), `data-concert="${esc(c.id)}"`); }).join('')}</div></div>`;
  }
  return `<div class="viz"><h2>You saw them live</h2><span class="sub">${list.length === 1 ? 'Once' : `${list.length} times`}, from your setlist.fm. Tap a show for that night's setlist.</span>
    <div class="rank concerts">${list.map((c) => concertRow(c, true)).join('')}</div></div>`;
}
/** The night's setlist as a playlist, with your plays of each song; no setlist: its setlist.fm page. */
function openConcertPlaylist(c) {
  if (!c) return;
  if (!c.songs.length) { openUrl(c.url || 'https://www.setlist.fm/'); return; }
  openPage('playlistPage');
  TIDALJOB = null;
  PLNAME = null;
  PLAYLIST = {
    kind: 'setlist', url: c.url,
    name: `${c.artist} live in ${c.city || c.venue}`,
    note: `The setlist the night you were there, ${showDate(c.date)}${c.venue ? ` at ${c.venue}` : ''}`,
    tracks: c.songs.map((x) => ({ artist: c.artist, title: x.title, album: '', plays: x.plays, source: x.plays ? 'core' : 'new', note: songNote(x) })),
  };
  renderPlaylist();
}
/** Concerts in a Stats period, as highlights. */
function liveInsights(cs, kind) {
  if (!cs.length) return [];
  const link = (c) => `<button class="inline-link" data-artist="${esc(c.artist)}">${esc(c.artist)}</button>`;
  if (cs.length >= 3) return [['people', `You went to <b>${cs.length} concerts</b>: ${cs.slice(0, 3).map(link).join(', ')}${cs.length > 3 ? ' and more' : ''}.`]];
  return cs.map((c) => ['people', `You saw ${link(c)} live${c.venue ? ` at ${esc(c.venue)}` : ''}${kind === 'day' ? '' : ` on ${esc(showDate(c.date))}`}${c.after != null && c.after > c.before ? `, then played them <b>${plural(c.after, 'time')}</b> ${c.running ? 'since' : 'in four weeks'}` : ''}.`]);
}
function renderConcerts() {
  const C = CONCERTS, st = ST.setlist || {};
  if (!C || !isOpen('concertsPage')) return;
  let h = '';
  if (!st.hasKey || !st.user) {
    h = `<div class="empty">${icon('people', 'i big')}<p>${st.hasKey ? 'Add your setlist.fm username' : 'Set up setlist.fm with a free API key, then add your username'} to see the shows you marked "I was there" on setlist.fm.</p>
      <button class="btn filled" data-go="slSection">Set up setlist.fm</button></div>`;
  } else if (C.loading || (!(C.shows || []).length && (C.busy || st.concertsBusy))) {
    h = `<div class="empty"><p>Fetching your concerts from setlist.fm…</p></div><div class="lin inline indet"><i></i></div>`;
  } else if (!(C.shows || []).length) {
    h = `<div class="empty"><p>${esc(C.error || st.concertsError || 'No concerts yet.')}</p><button class="btn tonal" id="concertsRefresh">Try again</button></div>`;
  } else {
    const shows = C.shows;
    const artists = {};
    shows.forEach((c) => { artists[c.artist] = (artists[c.artist] || 0) + 1; });
    const most = Object.entries(artists).filter(([, n]) => n > 1).sort((a, b) => b[1] - a[1]).slice(0, 8);
    const first = shows[shows.length - 1].date.slice(0, 4);
    h = `<div class="rank-hero"><h1>Your concerts</h1><p>${plural(shows.length, 'show')} of ${plural(Object.keys(artists).length, 'artist')} since ${esc(first)}</p></div>`;
    const glows = shows.filter((c) => c.after != null && !c.running && c.after >= 5).sort((a, b) => b.after - b.before - (a.after - a.before)).slice(0, 3);
    if (glows.length) {
      h += `<div class="viz"><h2>Afterglow</h2><span class="sub">The shows that sent you back to their music most, four weeks after against four weeks before</span>
        <div class="rank concerts">${glows.map((c) => concertRow(c, false)).join('')}</div></div>`;
    }
    if (most.length) h += `<div class="viz"><h2>Seen most</h2><div class="tags">${most.map(([a, n]) => `<button class="chip link" data-artist="${esc(a)}">${esc(a)} <small>${n}×</small></button>`).join('')}</div></div>`;
    const years = {};
    shows.forEach((c) => { (years[c.date.slice(0, 4)] = years[c.date.slice(0, 4)] || []).push(c); });
    Object.keys(years).sort().reverse().forEach((y) => {
      h += `<h3 class="live-h">${y}: ${plural(years[y].length, 'show')}</h3><div class="rank concerts">${years[y].map((c) => concertRow(c, false)).join('')}</div>`;
    });
    h += `<p class="hint">From setlist.fm: the shows ${esc(st.user)} marked "I was there". Tap one for that night's setlist. <button class="link-inline" id="concertsRefresh">Update now</button></p>`;
  }
  morph($('#concertsBody'), h);
}

// ---- a whole top list: the period's songs, albums or artists by plays, the page you came from marked ----

let RANKS = null;
const RANK_NOUN = { tracks: 'songs', albums: 'albums', artists: 'artists' };
/** From a tile (its data-r* attributes in [d]) or the Stats top list ([shown]: with the Stats filters). */
function openRanks(kind, range, d, shown) {
  // The page's own song, album and artist, to find in each list.
  const focus = {
    tracks: d.rt && kind === 'tracks' ? { artist: d.ra, title: d.rt } : null,
    albums: kind === 'albums' && d.rt ? { artist: d.ra, title: d.rt } : d.ral ? { artist: d.raa || d.ra, title: d.ral } : null,
    artists: d.ra ? { artist: d.ra, title: '' } : null,
  };
  openPage('rankPage');
  RANKS = { kind, range: range || 'all', focus, shown, rows: {}, total: 0, pos: -1, loading: true, req: ++REQ, scrolled: false, more: -1 };
  renderRanks();
  askRanks(0, true);
}
function askRanks(from, around) {
  const R = RANKS, f = R.focus[R.kind];
  send({ t: 'rankList', kind: R.kind, range: R.range, shown: R.shown, from, n: 100, around, artist: f ? f.artist : '', title: f ? f.title : '', req: R.req });
}
window.onRankList = (j) => {
  const R = RANKS;
  if (!R || j.req !== R.req) return;
  j.rows.forEach((x) => { R.rows[x.pos] = x; });
  Object.assign(R, { total: j.total, pos: j.focus, info: j.range, loading: false, more: -1 });
  renderRanks();
};
function rankRow(R, x, max) {
  const bar = `<span class="v">${num(x.plays)}</span><div class="bar"><i style="width:${(x.plays / max) * 100}%"></i></div>`;
  const cls = `r${R.kind === 'tracks' ? '' : ' rc'}${x.pos === R.pos ? ' here' : ''}`;
  const n = `<span class="n">${num(x.rank)}</span>`;
  if (R.kind === 'artists') return `<button class="${cls}" data-pos="${x.pos}" data-artist="${esc(x.name)}">${n}${coverHtml(x.photo, x.name, x.name, 'tiny round')}<span class="t"><b>${esc(x.name)}</b></span>${bar}</button>`;
  if (R.kind === 'albums') return `<button class="${cls}" data-pos="${x.pos}" ${albumAttrs(x.artist, x.title)} data-cover="${esc(x.cover)}">${n}${coverHtml(x.cover, x.title, x.artist, 'tiny')}<span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)}</small></span>${bar}</button>`;
  return `<button class="${cls}" data-pos="${x.pos}" ${trackAttrs(x.artist, x.title)}>${n}<span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)}</small></span>${bar}</button>`;
}
function renderRanks() {
  const R = RANKS;
  if (!R || !isOpen('rankPage')) return;
  const noun = RANK_NOUN[R.kind];
  const seg = `<div class="seg small" id="rankSeg">${['tracks', 'albums', 'artists'].map((k) => `<button data-rkind="${k}" class="${k === R.kind ? 'on' : ''}">${RANK_NOUN[k].charAt(0).toUpperCase() + RANK_NOUN[k].slice(1)}</button>`).join('')}</div>`;
  const when = R.info ? `${periodName(R.info)}${R.shown && (statsGenre || statsDecade) ? `, ${filterWords(statsGenre, statsDecade)}` : ''}` : '';
  let h = `<div class="rank-hero"><h1>Your top ${noun}</h1><p>${R.loading ? 'Counting…' : `${esc(when)}: ${num(R.total)} ${noun}`}</p></div>${seg}`;
  if (R.loading) h += '<div class="lin inline indet"><i></i></div>';
  else if (!R.total) h += '<p class="hint">Nothing in this period.</p>';
  else {
    const have = Object.keys(R.rows).map(Number).sort((a, b) => a - b);
    const max = Math.max(1, (R.rows[0] || R.rows[have[0]]).plays);
    let rows = '', prev = -1;
    const gap = (from, to) => `<button class="rank-gap" data-rgap="${from}">${R.more === from ? 'Counting…' : `${icon('down')}${num(to - from)} more`}</button>`;
    for (const p of have) {
      if (p > prev + 1) rows += gap(prev + 1, p);
      rows += rankRow(R, R.rows[p], max);
      prev = p;
    }
    if (prev < R.total - 1) rows += gap(prev + 1, R.total);
    h += `<div class="rank ranks">${rows}</div>`;
    if (R.pos < 0 && R.focus[R.kind]) h += `<p class="hint">${R.kind === 'tracks' ? 'This song' : R.kind === 'albums' ? 'This album' : 'This artist'} has no plays in this period.</p>`;
  }
  morph($('#rankBody'), h);
  if (!R.loading && !R.scrolled) {
    R.scrolled = true;
    const here = $('#rankBody .r.here');
    if (here && R.pos > 4) requestAnimationFrame(() => here.scrollIntoView({ block: 'center' }));
  }
}

// ---- setlists from setlist.fm, on artist pages ----

let LIVE = null; // the artist page's setlists: { name, req, loading } until setlist.fm answers
let liveReq = 0;
function requestLive(name) {
  LIVE = { name, loading: true, req: ++liveReq };
  if ((ST.setlist || {}).hasKey) send({ t: 'setlists', name, req: LIVE.req });
}
window.onSetlists = (s) => {
  if (!LIVE || s.req !== LIVE.req) return;
  LIVE = { ...s, name: LIVE.name, req: LIVE.req, loading: false };
  const slot = $('#liveSlot');
  // In tabs the whole tab is drawn again: an artist setlist.fm doesn't know leaves it saying so.
  if (slot && isOpen('artistPage') && ARTIST && norm(ARTIST.name) === norm(LIVE.name)) { if (pageTabs()) renderArtist(); else slot.innerHTML = liveHtml(); }
};
const showDate = (iso) => new Date(`${iso}T12:00:00`).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
const songNote = (x) => [x.cover ? `cover of ${x.cover}` : '', x.with ? `with ${x.with}` : '', x.encore ? 'encore' : ''].filter(Boolean).join(', ');

/** "On stage lately" for artist [name]: the songs of their usual set lately (as a concert playlist) and their recent shows. */
function liveHtml(name) {
  // Back on an earlier artist page: its own setlists (setlist.fm's answers are kept a while in the app).
  if (name && (!LIVE || norm(LIVE.name) !== norm(name))) requestLive(name);
  const L = LIVE;
  if (!L) return '';
  const head = '<h2>On stage lately</h2>';
  if (!(ST.setlist || {}).hasKey) {
    return `<div class="viz live">${head}<span class="sub">Their recent setlists, and what they play lately as a concert playlist, with a free key from setlist.fm.</span>
      <div class="btn-row"><button class="btn tonal" data-go="slSection">${icon('open')}Set up setlist.fm</button></div></div>`;
  }
  if (L.loading) return `<div class="viz live">${head}<span class="sub">Asking setlist.fm…</span><div class="lin inline indet"><i></i></div></div>`;
  if (L.error) return `<div class="viz live">${head}<p class="hint">${esc(L.error)}</p></div>`;
  const shows = L.shows || [], typ = L.typical || [], next = (L.upcoming || [])[0];
  if (L.unknown || (!shows.length && !next)) return '';
  let h = `<div class="viz live">${head}<span class="sub">${typ.length ? `Their usual set at their last ${plural(L.of, 'show')} with a setlist, in its usual order` : 'Their shows on setlist.fm'}</span>`;
  if (next) h += `<button class="live-next" data-url="${esc(next.url)}">${icon('calendar')}<span><b>Next: ${esc(showDate(next.date))}</b><small>${esc([next.venue, next.city].filter(Boolean).join(', '))}</small></span></button>`;
  const plays = (x) => (x.plays ? `<span class="v cnt">${num(x.plays)}×</span>` : '<span class="v new-tag">new</span>');
  if (typ.length && pageTabs()) {
    // In tabs: the set in two columns, top to bottom, then the next column.
    h += `<div class="rank set2" style="grid-template-rows:repeat(${Math.ceil(typ.length / 2)},auto)">${typ.map((x, i) => `<button class="r" ${trackAttrs(L.name, x.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b></span>${plays(x)}</button>`).join('')}</div>
      <div class="btn-row"><button class="btn filled" data-livepl="usual">${icon('playlist')}Make a concert playlist</button></div>`;
  } else if (typ.length) {
    h += `<div class="rank">${typ.map((x, i) => `<button class="r" ${trackAttrs(L.name, x.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b><small>${esc([`at ${x.n} of ${L.of}`, songNote(x)].filter(Boolean).join(', '))}</small></span>${plays(x)}</button>`).join('')}</div>
      <div class="btn-row"><button class="btn filled" data-livepl="usual">${icon('playlist')}Make a concert playlist</button></div>`;
  }
  if (shows.length && pageTabs()) {
    h += `<h3 class="live-h">Recent shows</h3><div class="tix">${shows.slice(0, 15).map((s, i) => ticketHtml(s.date, s.city || s.venue, `${s.city ? `${s.venue}, ` : ''}${plural(s.songs.length, 'song')}`, `data-show="${i}"`)).join('')}</div>`;
  } else if (shows.length) {
    h += `<h3 class="live-h">Recent shows</h3><div class="rank">${shows.slice(0, 8).map((s, i) => `<button class="r" data-show="${i}"><span class="t"><b>${esc(showDate(s.date))}, ${esc(s.city || s.venue)}</b><small>${esc([s.venue, s.country].filter(Boolean).join(', '))}, ${plural(s.songs.length, 'song')}</small></span><span class="v">${icon('chevron-right')}</span></button>`).join('')}</div>`;
  }
  return `${h}<p class="hint">Setlists from <button class="link-inline" data-url="${esc(L.url || 'https://www.setlist.fm/')}">setlist.fm</button>, written down by fans. "New" are songs you haven't played yet.</p></div>`;
}

/** A concert playlist: their usual set lately ([i] < 0) or the setlist of show [i]. */
function openLivePlaylist(i) {
  const L = LIVE;
  if (!L || L.loading) return;
  const s = i >= 0 ? (L.shows || [])[i] : null;
  const songs = s ? s.songs : L.typical || [];
  if (!songs.length) return;
  openPage('playlistPage');
  TIDALJOB = null;
  PLNAME = null;
  PLAYLIST = {
    kind: 'setlist', url: s ? s.url : L.url,
    name: s ? `${L.name} live in ${s.city || s.venue}` : `${L.name} live`,
    note: s ? `Their setlist on ${showDate(s.date)}${s.venue ? ` at ${s.venue}` : ''}` : `What they played most at their last ${plural(L.of, 'show')}, in their usual order`,
    tracks: songs.map((x) => ({ artist: L.name, title: x.title, album: '', plays: x.plays, source: x.plays ? 'core' : 'new', note: songNote(x) })),
  };
  renderPlaylist();
}

// ---- one artist ----

let ARTIST = null;
function openArtist(name) {
  if (!name) return;
  openPage('artistPage');
  ARTIST = { name, loading: true, req: ++REQ };
  requestDiscography(name);
  requestLive(name);
  $('#artistBody').innerHTML = `<div class="empty"><p>Looking up ${esc(name)}…</p></div>`;
  send({ t: 'artist', name, range: statsRange, req: ARTIST.req });
}
window.onArtist = (a) => {
  ARTIST = deliver('artistPage', a.req, ARTIST, (s, shown) => {
    const next = { ...a, req: s.req, tab: s.tab };
    if (shown) { const changed = !sameData(next, s); ARTIST = next; if (changed) renderArtist(); }
    return next;
  });
};

function renderArtist() {
  const a = ARTIST;
  if (!a || a.loading || !isOpen('artistPage')) return;
  if (a.missing) {
    const sug = ((DISCOVER || {}).artists || []).find((x) => norm(x.name) === norm(a.name));
    const best = a.best || [];
    $('#artistBody').innerHTML = `<div class="hero new-artist">${a.photo ? coverHtml(a.photo, a.name, a.name, 'round') : ''}<div><h1>${esc(a.name)}</h1>
        <div class="meta">${(ST.history || {}).count > 0 ? 'No plays in your history yet' : 'Import your Last.fm history on the Stats tab to see their story here'}</div></div></div>${listenRow('artists', a.name)}
      ${sug ? `<button class="card fy-because" data-why="${esc(a.name)}"><small>Suggested for you</small><span>${esc(becauseText(sug))}</span>${icon('chevron-right')}</button>` : ''}
      ${best.length ? `<div class="viz"><div class="xhead"><h2>Their best-known songs</h2><button class="btn tonal small" data-pl="artistBest" data-artist="${esc(a.name)}">${icon('playlist')}Playlist</button></div><span class="sub">Most played on Last.fm</span>
        <div class="rank">${best.map((t, i) => `<div class="r"><span class="n">${i + 1}</span><span class="t"><b>${esc(t)}</b></span></div>`).join('')}</div></div>` : ''}
      <div id="discoSlot">${discoHtml(a.name)}</div><div id="liveSlot">${liveHtml(a.name)}</div>`;
    $('#artistBody').querySelectorAll('[data-why]').forEach((b) => { b.onclick = () => openWhy(b.dataset.why); });
    return;
  }
  const mo = (ms) => new Date(ms).toLocaleDateString([], { month: 'short', year: 'numeric' });
  const pic = a.photo || a.cover || '';
  let h = `<div class="artist-hero page-hero"><i class="glow"></i>
      ${holoCover(pic, a.name, a.name, { frame: true, key: `artist|${a.name}` })}
      <div class="ah-text"><span class="k">Artist</span><h1 class="fit">${esc(a.name)}</h1>
        <div class="meta">${plural(a.plays, 'play')} since ${esc(mo(a.first.ts))}, on ${plural(a.days, 'day')}</div></div></div>
    ${a.genres.length ? `<div class="tags">${a.genres.map((g) => `<button class="chip link" data-genre="${esc(genreKeyOf(g))}" data-glabel="${esc(g)}">${esc(g)}</button>`).join('')}</div>` : ''}
    ${listenRow('artists', a.name)}`;
  // The page in parts: one after the other, or in tabs (Settings → Look).
  const P = {};
  P.tiles = `<p class="hint center">First heard ${esc(dateLabel(a.first.ts))}: ${esc(a.first.track)}</p>
    <div class="tiles">
      ${tile('All time', num(a.plays), a.rank ? `plays, #${a.rank} of ${compact(a.artists)} artists` : `plays on ${plural(a.days, 'day')}`, '', 'music', a.rank ? rankAttrs('artists', 'all', { artist: a.name }) : '')}
      ${tile(periodName(a.range), a.range.rank ? `#${a.range.rank}` : '–', a.range.plays ? plural(a.range.plays, 'play') : 'no plays', '', 'calendar', a.range.rank ? rankAttrs('artists', a.range.id, { artist: a.name }) : '')}
      ${tile('Busiest month', mo(a.busiestMonth.start), plural(a.busiestMonth.count, 'play'), '', 'chart')}
      ${tile('Longest streak', plural(a.streak, 'day'), a.streak > 1 ? `until ${dateLabel(a.streakEnd)}` : '', '', 'fire')}
    </div>`;
  const ra = a.rangeAlbums || [];
  P.period = periodBlock(a.range, a.rangeTracks, 'artist', (x) => trackAttrs(a.name, x.title),
    ra.length ? `<p class="hint">Mostly from ${ra.map((x) => `<button class="link-inline" ${albumAttrs(a.name, x.title)}>${esc(x.title)}</button> (${num(x.plays)})`).join(', ')}.</p>` : '');
  P.vs = vsBlock(a.global, esc(a.name));
  P.origin = originHtml(a);
  P.disco = `<div id="discoSlot">${discoHtml(a.name)}</div>`;
  P.river = a.river ? riverViz(a.river, { title: 'Their albums over time', other: 'Singles and the rest',
    sub: (s) => `Plays per ${a.river.unit} of each album since you first played them; the top ${a.river.artists.length} are <b>${s}%</b> of it.`,
    link: (n) => albumAttrs(a.name, n) }) : '';
  P.life = lifeCharts(a, 'them', pic, a.name + a.name);
  P.record = '';
  if (STATS && STATS.record && STATS.record.days) {
    const idx = STATS.record.artists.findIndex((n) => norm(n) === norm(a.name));
    if (idx >= 0) {
      RECS.artist = { rec: STATS.record, spot: idx, label: periodName(STATS.range) };
      P.record = recordViz(STATS.record, 'artist', 'On your record', `Their plays lit up among everything you played (${periodName(STATS.range)}).`, { legend: false });
    }
  }
  P.tracks = '';
  if (a.tracks.length) {
    P.tracks = `<div class="viz"><h2>Top tracks</h2><span class="sub">Hold a song to hide it from your stats or fix its name.</span><div class="rank">${a.tracks.slice(0, topN('artist')).map((x, i) => `<button class="r" ${trackAttrs(a.name, x.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b></span><span class="v cnt">${num(x.plays)}×</span></button>`).join('')}</div>${topMore('artist', a.tracks.length)}</div>`;
  }
  P.albums = a.albums.length ? `<div class="viz"><h2>Albums</h2><div class="rank">${a.albums.map((x) => `<button class="r with-cover" ${albumAttrs(a.name, x.title)} data-cover="${esc(x.cover)}">${coverHtml(x.cover, x.title, a.name, 'tiny')}<span class="t"><b>${esc(x.title)}</b><small>${x.owned ? 'on your shelf' : ''}</small></span><span class="v">${num(x.plays)}</span></button>`).join('')}</div></div>` : '';
  P.concerts = concertsBlock(a.concerts);
  P.live = `<div id="liveSlot">${liveHtml(a.name)}</div>`;
  P.global = globalTopHtml(a);
  P.shelf = a.owned.length ? `<div class="viz"><h2>On your shelf</h2><div class="covers">${a.owned.map((x) => `<button class="rec" data-rec="${x.id}">${coverHtml(x.cover, x.title, x.artist)}<b>${esc(x.title)}</b><small>${x.year || ''}</small></button>`).join('')}</div></div>` : '';
  // The live tab waits for setlist.fm (liveSlot fills in); empty, it says so.
  if (a.tab === 'live' && !a.concerts && LIVE && !LIVE.loading && !liveHtml()) P.live = '<div class="empty small"><p>No shows of theirs on setlist.fm yet, and none you were at.</p></div>';
  h += tabbedHtml('artistPage', a.tab, ARTIST_TABS, ARTIST_LONG, P);
  keepHolo($('#artistBody'), h);
  tint($('#artistBody'), pic, a.name + a.name);
  fitText($('#artistBody .fit'));
  drawRecords();
  syncPageTabs($('#artistPage'));
}

// ---- long pages in tabs (Settings → Look): artist, album and genre pages, Stats and Explore; or each as one long page ----

const layoutPref = (key) => { try { return localStorage.getItem(key) !== 'long'; } catch (_) { return true; } };
/** Artist, album and genre pages in tabs, Explore as rows; or each as one long page. Stats has its own choice (statsView). */
const pageTabs = () => layoutPref('pageLayout');
const mainCompact = () => layoutPref('mainLayout');
/** Each place's tabs ([key, label, its parts]) and the long page's order of the same parts. */
const ARTIST_TABS = [['overview', 'Overview', ['tiles', 'period', 'vs', 'origin']], ['listening', 'Listening', ['river', 'life', 'record']],
  ['music', 'Music', ['tracks', 'albums', 'shelf', 'disco', 'global']], ['live', 'Live', ['concerts', 'live']]];
const ARTIST_LONG = ['tiles', 'period', 'vs', 'origin', 'disco', 'river', 'life', 'record', 'tracks', 'albums', 'concerts', 'live', 'global', 'shelf'];
const ALBUM_TABS = [['overview', 'Overview', ['tiles', 'period', 'vs']], ['songs', 'Songs', ['shape', 'songs']], ['listening', 'Listening', ['clicked', 'river', 'life']]];
const ALBUM_LONG = ['tiles', 'period', 'shape', 'songs', 'vs', 'clicked', 'river', 'life'];
const GENRE_TABS = [['overview', 'Overview', ['tiles', 'inside']], ['artists', 'Artists', ['artists', 'everyone']],
  ['music', 'Music', ['albums', 'songs', 'lost', 'shelf', 'decades']], ['listening', 'Listening', ['life']]];
const GENRE_LONG = ['tiles', 'inside', 'artists', 'lost', 'albums', 'songs', 'everyone', 'decades', 'life', 'shelf'];
/** Explore as one long page: its parts in this order. */
const EXPLORE_LONG = ['dig', 'foryou', 'lost', 'lostAlbums', 'regulars', 'oneDay', 'deepCuts', 'onThisDay'];

/** Where tabs are: whether they're on there, and how another opens. */
const TAB_HOSTS = {
  artistPage: { on: pageTabs, show: (t) => PAGE_STATE.artistPage.set({ ...ARTIST, tab: t }) },
  albumPage: { on: pageTabs, show: (t) => PAGE_STATE.albumPage.set({ ...ALBUM, tab: t }) },
  genrePage: { on: pageTabs, show: (t) => PAGE_STATE.genrePage.set({ ...GENRE, tab: t }) },
};
/** What each place shows in tabs now: its tabs in order and the open one. */
const TAB_SHOWN = {};
const hostScroller = (host) => (host.classList.contains('page') ? host : document.scrollingElement);
/**
 * A place's parts [P]: all of them in [long] order, or in tabs the tab bar and every tab side by side, the [open] one
 * (else the first) in view. The others wait hidden beside it, already built, so a swipe only has to move them.
 * [empty]: what an empty tab says; [drop]: empty tabs are left out instead; [tail]: comes after the parts.
 */
function tabbedHtml(id, open, tabs, long, P, { empty = '', drop = false, tail = '' } = {}) {
  if (!TAB_HOSTS[id].on()) { TAB_SHOWN[id] = null; return long.map((k) => P[k] || '').join('') + tail; }
  let shown = tabs.map(([k, l, parts]) => ({ k, l, html: parts.map((p) => P[p] || '').join('') }));
  if (drop) shown = shown.filter((t) => t.html);
  if (!shown.length) { TAB_SHOWN[id] = null; return tail; }
  const cur = shown.find((t) => t.k === open) || shown[0];
  const at = shown.indexOf(cur);
  TAB_SHOWN[id] = { keys: shown.map((t) => t.k), open: cur.k };
  // The open one is marked by an attribute, not a class: a page's update keeps blocks whose class stays, so no tab is rebuilt.
  const body = (t, i) => `<div class="atab-body" data-tab="${t.k}" style="--i:${i - at}"${i === at ? ' data-open' : ' aria-hidden="true"'}>${t.html || empty || '<div class="empty small"><p>Nothing here yet.</p></div>'}${tail}</div>`;
  return `<i class="tab-anchor"></i><div class="atabs"><div class="seg small">${shown.map((t) => `<button data-ptab="${t.k}" class="${t.k === cur.k ? 'on' : ''}">${t.l}</button>`).join('')}</div></div>
    <div class="atab-clip"><div class="atab-track">${shown.map(body).join('')}</div></div>`;
}
/** Where the scroll is when the tabs sit right where they stick; null without tabs. */
function tabsTop(host) {
  const anchor = host.querySelector('.tab-anchor'), bar = host.querySelector('.atabs');
  if (!anchor || !bar) return null;
  const sc = hostScroller(host);
  const top = sc === document.scrollingElement ? 0 : sc.getBoundingClientRect().top;
  return Math.max(0, anchor.getBoundingClientRect().top - top + sc.scrollTop - (parseFloat(getComputedStyle(bar).top) || 0));
}
/** Another tab of [id]: scrolled down past the tabs, the new one starts at its top, right under them. */
function openTab(id, t) {
  const host = $('#' + id), s = TAB_SHOWN[id];
  if (!s || s.open === t) return;
  const y = tabsTop(host), sc = hostScroller(host);
  TAB_HOSTS[id].show(t);
  if (y != null && sc.scrollTop > y) {
    sc.dataset.quiet = '1';
    sc.scrollTop = y;
    setTimeout(() => { delete sc.dataset.quiet; }, 120);
  }
}
/** The tab next to the open one ([dir] 1: right, -1: left), or null. */
function tabNext(host, dir) {
  const s = TAB_SHOWN[host.id];
  if (!s || !host.querySelector('.atabs')) return null;
  return s.keys[s.keys.indexOf(s.open) + dir] || null;
}
/**
 * The tabs beside the open one come into view (still off screen): each at the height it will have once open, right
 * under the tabs when the page is scrolled past them. Done as a finger touches down, before it's clear it swipes.
 */
function tabsWake(host, track) {
  if (track.classList.contains('live')) return;
  const y = tabsTop(host), sc = hostScroller(host);
  const off = `${y != null ? Math.max(0, sc.scrollTop - y) : 0}px`;
  track.querySelectorAll(':scope > .atab-body:not([data-open])').forEach((b) => { b.style.top = off; });
  track.classList.add('live');
}
function tabsSleep(track) {
  if (track._sliding) return;
  track.classList.remove('live');
  track.style.transform = '';
  track.querySelectorAll(':scope > .atab-body:not([data-open])').forEach((b) => { b.style.top = ''; });
}
/** The space between two tabs side by side: the page's margins on both. */
const TAB_GAP = 32;
/** The rest of the way to tab [key], [steps] tabs over, at about the pace the finger left it ([v] px/ms); then it's the open one. */
function tabGo(host, track, steps, key, from, v, ms) {
  const w = track.offsetWidth + TAB_GAP, to = -steps * w, left = Math.abs(to - from);
  const t = ms || Math.round(Math.min(300, Math.max(150, v > 0.2 ? (left / v) * 1.8 : 120 + (180 * left) / w)));
  host.querySelectorAll('.atabs [data-ptab]').forEach((b) => b.classList.toggle('on', b.dataset.ptab === key));
  track._sliding = true;
  track.style.transform = `translateX(${to}px)`;
  const a = track.animate([{ transform: `translateX(${from}px)` }, { transform: `translateX(${to}px)` }], { duration: t, easing: 'cubic-bezier(.25, .8, .3, 1)' });
  let over = false;
  const land = () => {
    if (over) return;
    over = true;
    track._sliding = false;
    // The tab already on screen becomes the open one where it is; the render after only changes which one that is.
    tabsSleep(track);
    openTab(host.id, key);
  };
  a.onfinish = land;
  a.oncancel = land;
  setTimeout(land, t + 250);
}
/** Not far enough: back where it was. */
function tabBack(track, from) {
  track.style.transform = '';
  const a = track.animate([{ transform: `translateX(${from}px)` }, { transform: 'none' }], { duration: 220, easing: EASE_OUT });
  a.onfinish = a.oncancel = () => tabsSleep(track);
}
/** A tap on a tab slides over to it the way a swipe would. */
function tabTap(host, key) {
  const s = TAB_SHOWN[host.id], track = host.querySelector('.atab-track');
  if (!s || !track || track._sliding || key === s.open) return;
  if (!s.keys.includes(key) || matchMedia('(prefers-reduced-motion: reduce)').matches) { openTab(host.id, key); return; }
  tabsWake(host, track);
  tabGo(host, track, s.keys.indexOf(key) - s.keys.indexOf(s.open), key, 0, 0, 280);
}
/** Rows that scroll sideways, charts you scrub, a zoomed record and fields keep their own drags. */
const SWIPE_SKIP = '.tix, .covers, .xcards, .chip-row, .na-row, svg, canvas, [data-col], [data-heat], [data-ridge], input, textarea';
/**
 * Swiping sideways on a place in tabs moves to the tab beside it like turning a page: the tabs sit side by side,
 * already built, and follow the finger; on letting go they slide the rest of the way or spring back. The screen's
 * edges stay Android's (back).
 */
function tabSwipe(host) {
  let s = null, swipedAt = 0;
  host.addEventListener('pointerdown', (e) => {
    s = null;
    const body = e.target.closest('.atab-body[data-open]');
    const track = body && body.parentElement;
    if (!body || track._sliding || !e.isPrimary || (e.pointerType === 'mouse' && e.button !== 0)) return;
    if (e.target.closest(SWIPE_SKIP)) return;
    if (e.clientX < 24 || e.clientX > window.innerWidth - 24) return;
    s = { x: e.clientX, y: e.clientY, track, on: false, woke: false, dx: 0, v: 0, t: performance.now(), frame: 0 };
  }, { passive: true });
  host.addEventListener('pointermove', (e) => {
    if (!s || !e.isPrimary) return;
    if (!s.on) {
      const dx = e.clientX - s.x, dy = e.clientY - s.y;
      if (Math.abs(dy) > 10 && Math.abs(dy) > Math.abs(dx)) { if (s.woke) tabsSleep(s.track); s = null; return; }
      // Ready a few pixels into a sideways move, before the tab itself moves: a touch or a scroll leaves the other tabs asleep.
      if (!s.woke && Math.abs(dx) > 4 && Math.abs(dx) > Math.abs(dy)) { s.woke = true; tabsWake(host, s.track); }
      if (Math.abs(dx) < 12 || Math.abs(dx) < Math.abs(dy) * 1.6) return;
      s.on = true;
      // It moves from here on, so it doesn't jump by the first 12 px.
      s.x += dx > 0 ? 12 : -12;
      s.t = performance.now();
    }
    const now = performance.now(), d = e.clientX - s.x;
    // How fast it goes, mostly from the last few moves.
    if (now > s.t) s.v = 0.6 * ((d - s.dx) / (now - s.t)) + 0.4 * s.v;
    s.t = now;
    s.dx = d;
    // One move a frame, however often the finger reports.
    if (s.frame) return;
    const st = s;
    st.frame = requestAnimationFrame(() => {
      st.frame = 0;
      if (s !== st) return;
      // No tab that way: it only gives a little.
      const free = tabNext(host, st.dx < 0 ? 1 : -1);
      st.track.style.transform = `translateX(${(free ? st.dx : st.dx * 0.22).toFixed(1)}px)`;
    });
  }, { passive: true });
  const end = (e) => {
    const st = s;
    s = null;
    if (!st) return;
    if (!st.on) { if (st.woke) tabsSleep(st.track); return; }
    if (st.frame) cancelAnimationFrame(st.frame);
    swipedAt = Date.now();
    const w = st.track.offsetWidth + TAB_GAP;
    const dir = st.dx < 0 ? 1 : -1, key = tabNext(host, dir);
    const from = key ? st.dx : st.dx * 0.22;
    // Its speed in the swipe's direction: past a third of the way and not flung back, or flung that way.
    const toward = -st.v * dir;
    const go = key && e.type === 'pointerup' && ((Math.abs(st.dx) > w * 0.33 && toward > -0.3) || (toward > 0.35 && Math.abs(st.dx) > 20));
    if (go) tabGo(host, st.track, dir, key, from, toward);
    else tabBack(st.track, from);
  };
  host.addEventListener('pointerup', end);
  host.addEventListener('pointercancel', end);
  // A swipe that ends on a row isn't a tap on it.
  host.addEventListener('click', (e) => { if (Date.now() - swipedAt < 400) { e.stopPropagation(); e.preventDefault(); } }, true);
}

/** The tabs get the page's color behind them once they stick to the top. */
function syncPageTabs(host) {
  const bar = host.querySelector('.atabs'), anchor = host.querySelector('.tab-anchor');
  if (!bar || !anchor) return;
  // Where they'd be without sticking (their anchor) against where they stick: measured so, the top bar sliding in or out doesn't matter.
  const top = parseFloat(getComputedStyle(bar).top) || 0;
  const sc = hostScroller(host);
  const from = sc === document.scrollingElement ? 0 : sc.getBoundingClientRect().top;
  bar.classList.toggle('stuck', anchor.getBoundingClientRect().top - from < top);
}

// ---- tidy up: videos, app sounds and clips out of the stats ----

let TIDY = null;
const TIDY_WHY = { app: 'named like an app', video: 'sounds like a video', videoTitle: 'video-style title', sameName: 'same name as the artist', clip: 'replayed seconds apart' };
const hideAttrs = (kind, artist, title) => `data-kind="${kind}" data-a="${esc(artist)}" data-ti="${esc(title || '')}"`;
const lastfmTrackUrl = (artist, title) => lastfmUrl(artist) + '/_/' + lfEnc(title);

function openTidy() {
  openPage('tidyPage');
  renderTidy();
  send({ t: 'tidy' });
}
window.onTidy = (t) => { TIDY = t; renderTidy(); };

/** The banner on the Stats tab while there are suggestions. */
function tidyBanner(t) {
  if (!t || !t.count) return '';
  const ex = (t.examples || []).map((x) => `“${esc(x)}”`).join(', ');
  const more = t.count > (t.examples || []).length ? ` and ${t.count - t.examples.length} more` : '';
  return `<button class="tidy-banner" data-tidy>${icon('clean')}<span><b>Some plays look like videos or apps</b>
    <small>${ex}${more}: ${plural(t.plays, 'play')} in your history. Tap to tidy up.</small></span></button>`;
}

function tidyRow(x, acts) {
  const why = (x.reasons || []).map((r) => `<span class="why">${esc(TIDY_WHY[r] || r)}</span>`).join('');
  return `<div class="r"><span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)}, ${plural(x.plays, 'play')}, last ${esc(dateLabel(x.last))}</small>${why ? `<span class="whys">${why}</span>` : ''}</span>
    <span class="acts">${acts}</span></div>`;
}

function renderTidy() {
  if (!isOpen('tidyPage')) return;
  const t = TIDY;
  const body = $('#tidyBody');
  if (!t) { body.innerHTML = '<div class="lin inline indet"><i></i></div>'; return; }
  if (t.empty) { body.innerHTML = `<div class="empty">${icon('clean', 'i big')}<p>Import your Last.fm history on the Stats tab first.</p></div>`; return; }
  const sg = t.suggestions || [];
  const hd = t.hidden || { artists: [], tracks: [] };
  let h = '<p class="body intro">Hidden plays stay out of your stats, stories and artist pages. Nothing changes on Last.fm, and you can show them again here anytime.</p>';
  h += '<div class="section-head"><h2>Look like videos or apps</h2></div>';
  if (sg.length) {
    const plays = sg.reduce((n, x) => n + x.plays, 0);
    h += `<p class="hint">Tracks without an album that carry an app's name or a video-style title, or that replay every few seconds like a clip on loop: ${plural(sg.length, 'track')}, ${plural(plays, 'play')}.</p>
      <button class="btn filled wide hideall" data-hide-all>${icon('eye-off')}Hide all ${sg.length}</button>
      <div class="rank tidy" id="tidySugg">${sg.map((x) => tidyRow(x, `<button class="btn text small" data-keep ${hideAttrs('track', x.artist, x.title)}>Keep</button><button class="btn tonal small" data-hide ${hideAttrs('track', x.artist, x.title)}>Hide</button>`)).join('')}</div>`;
  } else {
    h += '<p class="hint">Nothing suspicious left.</p>';
  }
  const na = t.noAlbum || [];
  if (na.length) {
    h += `<div class="section-head"><h2>Most played without an album</h2></div>
      <p class="hint">Probably music, but this is where videos and app sounds hide. Hide anything that isn't.</p>
      <div class="rank tidy">${na.map((x) => tidyRow(x, `<button class="btn tonal small" data-hide ${hideAttrs('track', x.artist, x.title)}>Hide</button>`)).join('')}</div>`;
  }
  h += `<div class="section-head"><h2>Hidden</h2>${t.hiddenPlays ? `<span class="sub">${plural(t.hiddenPlays, 'play')}</span>` : ''}</div>`;
  if (hd.artists.length || hd.tracks.length) {
    h += `<div class="rank tidy">${hd.artists.map((a) => `<div class="r"><span class="t"><b>${esc(a)}</b><small>Artist: all their tracks</small></span>
        <span class="acts"><button class="btn text small" data-unhide ${hideAttrs('artist', a)}>Show</button></span></div>`).join('')}
      ${hd.tracks.map((x) => `<div class="r"><span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)}</small></span>
        <span class="acts"><button class="icon-btn small" data-url="${esc(lastfmTrackUrl(x.artist, x.title))}" aria-label="Open on Last.fm">${icon('open')}</button>
        <button class="btn text small" data-unhide ${hideAttrs('track', x.artist, x.title)}>Show</button></span></div>`).join('')}</div>
      ${hd.tracks.length ? `<p class="hint">To delete them from Last.fm as well, open a track there (${icon('open', 'i inl')}) and delete its scrobbles from your library. Last.fm doesn't let apps delete scrobbles.</p>` : ''}`;
  } else {
    h += `<p class="hint">Nothing hidden yet. You can also hide an artist from their page (${icon('eye-off', 'i inl')} at the top), or a single track from the artist's top tracks.</p>`;
  }
  morph(body, h);
}

/** Hide, show again or keep one track or artist; the stats come back without (or with) it. */
function tidyAction(el) {
  const it = { kind: el.dataset.kind, artist: el.dataset.a, title: el.dataset.ti };
  const t = el.matches('[data-keep]') ? 'keep' : el.matches('[data-unhide]') ? 'unhide' : 'hide';
  send({ t, ...it });
  const row = el.closest('.r');
  if (row) row.classList.add('gone');
  if (t !== 'keep') {
    $('#statsBody').classList.add('stale');
    if (isOpen('artistPage') && ARTIST && ARTIST.name) send({ t: 'artist', name: ARTIST.name, range: statsRange, req: ARTIST.req });
  }
  const what = it.kind === 'artist' ? it.artist : `“${it.title}”`;
  toast(t === 'hide' ? `${what} is hidden from your stats` : t === 'unhide' ? `${what} is back in your stats` : `${what} stays in your stats`);
}

/** Hold a song or an artist anywhere for what you can do with it besides opening it. */
function openRowActions(el) {
  const song = el.dataset.track != null ? { artist: el.dataset.a || '', title: el.dataset.track } : null;
  const artist = song ? song.artist : el.dataset.artist;
  if (!artist) return;
  openSheet(`<div class="sheet-title">${esc(song ? song.title : artist)}</div>${song ? `<p class="body">${esc(artist)}</p>` : ''}
    <div class="sheet-actions col">
      ${song ? `<button class="btn tonal" data-hide ${hideAttrs('track', artist, song.title)}>${icon('eye-off')}Hide this song from your stats</button>` : ''}
      <button class="btn tonal" data-hide ${hideAttrs('artist', artist)}>${icon('eye-off')}${song ? `Hide everything by ${esc(artist)}` : 'Hide them from your stats'}</button>
      ${song ? `<button class="btn text" data-fixname>${icon('edit')}Fix its name in new scrobbles</button>` : ''}
    </div>
    <p class="hint">Hidden plays leave your stats, stories and top lists. Nothing changes on Last.fm, and Settings → Stats and listening → Tidy up brings them back.</p>`);
  $('#sheetContent').onclick = (ev) => {
    // Hiding itself is the same as anywhere else (tidyAction, as the click goes on up).
    if (ev.target.closest('[data-hide]')) { closeSheet(); return; }
    if (song && ev.target.closest('[data-fixname]')) openFixSheet('', { field: 'title', how: 'is', find: song.title, to: song.title, artist });
  };
}
const LONG_PRESS_MS = 480;
function watchLongPress() {
  let timer = 0, at = null, fired = 0;
  const cancel = () => { clearTimeout(timer); timer = 0; at = null; };
  document.addEventListener('pointerdown', (e) => {
    cancel();
    if (!e.isPrimary || (e.pointerType === 'mouse' && e.button !== 0)) return;
    const el = e.target.closest('[data-track], [data-artist]');
    if (!el || el.closest('#sheet, #story, svg, canvas')) return;
    at = { x: e.clientX, y: e.clientY, el };
    timer = setTimeout(() => {
      const a = at;
      cancel();
      if (!a || !a.el.isConnected) return;
      fired = Date.now();
      try { if (navigator.vibrate) navigator.vibrate(10); } catch (_) { /* not allowed */ }
      openRowActions(a.el);
    }, LONG_PRESS_MS);
  }, { passive: true });
  document.addEventListener('pointermove', (e) => { if (at && Math.hypot(e.clientX - at.x, e.clientY - at.y) > 8) cancel(); }, { passive: true });
  ['pointerup', 'pointercancel'].forEach((k) => document.addEventListener(k, () => { clearTimeout(timer); timer = 0; at = null; }, { passive: true }));
  window.addEventListener('scroll', cancel, { passive: true, capture: true });
  // The tap that ends a long press doesn't open the row as well, and Android's own menu stays away.
  document.addEventListener('click', (e) => { if (Date.now() - fired < 700) { fired = 0; e.stopPropagation(); e.preventDefault(); } }, true);
  document.addEventListener('contextmenu', (e) => { if (e.target.closest('[data-track], [data-artist]')) e.preventDefault(); });
}

function hideAllSuggestions() {
  const sg = (TIDY && TIDY.suggestions) || [];
  if (!sg.length) return;
  const items = sg.map((x) => ({ kind: 'track', artist: x.artist, title: x.title }));
  send({ t: 'hide', items });
  $$('#tidyBody .hideall, #tidySugg .r').forEach((r) => r.classList.add('gone'));
  $('#statsBody').classList.add('stale');
  toast(`${plural(items.length, 'track')} hidden from your stats`);
}

function rarelyViz(r) {
  if (!r || !r.items || !r.items.length) return '';
  const sub = r.unplayed ? `${r.unplayed} of ${plural(r.owned, 'record')} you own had no plays in this period` : 'Your records, least played first';
  return `<div class="viz"><h2>On your shelf, rarely played</h2><span class="sub">${esc(sub)}</span><div class="covers">${r.items.map((x) =>
    `<button class="rec" data-rec="${x.id}">${coverHtml(x.cover, x.title, x.artist)}<b>${esc(x.title)}</b><small>${esc(x.artist)}</small><small class="meta">${x.plays ? plural(x.plays, 'play') : x.last ? `last ${esc(dateLabel(x.last))}` : 'never played'}</small></button>`).join('')}</div></div>`;
}

function wishlistViz(w) {
  if (!w || !w.length) return '';
  return `<div class="viz"><h2>Wishlist ideas</h2><span class="sub">Albums you stream a lot but don't own on vinyl. Tap one to look on Discogs.</span><div class="covers">${w.map((x) =>
    `<button class="rec" data-url="${esc(x.search)}">${coverHtml(x.image, x.album, x.artist)}<b>${esc(x.album)}</b><small>${esc(x.artist)}</small><small class="meta">${plural(x.plays, 'play')}</small></button>`).join('')}</div></div>`;
}

/** How many rows each top list shows (10, 50 or 100), as you last chose for that list. */
const TOPN = (() => { try { return JSON.parse(localStorage.getItem('topN') || '{}'); } catch (_) { return {}; } })();
const topN = (k) => TOPN[k] || 10;
/** The buttons under a top list of [have] rows: one step longer, and back to 10. */
function topMore(k, have) {
  const n = topN(k), next = n < 50 ? 50 : 100;
  const longer = have > n ? `<button class="btn text small" data-topn="${k}:${next}">Show top ${Math.min(next, have)}</button>` : '';
  const shorter = n > 10 && have > 10 ? `<button class="btn text small" data-topn="${k}:10">Show top 10</button>` : '';
  return longer || shorter ? `<div class="btn-row top-more">${longer}${shorter}</div>` : '';
}
function setTopN(k, n) {
  TOPN[k] = n;
  try { localStorage.setItem('topN', JSON.stringify(TOPN)); } catch (_) { /* preview without storage */ }
  if (k === 'stats') renderStats();
  else if (k === 'artist') renderArtist();
  else if (k === 'genre') renderGenre();
}
function topViz(top) {
  if (!top) return '';
  const all = top[topKind] || [], list = all.slice(0, topN('stats'));
  const max = Math.max(1, ...list.map((x) => x.plays));
  const rows = list.map((x, i) => {
    const name = topKind === 'artists' ? x.name : x.title;
    const sub = topKind === 'artists' ? '' : x.artist;
    const open = topKind === 'tracks' ? trackAttrs(x.artist, x.title) : topKind === 'albums' ? `${albumAttrs(x.artist, x.title)} data-cover="${esc(x.cover)}"` : `data-artist="${esc(x.name)}"`;
    const pic = topKind === 'albums' ? coverHtml(x.cover, x.title, x.artist, 'tiny') : topKind === 'artists' ? coverHtml(x.photo, x.name, x.name, 'tiny round') : `<span class="n">${i + 1}</span>`;
    return `<button class="r ${topKind === 'tracks' ? '' : 'with-cover'}" ${open}>${pic}<span class="t"><b>${esc(name)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</span><span class="v">${num(x.plays)}</span><div class="bar"><i style="width:${(x.plays / max) * 100}%"></i></div></button>`;
  }).join('');
  return `<div class="viz"><h2>Top ${list.length > 10 ? list.length : 10}</h2><div class="seg small" id="topSeg">${['artists', 'albums', 'tracks'].map((k) =>
    `<button data-top="${k}" class="${k === topKind ? 'on' : ''}">${k.charAt(0).toUpperCase() + k.slice(1)}</button>`).join('')}</div>
    <div class="rank">${rows || '<p class="hint">Nothing in this period.</p>'}</div>${topMore('stats', all.length)}
    ${rows ? `<div class="btn-row">${plButton('top', 'Playlist of the top 40')}<button class="btn text" ${rankAttrs(topKind, statsRange)} data-rshown="1">${icon('view-list')}The whole list</button></div>` : ''}</div>`;
}

function numbersHtml(s) {
  const sm = s.summary, pv = sm.prev || {}, k = s.range.kind, run = s.range.running;
  return `<div class="tiles">
      ${tile('Plays', compact(sm.scrobbles), `${sm.perDay} a day`, deltaHtml(sm.scrobbles, pv.scrobbles, k, run), 'music', k === 'week' ? 'data-goblock="week"' : 'data-goblock="timeline"')}
      ${tile('Hours', `≈${compact(sm.hours)}`, 'at ~3.5 min a track', deltaHtml(sm.hours, pv.hours, k, run), 'schedule')}
      ${tile('Artists', compact(sm.artists), `${compact(sm.tracks)} different tracks`, deltaHtml(sm.artists, pv.artists, k, run), 'heart', `${rankAttrs('artists', s.range.id)} data-rshown="1"`)}
      ${tile('New artists', sm.historyStartsHere ? '–' : compact(sm.newArtists), sm.historyStartsHere ? 'your history starts here' : sm.newArtists ? 'first time ever' : 'only familiar names', sm.historyStartsHere ? '' : deltaHtml(sm.newArtists, pv.newArtists, k, run), 'explore', sm.newArtists ? 'data-goblock="newArtists"' : '')}
    </div>`;
}
/** Charts that draw change over the period need a few days of music first. */
const thin = (s) => s.summary.days < 4;

/**
 * Every block of the Stats tab: its name (in Arrange), the periods it is for (all but the day, unless said), and how it
 * draws itself full size from the stats [s] ('' when it has nothing to show). [classic]: how it looked up to 0.58, for
 * the Classic page (Settings → Look → Stats). What a block says as a card is in CARDS.
 */
const SPAN = ['week', 'month', 'year', '12m', 'all'];
const BLOCKS = {
  story: { name: 'The story', html: (s) => storyCardHtml(s) },
  numbers: { name: 'Numbers', html: numbersHtml },
  tidy: { name: 'Tidy-up note', html: (s) => tidyBanner(s.tidy) },
  highlights: { name: 'Highlights', kinds: ['day', ...SPAN], html: highlightsViz },
  week: { name: 'Your week', kinds: ['week'], html: (s) => weekViz(s) },
  record: {
    name: 'Your record',
    html: (s) => {
      if (!RECS.stats || RECS.stats.rec !== s.record) RECS.stats = { rec: s.record, spot: -1, label: periodName(s.range) };
      return recordViz(s.record, 'stats', 'Your record');
    },
  },
  timeline: { name: 'Listening over time', html: (s) => (s.range.kind === 'week' ? '' : timelineViz(s.timeline)) },
  river: { name: 'The river', html: (s) => (thin(s) ? '' : riverViz(s.river)) },
  days: { name: 'Your days', kinds: ['week'], html: (s) => daysViz(s) },
  clock: { name: 'When you listen', html: (s) => (s.range.kind === 'week' ? '' : clockViz(s.clock, 'stats')) },
  streaks: { name: 'Days and streaks', html: calendarViz, classic: (s) => streakViz(s.streaks, s) },
  who: {
    name: 'Who you played when',
    html: (s) => (thin(s) ? '' : whoViz(s)),
    classic: (s) => (thin(s) ? '' : (s.range.kind === 'week' ? '' : habitsViz(s.habits)) + pulseViz(s.pulse)),
  },
  newArtists: { name: 'New artists', html: newArtistsView, classic: (s) => newArtistsViz(s.newArtists) },
  repeat: { name: 'On repeat', html: onRepeatViz, classic: (s) => obsessionsViz(s.obsessions, s.loops) },
  genres: { name: 'Genre drift', html: (s) => (thin(s) ? '' : genreViz(s.genres)) },
  decades: { name: 'Decades', html: decadesView, classic: (s) => decadesViz(s.decades) },
  rarely: { name: 'Rarely played records', html: (s) => rarelyViz(s.rarely) },
  wishlist: { name: 'Wishlist ideas', html: (s) => wishlistViz(s.wishlist) },
  top: { name: 'Top lists', kinds: ['day', ...SPAN], html: (s) => topViz(s.top) },
  taste: { name: 'Your taste', html: tasteHtml },
  today: { name: 'Today so far', kinds: ['day'], html: (s) => todayViz(s) },
  dayline: { name: 'Your day', kinds: ['day'], html: (s) => daylineViz(s) },
  firstLatest: { name: 'First and latest', kinds: ['day'], html: (s) => firstLatestViz(s) },
  repeats: { name: 'On repeat', kinds: ['day'], html: (s) => repeatsViz(s) },
  newToday: { name: 'New today', kinds: ['day'], html: (s) => newTodayViz(s) },
  onThisDay: { name: 'On this day', kinds: ['day'], html: (s) => onThisDayViz(s) },
  dayFacts: { name: 'Streak and vinyl', kinds: ['day'], html: (s) => dayFactsViz(s) },
};
const DEFAULT_ORDER = {
  day: ['today', 'dayline', 'firstLatest', 'repeats', 'highlights', 'newToday', 'onThisDay', 'dayFacts', 'top'],
  span: ['story', 'numbers', 'tidy', 'highlights', 'week', 'record', 'timeline', 'river', 'days', 'clock', 'streaks', 'who', 'newArtists', 'repeat', 'genres', 'decades', 'rarely', 'wishlist', 'top', 'taste'],
};
/** Blocks up to 0.58 that live on in today's (habits and phases in Who you played when, obsessions in On repeat). */
const OLD_BLOCKS = { pulse: 'who', habits: 'who', obsessions: 'repeat' };
const statsGroup = (s) => (s && s.range && s.range.kind === 'day' ? 'day' : 'span');
/** Stats as cards, full size or the classic page (Settings → Look); before that choice, as Explore was set. */
function statsView() {
  try {
    const v = localStorage.getItem('statsView');
    if (v === 'cards' || v === 'full' || v === 'long') return v;
    const was = localStorage.getItem('mainLayout') === 'long' ? 'long' : 'cards';
    localStorage.setItem('statsView', was);
    return was;
  } catch (_) {
    return 'cards';
  }
}
/** How [id] draws itself in [view]: the classic page keeps the blocks' old forms. */
const blockHtml = (id, s, view) => ((view === 'long' && BLOCKS[id].classic) || BLOCKS[id].html)(s) || '';
/** The page's order and hidden blocks, as arranged (kept with the other UI settings, so backups carry it). */
function statsLayout(group) {
  let saved = {};
  try { saved = JSON.parse(localStorage.getItem('statsLayout') || '{}')[group] || {}; } catch (_) { /* preview without storage */ }
  const def = DEFAULT_ORDER[group];
  const renamed = (v) => (Array.isArray(v) ? v.map((k) => OLD_BLOCKS[k] || k) : v);
  // Whatever is stored (a hand-edited or damaged backup too): known blocks, each once.
  const known = (v) => (Array.isArray(v) ? [...new Set(renamed(v))].filter((k) => def.includes(k)) : []);
  const order = known(saved.order);
  // Blocks added in a later version go after the block they follow by default.
  def.forEach((k, i) => {
    if (order.includes(k)) return;
    const before = def.slice(0, i).reverse().find((x) => order.includes(x));
    order.splice(before ? order.indexOf(before) + 1 : 0, 0, k);
  });
  // A merged block stays hidden only if all of the blocks it came from were.
  const hid = Array.isArray(saved.hidden) ? saved.hidden : [];
  const hidden = known(hid.filter((k) => !OLD_BLOCKS[k] || Object.keys(OLD_BLOCKS).filter((o) => OLD_BLOCKS[o] === OLD_BLOCKS[k]).every((o) => hid.includes(o))));
  // Cards as chosen; before any choice, the blocks that start as cards.
  return { order, hidden: new Set(hidden), small: new Set(Array.isArray(saved.small) ? known(saved.small) : SMALL_DEFAULT.filter((k) => def.includes(k))) };
}
function saveLayout(group, order, hidden, small) {
  // A damaged entry is started over, or nothing could ever be saved again.
  let all = {};
  try {
    const v = JSON.parse(localStorage.getItem('statsLayout') || '{}');
    if (v && typeof v === 'object' && !Array.isArray(v)) all = v;
  } catch (_) { /* damaged, or a preview without storage */ }
  if (order) all[group] = { order, hidden: [...hidden], ...(small ? { small: [...small] } : {}) };
  else delete all[group];
  try { localStorage.setItem('statsLayout', JSON.stringify(all)); } catch (_) { /* preview without storage */ }
}

function renderStats() {
  const s = STATS;
  const body = $('#statsBody');
  renderPeriod(s);
  // Last.fm asks for this credit, linked back to it (here: your profile).
  const lu = (ST.lastfm || {}).user;
  $('#statsCredit').hidden = !s || s.empty;
  $('#statsCredit button').dataset.url = lu ? `https://www.last.fm/user/${encodeURIComponent(lu)}` : 'https://www.last.fm/';
  if (!s || s.empty) { body.innerHTML = ''; return; }
  statsRange = s.range.id;
  const k = s.range.kind;
  const arrange = `<button class="btn text arrange-btn" id="arrangeBtn">${icon('tune')}Arrange this page</button>`;
  if (!s.summary.scrobbles) {
    const other = k === 'day' ? onThisDayViz(s) : '';
    body.innerHTML = `<div class="empty">${icon('album', 'i big')}<p>${k === 'day' && s.range.running ? 'Nothing played yet today.' : `No plays in ${esc(periodName(s.range))}.`}</p></div>${other}`;
    return;
  }
  const lay = statsLayout(statsGroup(s));
  const ids = lay.order.filter((id) => !lay.hidden.has(id) && (BLOCKS[id].kinds || SPAN).includes(k));
  const view = k === 'day' ? 'full' : statsView();
  // Each block marked by its id, so a highlight or a number can lead to it.
  const mark = (id, h) => h.replace(/^\s*<([a-z0-9]+)/i, (m, t) => `<${t} data-blk="${id}"`);
  if (view !== 'cards') {
    morph(body, ids.map((id) => mark(id, blockHtml(id, s, view))).join('') + arrange);
  } else {
    // Cards one under the other, in the order Arrange gives them; a full-size block between them starts a new group.
    let h = '', run = [];
    const flush = () => { if (run.length) h += `<div class="scards">${run.join('')}</div>`; run = []; };
    for (const id of ids) {
      if (lay.small.has(id) && !NEVER_CARD.includes(id)) {
        const c = statCard(id, s);
        if (c) run.push(c);
        continue;
      }
      const x = blockHtml(id, s, view);
      if (!x) continue;
      flush();
      h += mark(id, x);
    }
    flush();
    morph(body, h + arrange);
  }
  drawRecords();
  afterStats();
  renderBlock();
}
/** After the page is drawn: the genre map fitted to its boxes, you vs everyone's line, and a block asked for. */
let statsGoTo = '';
function afterStats() {
  const gm = $('#statsBody .genre-map');
  gmSizes.disconnect();
  if (gm) { gmSizes.observe(gm); requestAnimationFrame(fitGenreMap); }
  if ($('#vsTileLine')) { renderVsCard(); if (!VS) send({ t: 'vs' }); }
  if (statsGoTo && $(`#statsBody [data-blk="${statsGoTo}"]`)) { const id = statsGoTo; statsGoTo = ''; requestAnimationFrame(() => goBlock(id)); }
}
/** To block [id]: scrolled to on the page, or opened on its own page when it's a card. */
function goBlock(id) {
  const el = $(`#statsBody [data-blk="${id}"]`);
  if (el) {
    if (isOpen('blockPage')) closePage();
    el.scrollIntoView({ block: 'start', behavior: 'smooth' });
    return;
  }
  if (BLOCKS[id] && STATS && (BLOCKS[id].kinds || SPAN).includes(STATS.range.kind)) openBlock(id);
}

// ---- Stats in cards: a block as a sentence and a small picture, opening full size on its own page ----

/** Blocks that start as cards; Arrange makes any block a card or full size, except these. */
const SMALL_DEFAULT = ['streaks', 'who', 'newArtists', 'repeat', 'decades'];
const NEVER_CARD = ['story', 'numbers', 'tidy', 'highlights', 'record', 'taste', 'today'];
/**
 * What a block says as a card: its icon, a sentence ([big], HTML), a line under it and a small picture that reads at
 * this size; null when it has nothing to show. Blocks without their own card get a plain one.
 */
const CARDS = {
  streaks: (s) => {
    const st = s.streaks || {};
    if (!st.longest) return null;
    const now = s.range.running && st.current ? ` Today is day ${st.current}.` : '';
    return { ic: 'fire', big: `${plural(st.longest, 'day')} in a row`, line: `${st.longest > 1 ? `${esc(shortDate(st.longestFrom))} to ${esc(shortDate(st.longestTo))}.` : esc(shortDate(st.longestFrom))}${now}`, pic: dayStrip(s) };
  },
  who: (s) => {
    const W = whoRows(s, 8);
    if (!W || thin(s)) return null;
    const top3 = W.rows.slice(0, 3);
    const max = Math.max(1, ...top3.flatMap((r) => r.counts));
    const steady = W.rows.filter((r) => r.kind === 'steady').sort((a, b) => b.present - a.present)[0];
    const other = W.rows.find((r) => r.kind === 'wave') || W.rows.find((r) => r.kind === 'new');
    const big = steady && other ? `${esc(steady.name)} steady, ${esc(other.name)} ${other.kind === 'wave' ? 'in a wave' : 'new'}`
      : steady ? `${esc(steady.name)} most ${W.unitWord}` : other ? `${esc(other.name)} ${other.kind === 'wave' ? 'in a wave' : 'new to you'}` : `${esc(W.rows[0].name)} on top`;
    const more = W.rows.length - top3.length;
    return {
      ic: 'people', big, line: `${top3.map((r) => esc(r.name)).join(', ')}${more > 0 ? ` and ${more} more` : ''}, in the order they peaked`,
      pic: `<div class="sc-ridges">${top3.map((r) => `<span style="color:${r.color}">${esc(r.name)}</span>${ridgeSvg(r.counts, r.color, max, 22)}`).join('')}</div>`,
    };
  },
  newArtists: (s) => {
    const na = s.newArtists || {}, found = na.found || [];
    if (s.summary.historyStartsHere && !na.count) return { ic: 'explore', big: 'Everyone is new', line: `Your history starts ${esc(inPeriod(s.range))}` };
    if (!na.count) return { ic: 'explore', big: 'No new artists', line: 'You stuck with the ones you knew' };
    const f = found[0];
    return {
      ic: 'explore', big: `${plural(na.count, 'new artist')}${na.kept ? `, ${num(na.kept)} kept` : ''}`,
      line: f ? `${esc(f.name)} played most${f.from ? `, found right after ${esc(f.from)}` : ''}` : '',
      pic: found.length ? `<div class="sc-faces">${found.slice(0, 7).map((x) => artistFace(x)).join('')}</div>` : '',
    };
  },
  repeat: (s) => {
    const list = repeatList(s);
    if (!list.length) return null;
    const x = list[0];
    return {
      ic: 'repeat', big: `${esc(x.track)}, ${Math.max(x.week, x.run)} times`, line: `${esc(x.artist)}, ${esc(repeatWords(x))}`,
      pic: `<div class="sc-covers">${list.slice(0, 5).map((y) => coverHtml(y.cover, y.album || y.track, y.artist, 'tiny')).join('')}${list.length > 1 ? `<small>${list.length > 5 ? `and ${list.length - 5} more` : `${plural(list.length, 'song')} in all`}</small>` : ''}</div>`,
    };
  },
  decades: (s) => {
    const x = s.decades || {}, ds = x.decades || [];
    if (ds.length < 2) return null;
    const known = Math.max(1, x.known);
    const [a, b] = [...ds].sort((p, q) => q.plays - p.plays);
    return { ic: 'history', big: `Mostly the ${a.decade}s${b && b.plays / known >= 0.15 ? ` and ${b.decade}s` : ''}`, line: `${pct(a.plays / known)}${b ? ` and ${pct(b.plays / known)}` : ''} of the plays with a known year`, pic: decadeBar(ds, known, false) };
  },
  genres: (s) => {
    const g = s.genres || {}, periods = g.periods || [];
    if (thin(s) || !periods.length || !g.names || g.names.length < 2) return null;
    const n = g.names.length, totals = new Array(n).fill(0);
    periods.forEach((p) => p.shares.forEach((v, k) => { totals[k] += v * p.total; }));
    const all = totals.reduce((p, q) => p + q, 0);
    if (!all) return null;
    const order = totals.map((v, k) => [v, k]).filter(([v, k]) => v > 0 && k < n - 1).sort((p, q) => q[0] - p[0]);
    if (!order.length) return null;
    const color = (k) => (k < n - 1 ? GENRE_COLORS[k] : 'var(--md-outline-variant)');
    return {
      ic: 'chart', big: `Mostly ${esc(g.names[order[0][1]])}`, line: order.slice(0, 3).map(([v, k]) => `${esc(g.names[k])} ${pct(v / all)}`).join(', '),
      pic: `<div class="sc-bar">${totals.map((v, k) => (v > 0 ? `<i style="flex:${v} 1 0;background:${color(k)}"></i>` : '')).join('')}</div>`,
    };
  },
  clock: (s) => {
    const c = s.clock || [];
    if (s.range.kind === 'week' || !c.length) return null;
    let peak = 0;
    c.forEach((v, i) => { if (v > c[peak]) peak = i; });
    if (!c[peak]) return null;
    const hours = Array.from({ length: 24 }, (_, h) => c.reduce((n, v, i) => n + (i % 24 === h ? v : 0), 0));
    return { ic: 'schedule', big: `${DOW_LONG[Math.floor(peak / 24)]}, ${pad(peak % 24)}:00`, line: `Your busiest hour of the week, ${plural(c[peak], 'play')}`, pic: miniCols(hours) };
  },
  timeline: (s) => {
    const t = s.timeline || {}, counts = t.counts || [];
    if (s.range.kind === 'week' || counts.length < 2) return null;
    let best = 0;
    counts.forEach((v, i) => { if (v > counts[best]) best = i; });
    return { ic: 'chart', big: `Most in ${esc(periodLabel(t.starts[best], t.unit))}`, line: `${plural(counts[best], 'play')}, ${t.avg} on average per ${t.unit}`, pic: miniCols(counts) };
  },
  rarely: (s) => {
    const r = s.rarely || {}, items = r.items || [];
    if (!items.length) return null;
    return { ic: 'album', big: r.unplayed ? `${r.unplayed} of ${plural(r.owned, 'record')} unplayed` : 'Your records, least played first', line: `${esc(items[0].title)} by ${esc(items[0].artist)} least of all`, pic: `<div class="sc-covers">${items.slice(0, 6).map((x) => coverHtml(x.cover, x.title, x.artist, 'tiny')).join('')}</div>` };
  },
  wishlist: (s) => {
    const w = s.wishlist || [];
    if (!w.length) return null;
    return { ic: 'heart', big: `${plural(w.length, 'album')} you stream but don't own`, line: `${esc(w[0].album)} by ${esc(w[0].artist)} most, ${plural(w[0].plays, 'play')}`, pic: `<div class="sc-covers">${w.slice(0, 6).map((x) => coverHtml(x.image, x.album, x.artist, 'tiny')).join('')}</div>` };
  },
  top: (s) => {
    const a = ((s.top || {}).artists || []).slice(0, 6);
    if (!a.length) return null;
    return { ic: 'star', big: `${esc(a[0].name)} on top`, line: `${plural(a[0].plays, 'play')}${a[2] ? `, then ${esc(a[1].name)} and ${esc(a[2].name)}` : ''}`, pic: `<div class="sc-faces">${a.map((x) => coverHtml(x.photo, x.name, x.name, 'tiny round')).join('')}</div>` };
  },
};
function statCard(id, s) {
  const c = CARDS[id] ? CARDS[id](s) : BLOCKS[id].html(s) ? { ic: 'chart', big: esc(BLOCKS[id].name), line: 'Opens full size' } : null;
  if (!c) return '';
  return `<button class="scard" data-block="${id}" aria-label="${esc(BLOCKS[id].name)}, open"><span class="scard-k">${icon(c.ic, 'i small')}${esc(BLOCKS[id].name)}</span><span class="scard-open" aria-hidden="true">${icon('chevron-right')}</span>
    <b class="scard-s">${c.big}</b>${c.line ? `<small class="scard-l">${c.line}</small>` : ''}${c.pic ? `<span class="scard-pic">${c.pic}</span>` : ''}</button>`;
}
let BLOCK = null;
function openBlock(id) {
  BLOCK = id;
  openPage('blockPage');
  renderBlock();
}
function renderBlock() {
  if (!BLOCK || !isOpen('blockPage') || !STATS || STATS.empty) return;
  $('#blockTitle').textContent = periodName(STATS.range);
  const h = BLOCKS[BLOCK] && (BLOCKS[BLOCK].kinds || SPAN).includes(STATS.range.kind) ? BLOCKS[BLOCK].html(STATS) : '';
  morph($('#blockBody'), h || '<div class="empty small"><p>Nothing to show for this period.</p></div>');
  drawRecords();
}

// ---- the period day by day, who you played when, new artists, songs on repeat, decades ----

/** "Oct 4" */
const shortDate = (ms) => new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'short' });
/** The start of the day [i] days after [ms] (calendar days, so a change of the clocks doesn't shift it). */
const dayAfter = (ms, i) => { const d = new Date(ms); d.setDate(d.getDate() + i); d.setHours(0, 0, 0, 0); return +d; };
/** Calendar days from [a] to [b]. */
const daysBetween = (a, b) => { const x = new Date(a), y = new Date(b); return Math.round((Date.UTC(y.getFullYear(), y.getMonth(), y.getDate()) - Date.UTC(x.getFullYear(), x.getMonth(), x.getDate())) / 864e5); };
/** "this month", "in October 2026", "in all your history" */
const inPeriod = (r) => (r.kind === 'all' ? 'in all your history' : r.running ? `this ${periodWord(r.kind)}` : `in ${periodName(r)}`);
/** How dark a day is drawn for [v] plays of at most [max]. */
const shade = (v, max) => (v ? Math.round(16 + 84 * Math.sqrt(v / max)) : 0);
const MONTH_LETTER = ['J', 'F', 'M', 'A', 'M', 'J', 'J', 'A', 'S', 'O', 'N', 'D'];

/**
 * The period as a calendar: darker days had more plays, the longest run underlined, marks for the days a new artist
 * or a song on repeat came in. A year is twelve small months; all time is a month per box. Tap a day or month for it.
 */
function calendarViz(s) {
  const c = s.calendar || {}, st = s.streaks || {};
  if (!(c.counts || []).length) return '';
  const k = s.range.kind;
  const max = Math.max(1, ...c.counts);
  const run = st.longest && c.unit === 'day' ? [daysBetween(c.start, st.longestFrom), daysBetween(c.start, st.longestFrom) + st.longest - 1] : [-1, -2];
  const big = c.unit === 'day' && c.days <= 31;
  const grid = c.unit === 'month' ? monthsGrid(c, max) : big ? daysGrid(c, s, max, run) : yearGrid(c, s, max, run);
  const title = k === 'month' ? `${new Date(c.start).toLocaleDateString([], { month: 'long' })}, day by day` : k === 'all' ? 'Month by month' : k === 'year' ? `${periodName(s.range)}, day by day` : 'Day by day';
  const sub = c.unit === 'month' ? 'Darker months had more plays. Tap a month for it.' : `Darker days had more plays${big ? ', and the line under them is your longest run' : ', the ringed ones your longest run'}. Tap a day for it.`;
  const nd = (c.newDays || []).length, rd = (c.repeatDays || []).length;
  const key = big && st.longest > 1 ? `<div class="cal-key"><span><i class="ln"></i>longest run</span>${nd ? '<span><em class="mn"></em>a new artist</span>' : ''}${rd ? '<span><em class="mr"></em>a song on repeat</span>' : ''}</div>` : '';
  const cur = s.range.running && st.current;
  // All time spans years: its dates say which.
  const dt = (ms) => esc(k === 'all' ? dateLabel(ms) : shortDate(ms));
  const words = st.longest ? `<b>${plural(st.longest, 'day')} in a row</b>${st.longest > 1 ? `, ${dt(st.longestFrom)} to ${dt(st.longestTo)}` : `, ${dt(st.longestFrom)}`}.${cur ? ` Today is day ${st.current}${st.currentFrom < s.range.from * 1000 ? `, a run that began ${dt(st.currentFrom)}` : ''}.` : ''}` : '';
  const artists = (st.artists || []).length ? `<div class="cal-artists"><small>Most days in a row with one artist</small><div class="rank">${st.artists.map((a) =>
    `<button class="r with-cover" data-artist="${esc(a.artist)}">${coverHtml(artistPhotoOf(s, a.artist), a.artist, a.artist, 'tiny round')}<span class="t"><b>${esc(a.artist)}</b><small>${dt(a.from)} to ${dt(a.to)}</small></span><span class="v">${plural(a.days, 'day')}</span></button>`).join('')}</div></div>` : '';
  return `<div class="viz cal-viz"><h2>${esc(title)}</h2><span class="sub">${sub}</span>${grid}${key}${words ? `<div class="readout">${words}</div>` : ''}${artists}</div>`;
}
/** A week or a month: a row per week, Monday first, each day with its date. */
function daysGrid(c, s, max, run) {
  const lead = (new Date(c.start).getDay() + 6) % 7;
  const nd = new Set(c.newDays || []), rd = new Set(c.repeatDays || []);
  let h = DOW_LETTER.map((d) => `<b class="wd">${d}</b>`).join('');
  for (let i = 0; i < lead; i++) h += '<i class="blank"></i>';
  for (let d = 0; d < c.days; d++) {
    const ms = dayAfter(c.start, d), v = c.counts[d], col = (lead + d) % 7;
    const inRun = d >= run[0] && d <= run[1];
    const cls = ['cd', inRun ? 'run' : '', inRun && (d === run[0] || col === 0) ? 'rs' : '', inRun && (d === run[1] || col === 6) ? 're' : '', s.range.running && d === c.counts.length - 1 ? 'today' : ''].filter(Boolean).join(' ');
    const n = new Date(ms).getDate();
    if (v == null) { h += `<i class="cd ahead"><span>${n}</span></i>`; continue; }
    h += `<button class="${cls}${shade(v, max) > 58 ? ' dk' : ''}" data-day="${periodId('day', ms)}" style="--v:${shade(v, max)}%" aria-label="${esc(dateLabel(ms))}, ${plural(v, 'play')}"><span>${n}</span>${nd.has(d) ? '<em class="mn"></em>' : ''}${rd.has(d) ? '<em class="mr"></em>' : ''}</button>`;
  }
  return `<div class="cal">${h}</div>`;
}
/** A year: twelve small months, each opening on a tap of its name. */
function yearGrid(c, s, max, run) {
  const months = [];
  for (let d = 0; d < c.days; d++) {
    const ms = dayAfter(c.start, d), dt = new Date(ms);
    const key = dt.getFullYear() * 12 + dt.getMonth();
    if (!months.length || months[months.length - 1].key !== key) months.push({ key, ms, cells: '', lead: (dt.getDay() + 6) % 7 });
    const v = c.counts[d], m = months[months.length - 1];
    m.cells += v == null ? '<i class="ahead"></i>' : `<button class="${d >= run[0] && d <= run[1] ? 'run' : ''}" data-day="${periodId('day', ms)}" style="--v:${shade(v, max)}%" aria-label="${esc(dateLabel(ms))}, ${plural(v, 'play')}"></button>`;
  }
  return `<div class="cal-year">${months.map((m) => `<div class="cal-m"><button class="cal-mh" data-day="${periodId('month', m.ms)}">${esc(new Date(m.ms).toLocaleDateString([], { month: 'short' }))}</button>
    <div class="cal cal-mini">${'<i class="blank"></i>'.repeat(m.lead)}${m.cells}</div></div>`).join('')}</div>`;
}
/** All time: a row per year, a box per month. */
function monthsGrid(c, max) {
  const first = new Date(c.start);
  const years = [];
  c.counts.forEach((v, i) => {
    const dt = new Date(first.getFullYear(), first.getMonth() + i, 1);
    if (!years.length || years[years.length - 1].y !== dt.getFullYear()) years.push({ y: dt.getFullYear(), cells: '<i class="blank"></i>'.repeat(dt.getMonth()) });
    years[years.length - 1].cells += `<button data-day="${periodId('month', +dt)}" style="--v:${shade(v, max)}%" aria-label="${esc(periodLabel(+dt, 'month'))}, ${plural(v, 'play')}"></button>`;
  });
  return `<div class="cal-all"><span></span>${MONTH_LETTER.map((m) => `<b class="wd">${m}</b>`).join('')}${years.map((y) => `<button class="cal-yh" data-day="y${y.y}">${y.y}</button>${y.cells}`).join('')}</div>`;
}
/** The period's days as one strip (a year or all time by month), the longest run underlined, for a card. */
function dayStrip(s) {
  const c = s.calendar || {}, st = s.streaks || {};
  if (!(c.counts || []).length) return '';
  let vals = c.counts.slice(), run = [-1, -2];
  if (c.unit === 'day' && c.days <= 31) {
    vals = Array.from({ length: c.days }, (_, d) => c.counts[d]);
    if (st.longest) { const r0 = daysBetween(c.start, st.longestFrom); run = [r0, r0 + st.longest - 1]; }
  } else if (c.unit === 'day') {
    // A year: plays per month, the months still to come empty.
    const m = new Map();
    for (let d = 0; d < c.days; d++) {
      const dt = new Date(dayAfter(c.start, d)), key = dt.getFullYear() * 12 + dt.getMonth();
      if (!m.has(key)) m.set(key, null);
      if (c.counts[d] != null) m.set(key, (m.get(key) || 0) + c.counts[d]);
    }
    vals = [...m.values()];
  }
  const max = Math.max(1, ...vals.filter((v) => v != null));
  return `<span class="sc-days">${vals.map((v, i) => `<i class="${v == null ? 'ahead' : ''}${i >= run[0] && i <= run[1] ? ' run' : ''}" style="--v:${v == null ? 0 : shade(v, max)}%"></i>`).join('')}</span>`;
}
/** Small columns for a card: plays per hour, day or month. */
const miniCols = (vals) => { const max = Math.max(1, ...vals); return `<span class="sc-cols">${vals.map((v) => `<i style="height:${Math.max(v ? 6 : 2, (v / max) * 100).toFixed(1)}%"></i>`).join('')}</span>`; };
/** An artist's photo, where the stats have one. */
const artistPhotoOf = (s, name) => {
  const a = ((s.top || {}).artists || []).find((x) => x.name === name) || ((s.habits || {}).rows || []).find((x) => x.name === name);
  return (a && a.photo) || '';
};

/** A ridge of plays per bin, on a scale shared with its neighbors ([max], square-rooted so small ones still show). */
function ridgeSvg(counts, color, max, h = 30) {
  const w = 200, B = counts.length;
  if (B < 2) return '';
  const sm = soften(counts, B > 24 ? 0.9 : 0.5);
  const pts = sm.map((v, i) => [(i / (B - 1)) * w, Math.min(h, h - 1 - Math.sqrt(Math.max(0, v) / max) * (h - 3))]);
  const line = `M${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}${smoothThrough(pts)}`;
  return `<svg class="ridge" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-hidden="true"><path d="${line} L${w},${h} L0,${h}Z" fill="${color}" opacity=".3"/><path d="${line}" fill="none" stroke="${color}" stroke-width="1.6" vector-effect="non-scaling-stroke"/></svg>`;
}
/**
 * The period's top artists (by plays, at most [n]) with how they were played: new to you (first ever play in the
 * period), steady (most of the period's bins with music), a wave (half their plays within a quarter of the period)
 * or now and then. From the habits' bins.
 */
function whoRows(s, n) {
  const hb = s.habits || {}, rows = hb.rows || [], B = hb.starts ? hb.starts.length : 0;
  if (rows.length < 2 || B < 4) return null;
  const w = Math.max(1, Math.round(B / 4));
  const unitWord = { day: 'days', week: 'weeks', month: 'months', quarter: 'quarters', year: 'years' }[hb.unit] || 'weeks';
  const unitDays = { day: 1, week: 7, month: 30.4, quarter: 91, year: 365 }[hb.unit] || 7;
  const spanWords = ((d) => (d <= 10 ? 'a week' : d <= 45 ? 'a month' : d <= 120 ? 'three months' : d <= 240 ? 'half a year' : 'a year'))(w * unitDays);
  const fresh = (f) => f >= s.range.from * 1000 && f >= (s.range.historyStart + 14 * 86400) * 1000;
  const out = [...rows].sort((a, b) => b.plays - a.plays).slice(0, n).map((r) => {
    let best = 0, at = 0;
    for (let i = 0; i + w <= B; i++) {
      let t = 0;
      for (let j = i; j < i + w; j++) t += r.counts[j];
      if (t > best) { best = t; at = i; }
    }
    const sm = soften(r.counts, B > 24 ? 0.9 : 0.5);
    const kind = r.first && fresh(r.first) ? 'new' : r.present >= 0.6 * hb.active ? 'steady' : best / Math.max(1, r.plays) >= 0.5 && r.plays >= 5 ? 'wave' : 'now';
    const mid = hb.starts[Math.min(B - 1, at + Math.floor(w / 2))];
    const when = hb.unit === 'day' ? shortDate(mid) : periodLabel(mid, hb.unit === 'week' ? 'month' : hb.unit, hb.unit !== 'month');
    const word = kind === 'new' ? `new, ${shortDate(r.first)}` : kind === 'steady' ? `steady, ${r.present} ${unitWord}` : kind === 'wave' ? `a wave, ${when}` : 'now and then';
    return { ...r, peak: sm.indexOf(Math.max(...sm)), kind, word, color: r.ink >= 0 ? INKS[r.ink] : 'var(--md-outline)' };
  });
  return { hb, B, unitWord, spanWords, rows: out };
}
/** Who you played when: a row per top artist in the order they peaked, with how you played them and their curve. */
function whoViz(s) {
  const W = whoRows(s, 10);
  if (!W) return '';
  const max = Math.max(1, ...W.rows.flatMap((r) => r.counts));
  const steady = W.rows.filter((r) => r.kind === 'steady').sort((a, b) => b.present - a.present)[0];
  const wave = W.rows.find((r) => r.kind === 'wave'), fresh = W.rows.find((r) => r.kind === 'new');
  const words = [steady ? `<b>${esc(steady.name)}</b> was the steadiest: ${steady.present} of the ${W.hb.active} ${W.unitWord} with music.` : '',
    wave ? `<b>${esc(wave.name)}</b> came in a wave.` : '', fresh ? `<b>${esc(fresh.name)}</b> is new to you.` : ''].filter(Boolean).join(' ');
  const rows = [...W.rows].sort((a, b) => a.peak - b.peak).map((r) => `<button class="who-r" data-artist="${esc(r.name)}">${coverHtml(r.photo, r.name, r.name, 'tiny round')}<span class="t"><b>${esc(r.name)}</b><small class="k-${r.kind}">${esc(r.word)}</small></span>${ridgeSvg(r.counts, r.color, max)}</button>`).join('');
  return `<div class="viz who"><h2>Who you played when</h2><span class="sub">Your top artists in the order they peaked. Steady means most ${W.unitWord}, a wave means half their plays within ${W.spanWords}.</span>
    <div class="who-ax"><span>${esc(periodLabel(W.hb.starts[0], W.hb.unit, true))}</span><span>${esc(periodLabel(W.hb.starts[W.B - 1], W.hb.unit, true))}</span></div>${rows}
    ${words ? `<div class="readout">${words}</div>` : ''}</div>`;
}
/** The period's new artists with their faces and what you played right before, and the way to more like them. */
function newArtistsView(s) {
  const na = s.newArtists || {}, found = na.found || [];
  if (s.summary.historyStartsHere && !na.count) return `<div class="viz"><h2>New artists</h2><span class="sub">Your history starts ${esc(inPeriod(s.range))}, so every artist is new here.</span></div>`;
  if (!na.count) return `<div class="viz"><h2>New artists</h2><span class="sub">No first listens ${esc(inPeriod(s.range))}: you stuck with the artists you knew.</span></div>`;
  const faces = found.slice(0, 12).map((f) => `<button class="na-find" data-artist="${esc(f.name)}">${artistFace(f)}<b>${esc(f.name)}</b><small>${esc(shortDate(f.first))}</small>${f.from ? `<small>after ${esc(f.from)}</small>` : ''}</button>`).join('');
  const like = discoverOn() && found.length ? `<button class="btn tonal" data-likethem>${icon('explore')}More like them</button>` : '';
  const all = found.length > 1 ? `<button class="btn text" data-newlist>${icon('view-list')}All ${num(na.count)}</button>` : '';
  return `<div class="viz"><h2>New artists</h2><span class="sub">${plural(na.count, 'artist')} you heard for the first time${na.kept ? `, ${num(na.kept)} you kept playing` : ''}. Under each, the day you found them and what played right before.</span>
    ${faces ? `<div class="na-row">${faces}</div>` : ''}${like || all ? `<div class="btn-row">${like}${all}</div>` : ''}</div>`;
}
/** Songs on repeat, one entry per song: most plays in one week and the longest run back to back. */
function repeatList(s) {
  const m = new Map();
  (s.obsessions || []).forEach((o) => m.set(`${o.artist}\u0000${o.track}`, { track: o.track, artist: o.artist, album: o.album, cover: o.cover, week: o.count, at: o.week, run: 0 }));
  (s.loops || []).forEach((l) => {
    const k = `${l.artist}\u0000${l.track}`;
    const x = m.get(k) || { track: l.track, artist: l.artist, album: l.album, cover: l.cover, week: 0, at: l.at };
    m.set(k, { ...x, run: l.run });
  });
  return [...m.values()].sort((a, b) => Math.max(b.week, b.run) - Math.max(a.week, a.run));
}
const repeatWords = (x) => [x.week ? `${x.week} in one week` : '', x.run ? `${x.run} in a row` : ''].filter(Boolean).join(', ');
function onRepeatViz(s) {
  const list = repeatList(s).slice(0, 8);
  if (!list.length) return '';
  return `<div class="viz"><h2>On repeat</h2><span class="sub">Songs you played again and again: seven times or more in one week, or back to back</span><div class="rank">${list.map((x) =>
    `<button class="r with-cover" ${trackAttrs(x.artist, x.track, { album: x.album, cover: x.cover })}>${coverHtml(x.cover, x.album || x.track, x.artist, 'tiny')}<span class="t"><b>${esc(x.track)}</b><small>${esc(x.artist)}, ${x.week ? 'week of ' : ''}${esc(shortDate(x.at))}</small></span><span class="rp">${esc(repeatWords(x))}</span></button>`).join('')}</div></div>`;
}
/** The decades as one bar, oldest first and darker toward now; [tap]: each opens its sheet. */
function decadeBar(ds, known, tap) {
  const tag = tap ? 'button' : 'i';
  return `<span class="decbar">${ds.map((d, i) => {
    const k = (i + 1) / ds.length;
    return `<${tag} class="${k > 0.5 ? 'dk' : ''}${statsDecade === d.decade ? ' on' : ''}"${tap ? ` data-decade="${d.decade}" aria-label="The ${d.decade}s"` : ''} style="flex:${d.plays} 1 0;--k:${k.toFixed(2)}">${d.plays / known >= 0.09 ? `<span>${String(d.decade).slice(2)}s</span>` : ''}</${tag}>`;
  }).join('')}</span>`;
}
function decadesView(s) {
  const x = s.decades || {}, ds = x.decades || [];
  if (ds.length < 2 && !statsDecade) return '';
  const known = Math.max(1, x.known);
  const share = x.total ? Math.round((x.known / x.total) * 100) : 0;
  return `<div class="viz"><h2>Decades</h2><span class="sub">When the music you played came out (${share}% of plays have a year). Tap a decade for more.</span>${decadeBar(ds, known, true)}
    <div class="chips">${ds.map((d) => `<button class="chip${statsDecade === d.decade ? ' on' : ''}" data-decade="${d.decade}">${d.decade}s<small>&nbsp;${pct(d.plays / known)}</small></button>`).join('')}</div></div>`;
}
/** One decade: its share, Stats for it alone, and your records from it. */
function openDecade(d) {
  const x = (STATS && STATS.decades) || {}, e = (x.decades || []).find((y) => y.decade === d) || { plays: 0 };
  const on = statsDecade === d;
  const recs = COLL.filter((r) => decadeOf(r) === d).length;
  openSheet(`<div class="sheet-title">The ${d}s</div>
    <p class="body">${e.plays ? `${plural(e.plays, 'play')} ${esc(inPeriod(STATS.range))} came out in the ${d}s, ${pct(e.plays / Math.max(1, x.known))} of the plays with a known year.` : `Nothing from the ${d}s ${esc(inPeriod(STATS.range))}.`}</p>
    <div class="sheet-actions col"><button class="btn filled" data-decstats="${on ? 0 : d}">${on ? 'Stats for every decade' : `Stats for the ${d}s`}</button>
    ${recs ? `<button class="btn tonal" data-decshelf="${d}">${icon('big-grid')}${plural(recs, 'record')} from the ${d}s on your shelf</button>` : ''}</div>`);
  $('#sheetContent').onclick = (ev) => {
    const st = ev.target.closest('[data-decstats]');
    if (st) { closeSheet(); if (isOpen('blockPage')) closePage(); setStatsDecade(+st.dataset.decstats); window.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    const sh = ev.target.closest('[data-decshelf]');
    if (sh) {
      closeSheet();
      while (pageStack.length) closePage();
      shelf.decade = +sh.dataset.decshelf;
      showTab('shelf');
      renderShelf();
    }
  };
}

// ---- your taste: the views of your whole history, at the end of Stats ----

let tasteAsked = false;
/** The atlas, you vs everyone and your concerts, and the genre map: all of your history, whatever the period. */
function tasteHtml() {
  if (!EXPLORE && !tasteAsked) { tasteAsked = true; send({ t: 'explore' }); }
  const x = EXPLORE || {}, tail = x.tail || {};
  const intro = tail.artists ? `<p class="xp-intro">You've played <b>${num(tail.artists)}</b> artists${tail.few ? `, <b>${num(tail.few)}</b> of them fewer than ${tail.fewPlays} times` : ''}.</p>` : '';
  return `<section class="stats-taste"><div class="xrow-head"><h2>Your taste</h2></div><span class="sub">All of your history, whatever the period above</span>${intro}
    <div class="xp-tiles">
      <button class="xp-tile" data-atlas>${ATLAS_ART}<b>Musical atlas</b><small>Universe, family tree, landscape</small></button>
      <button class="xp-tile" id="openVs">${VS_ART}<b>You vs everyone</b><small id="vsTileLine">Compared with all of Last.fm</small></button>
      <button class="xp-tile wide" id="openConcerts">${TICKET_ART}<span><b>Your concerts</b><small>${(ST.setlist || {}).concerts ? `${plural(ST.setlist.concerts, 'show')} you were at, and what they did to your listening` : 'The shows you were at, from setlist.fm'}</small></span></button>
    </div>${genreMapHtml(x.genreMap)}</section>`;
}

// ---- the week as seven days, and each day on a 24-hour strip ----

const DOW_LETTER = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
/** Plays as lines on a 24-hour strip ([hours, color] pairs; color 1–5 the top artists, 0 everyone else), with a "now" mark. */
function stripHtml(plays, nowH, small) {
  const ticks = plays.map(([hr, c]) => `<i style="left:${Math.min(99.6, (hr / 24) * 100).toFixed(2)}%;background:${c ? `var(--a${c})` : 'color-mix(in srgb, var(--md-on-surface) 35%, transparent)'}"></i>`).join('');
  return `<div class="dstrip${small ? ' small' : ''}">${ticks}${nowH != null ? `<b class="now" style="left:${((nowH / 24) * 100).toFixed(2)}%"></b>` : ''}</div>`;
}
const hoursAxis = (cls = '') => `<div class="hours ${cls}"><i>0</i><i>6</i><i>12</i><i>18</i><i>24</i></div>`;
const legendHtml = (names) => (names.length ? `<div class="dlegend">${names.map((n, i) => `<button data-artist="${esc(n)}"><i style="background:var(--a${i + 1})"></i>${esc(n)}</button>`).join('')}</div>` : '');
/** Where now is on today's strip: hours on the clock since midnight, as the strips count them (on a DST day too). */
const clockHours = () => { const d = new Date(); return d.getHours() + d.getMinutes() / 60 + d.getSeconds() / 3600; };
const nowHours = (s) => (s.range.running ? clockHours() : null);

function weekViz(s) {
  const w = s.week;
  if (!w) return '';
  const max = Math.max(1, ...w.days.map((v) => v || 0), ...w.last);
  const cols = w.days.map((v, i) => `<div class="d${i === w.today ? ' today' : ''}${v == null ? ' ahead' : ''}" data-col="wk" data-i="${i}">
    <div class="cl"><i class="last" style="height:${((w.last[i] / max) * 100).toFixed(1)}%"></i>${v ? `<i class="v" style="height:${((v / max) * 100).toFixed(1)}%"></i>` : ''}</div><span>${DOW_LETTER[i]}</span></div>`).join('');
  const t = w.today;
  const def = t >= 0 ? `Today so far: <b>${plural(w.days[t] || 0, 'play')}</b>; last ${DOW_LONG[t].replace(/s$/, '')}: ${w.last[t]} in all` : weekText(w, w.days.indexOf(Math.max(...w.days.map((v) => v || 0))));
  return `<div class="viz"><h2>Your week</h2><span class="sub">Plays per day; the week before in outline</span><div class="wk">${cols}</div><div class="readout" id="ro-wk">${def}</div></div>`;
}
const weekText = (w, i) => `${DOW_LONG[i].replace(/s$/, '')}: <b>${plural(w.days[i] || 0, 'play')}</b>; a week earlier: ${w.last[i]}`;

function daysViz(s) {
  const w = s.week;
  if (!w) return '';
  const rows = w.strips.map((p, i) => `<div class="row${w.days[i] == null ? ' ahead' : ''}"><span>${DOW[i]}</span>${stripHtml(p, i === w.today ? clockHours() : null, true)}</div>`).join('');
  return `<div class="viz"><h2>Your days</h2><span class="sub">When you listened, day by day</span><div class="ddays">${rows}<div class="row"><span></span>${hoursAxis()}</div></div>${legendHtml(w.artists || [])}</div>`;
}

// ---- the Day tab: built for a handful of songs rather than thousands ----

/** "1 h 05", "40 min": rounded to the minute before it's split, so 119.7 is 2 h 00 and not 1 h 60. */
const minutesLabel = (m) => { const r = Math.round(m); return r >= 60 ? `${Math.floor(r / 60)} h ${pad(r % 60)}` : `${r} min`; };
const weekdayOf = (s) => new Date(s.range.from * 1000).toLocaleDateString([], { weekday: 'long' });
function todayViz(s) {
  const d = s.day || {}, sm = s.summary;
  const min = sm.scrobbles * 3.5, mr = Math.round(min);
  const big = mr >= 60 ? `<b>${Math.floor(mr / 60)} h ${pad(mr % 60)}</b>` : `<b>${mr}</b><span>min</span>`;
  let usual = '';
  if (d.usual) {
    const um = d.usual.plays * 3.5;
    const scale = Math.max(min, um, 1) * 1.15;
    const word = !s.range.running ? `A usual ${weekdayOf(s)}: <b>${minutesLabel(um)}</b>`
      : `${min > um * 1.1 ? 'More than' : min < um * 0.9 ? 'Less than' : 'About'} your usual ${weekdayOf(s)} by now (<b>${minutesLabel(um)}</b>)`;
    usual = `<div class="dusual"><div class="track"><i class="you" style="width:${((min / scale) * 100).toFixed(1)}%"></i><i class="usual" style="left:${((um / scale) * 100).toFixed(1)}%"></i></div><small>${word}</small></div>`;
  }
  return `<div class="viz dtoday"><div class="k">${s.range.running ? 'Today so far' : esc(periodName(s.range))}</div><div class="big">${big}</div>
    <div class="facts"><span><b>${num(sm.scrobbles)}</b> plays</span><span><b>${num(sm.artists)}</b> ${sm.artists === 1 ? 'artist' : 'artists'}</span><span><b>${(d.sessions || []).length}</b> ${(d.sessions || []).length === 1 ? 'session' : 'sessions'}</span></div>${usual}</div>`;
}
function sessionWords(x) {
  if (x.vinyl * 2 >= x.count && x.album) return [`${esc(x.album)}`, `${icon('album', 'i small')}on the turntable`];
  if (x.album) return [`${esc(x.album)}`, `${esc(x.albumArtist)}`];
  return [x.artistShare >= 0.5 ? `mostly ${esc(x.artist)}` : `${esc(x.artist)} and more`, ''];
}
function daylineViz(s) {
  const d = s.day;
  if (!d) return '';
  const now = nowHours(s);
  const ss = d.sessions.map((x, i) => {
    const live = s.range.running && i === d.sessions.length - 1 && Date.now() / 1000 - x.to < 30 * 60;
    const [what, how] = sessionWords(x);
    return `<div class="s"${x.album ? ` ${albumAttrs(x.albumArtist, x.album)}` : ''}><span class="t">${fmtTime(x.from * 1000)}–${live ? 'now' : fmtTime(x.to * 1000)}</span>
      <span class="w"><b>${plural(x.count, 'song')}</b>, ${what}${how ? `<small>${how}</small>` : ''}</span><i class="sw" style="background:${x.color ? `var(--a${x.color})` : 'var(--md-outline)'}"></i></div>`;
  }).join('');
  return `<div class="viz"><h2>Your day</h2><span class="sub">Each block is a session: music without a long break</span>${stripHtml(d.plays, now)}${hoursAxis('big')}
    <div class="dsessions">${ss}</div></div>`;
}
function firstLatestViz(s) {
  const d = s.day;
  if (!d || !d.first) return '';
  const card = (k, p) => `<button class="viz" ${trackAttrs(p.artist, p.title, { album: p.album, cover: p.cover })}><div class="k">${k}</div>${coverHtml(p.cover, p.album || p.title, p.artist, 'small')}<b>${esc(p.title)}</b><small>${esc(p.artist)}, ${fmtTime(p.ts * 1000)}</small></button>`;
  const same = d.first.ts === d.latest.ts;
  return `<div class="dpair${same ? ' one' : ''}">${card('First', d.first)}${same ? '' : card(s.range.running ? 'Latest' : 'Last', d.latest)}</div>`;
}
function repeatsViz(s) {
  const d = s.day;
  if (!d) return '';
  const rows = (d.repeats || []).map((p) => `<button class="r with-cover" ${trackAttrs(p.artist, p.title, { album: p.album, cover: p.cover })}>${coverHtml(p.cover, p.album || p.title, p.artist, 'tiny')}<span class="t"><b>${esc(p.title)}</b><small>${esc(p.artist)}</small></span><span class="v">${p.plays}×</span></button>`);
  const artists = (d.topArtists || []).filter((a) => a.plays >= 3).map((a) => `<button class="r with-cover" data-artist="${esc(a.name)}">${coverHtml(a.photo, a.name, a.name, 'tiny round')}<span class="t"><b>${esc(a.name)}</b><small>${plural(a.plays, 'song')}</small></span><span class="v">${a.plays}</span></button>`);
  if (!rows.length && !artists.length) return '';
  return `<div class="viz"><h2>On repeat</h2><div class="rank">${rows.join('')}${artists.slice(0, Math.max(1, 3 - rows.length)).join('')}</div></div>`;
}
function newTodayViz(s) {
  const d = s.day;
  if (!d || (!(d.newArtists || []).length && !d.newSongs)) return '';
  return `<div class="viz"><h2>New ${s.range.running ? 'today' : 'that day'}</h2><span class="sub">First time ever</span><div class="chips">${(d.newArtists || []).map((n) => `<button class="chip" data-artist="${esc(n)}">${icon('people', 'i small')}${esc(n)}</button>`).join('')}
    ${d.newSongs ? `<span class="chip static">${icon('music', 'i small')}${plural(d.newSongs, 'new song')}</span>` : ''}</div></div>`;
}
function onThisDayViz(s) {
  const d = s.day;
  if (!d || !(d.onThisDay || []).length) return '';
  const date = new Date(s.range.from * 1000).toLocaleDateString([], { day: 'numeric', month: 'long' });
  return `<div class="viz"><h2>On this day</h2><span class="sub">${esc(date)}, other years</span><div class="rank">${d.onThisDay.map((y) =>
    `<button class="r with-cover" data-day="${esc(y.id)}">${coverHtml(y.cover, y.album || y.artist, y.albumArtist || y.artist, 'tiny')}<span class="t"><b>${esc(y.album || y.artist)}</b><small>${esc(y.album ? y.albumArtist : '')}${y.album ? ', ' : ''}${y.year}, ${plural(y.plays, 'play')} that day</small></span>${icon('chevron-right')}</button>`).join('')}</div></div>`;
}
function dayFactsViz(s) {
  const st = s.streaks || {}, d = s.day || {};
  return `<div class="tiles">${s.range.running ? tile('Streak', st.current ? `Day ${st.current}` : '–', st.current ? `since ${dateLabel(st.currentFrom)}` : 'starts with music today', '', 'fire') : ''}
    ${tile('Vinyl', d.vinyl ? plural(d.vinyl, 'track') : 'none', d.vinyl ? `of ${plural(s.summary.scrobbles, 'play')}` : 'all streamed', '', 'album')}</div>`;
}

// ---- Arrange: the order of the blocks, and which show ----

function openArrange() {
  const group = statsGroup(STATS);
  const lay = statsLayout(group);
  const kinds = group === 'day' ? ['day'] : SPAN;
  const sizes = group !== 'day' && statsView() === 'cards';
  const size = (id) => (sizes && !NEVER_CARD.includes(id) ? `<button class="chip ar-size${lay.small.has(id) ? ' on' : ''}" data-size>${lay.small.has(id) ? 'Card' : 'Full'}</button>` : '');
  const row = (id) => `<div class="ar-row${lay.hidden.has(id) ? ' off' : ''}" data-ar="${id}"><span class="ar-grip" aria-label="Move ${esc(BLOCKS[id].name)}">${icon('drag')}</span>
    <span class="ar-name">${esc(BLOCKS[id].name)}${BLOCKS[id].kinds && !BLOCKS[id].kinds.includes('day') && BLOCKS[id].kinds.length === 1 ? `<small>${esc(BLOCKS[id].kinds[0])}s only</small>` : ''}</span>
    ${size(id)}<label class="ar-sw"><input type="checkbox"${lay.hidden.has(id) ? '' : ' checked'} aria-label="Show ${esc(BLOCKS[id].name)}"><i class="switch"></i></label></div>`;
  openSheet(`<div class="sheet-title">Arrange ${group === 'day' ? 'the Day tab' : 'Stats'}</div>
    <p class="body">Drag the handles to reorder, switch blocks off to hide them.${sizes ? ' Card shows a block as a sentence and a small picture; tap it on the page to see it full size.' : ''} ${group === 'day' ? 'Weeks, months and years have their own order.' : 'For weeks, months, years and all time; the Day tab has its own.'}</p>
    <div class="ar-list">${lay.order.filter((id) => (BLOCKS[id].kinds || SPAN).some((x) => kinds.includes(x))).map(row).join('')}</div>
    <div class="sheet-actions"><button class="btn text" id="arReset">Reset</button><button class="btn filled" id="arDone">Done</button></div>`);
  const list = $('#sheetContent .ar-list');
  const save = () => {
    const ids = $$('.ar-row', list).map((r) => r.dataset.ar);
    const hidden = new Set($$('.ar-row', list).filter((r) => !r.querySelector('input').checked).map((r) => r.dataset.ar));
    // Blocks of the other kind keep their places in the saved order, and their size.
    const full = lay.order.filter((id) => !ids.includes(id));
    const small = new Set([...lay.small].filter((id) => !ids.includes(id)));
    $$('.ar-row', list).forEach((r) => { const b = r.querySelector('.ar-size'); if (b ? b.classList.contains('on') : lay.small.has(r.dataset.ar)) small.add(r.dataset.ar); });
    saveLayout(group, [...ids, ...full], hidden, small);
    arrangeRedraw();
  };
  list.addEventListener('change', (e) => { e.target.closest('.ar-row').classList.toggle('off', !e.target.checked); save(); });
  list.addEventListener('click', (e) => {
    const b = e.target.closest('.ar-size');
    if (!b) return;
    b.classList.toggle('on');
    b.textContent = b.classList.contains('on') ? 'Card' : 'Full';
    save();
  });
  arrangeDrag(list, save);
  $('#sheetContent').onclick = (e) => {
    if (e.target.closest('#arDone')) { clearTimeout(arrangeTimer); closeSheet(); renderStats(); }
    if (e.target.closest('#arReset')) { saveLayout(group, null); openArrange(); arrangeRedraw(); }
  };
}
/** The page behind Arrange follows each change a moment later, as the sheet can also close by a swipe, Back or the scrim. */
let arrangeTimer = 0;
function arrangeRedraw() {
  clearTimeout(arrangeTimer);
  arrangeTimer = setTimeout(renderStats, 400);
}
/** Rows follow the finger by their handle and trade places with a neighbour once past its middle. */
function arrangeDrag(list, done) {
  let row = null, y0 = 0;
  list.addEventListener('pointerdown', (e) => {
    const g = e.target.closest('.ar-grip');
    if (!g) return;
    row = g.closest('.ar-row');
    y0 = e.clientY;
    row.classList.add('lift');
    g.setPointerCapture(e.pointerId);
    e.preventDefault();
  });
  list.addEventListener('pointermove', (e) => {
    if (!row) return;
    const dy = e.clientY - y0;
    row.style.transform = `translateY(${dy}px)`;
    const mid = row.offsetTop + row.offsetHeight / 2 + dy;
    const next = row.nextElementSibling, prev = row.previousElementSibling;
    const swap = next && mid > next.offsetTop + next.offsetHeight / 2 ? () => list.insertBefore(next, row)
      : prev && mid < prev.offsetTop + prev.offsetHeight / 2 ? () => list.insertBefore(row, prev) : null;
    if (!swap) return;
    const top = row.offsetTop;
    swap();
    y0 += row.offsetTop - top;
    row.style.transform = `translateY(${e.clientY - y0}px)`;
  });
  const end = () => {
    if (!row) return;
    row.style.transform = '';
    row.classList.remove('lift');
    row = null;
    done();
  };
  list.addEventListener('pointerup', end);
  list.addEventListener('pointercancel', end);
}

/** Tap (or drag across) a heat cell, a column, a ridge or the river: its numbers show in the line under the chart. */
function onVizPointer(e, data) {
  // A finger stays captured by what it first touched, so e.target never moves along: what's under it now.
  const t = document.elementFromPoint(e.clientX, e.clientY) || e.target;
  const cell = t.closest('[data-heat]');
  if (cell) {
    const ro = $('#ro-' + cell.dataset.heat);
    $$('.heat i.sel').forEach((x) => x.classList.remove('sel'));
    cell.classList.add('sel');
    const d = +cell.dataset.d, hr = +cell.dataset.h;
    if (ro) ro.innerHTML = `<b>${plural(+cell.dataset.v, 'play')}</b> on ${DOW[d]}, ${pad(hr)}:00–${pad(hr + 1)}:00`;
    return;
  }
  const rvHit = t.closest('[data-rv]');
  if (rvHit && data && data.river) {
    const b = +rvHit.dataset.rv;
    const B = data.river.starts.length;
    // Each river has its own cursor and readout (the Stats tab's and a page's can both be there).
    const viz = rvHit.closest('.viz');
    const cur = viz.querySelector('.cursor');
    if (cur) { cur.setAttribute('x1', (b / (B - 1)) * 340); cur.setAttribute('x2', (b / (B - 1)) * 340); cur.setAttribute('visibility', 'visible'); }
    viz.querySelector('.readout').innerHTML = riverText(data.river, b, viz.dataset.other);
    return;
  }
  const ridge = t.closest('[data-ridge]');
  if (ridge && data && data.pulse && !t.closest('[data-artist]')) {
    $$('.ridges .row.sel').forEach((x) => x.classList.remove('sel'));
    ridge.classList.add('sel');
    $('#ro-pulse').innerHTML = pulseText(data.pulse, +ridge.dataset.ridge);
    return;
  }
  const col = t.closest('[data-col]');
  if (!col || !data) return;
  col.parentElement.querySelectorAll('.sel').forEach((x) => x.classList.remove('sel'));
  col.classList.add('sel');
  const i = +col.dataset.i;
  if (col.dataset.col === 'na') $('#ro-na').innerHTML = newArtistsText(data.newArtists.buckets[i], data.newArtists.unit);
  else if (col.dataset.col === 'g') $('#ro-g').innerHTML = genreText(data.genres, i);
  else if (col.dataset.col === 'wk') $('#ro-wk').innerHTML = weekText(data.week, i);
  else if (col.dataset.col === 'tl') $('#ro-tl').innerHTML = `${esc(periodLabel(data.timeline.starts[i], data.timeline.unit))}: <b>${plural(data.timeline.counts[i], 'play')}</b>`;
  else if (col.dataset.col === 'am') {
    const d = new Date(data.monthsFrom);
    d.setMonth(d.getMonth() + i);
    col.closest('.viz').querySelector('.readout').innerHTML = `${esc(d.toLocaleDateString([], { month: 'long', year: 'numeric' }))}: <b>${plural(data.months[i], 'play')}</b>`;
  } else if (col.dataset.col === 'ah') col.closest('.viz').querySelector('.readout').innerHTML = `${pad(i)}:00–${pad(i + 1)}:00: <b>${plural(data.hours[i], 'play')}</b>`;
  else if (col.dataset.col === 'tt') col.closest('.viz').querySelector('.readout').innerHTML = shapeText(data, i);
  else if (col.dataset.col === 'ck') col.closest('.viz').querySelector('.readout').innerHTML = clickedText(data, i);
  else if (col.dataset.col === 'rr') col.closest('.viz').querySelector('.readout').innerHTML = runText(data, i);
  else if (col.dataset.col === 'aw') {
    const g = +col.dataset.g || 1;
    // Calendar days from the first week's Monday, so a DST change doesn't move the dates.
    const w0 = new Date(data.weeksFrom);
    const day = (k) => new Date(w0.getFullYear(), w0.getMonth(), w0.getDate() + k).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
    const n = (data.weeks || []).slice(i * g, i * g + g).reduce((a, b) => a + b, 0);
    col.closest('.viz').querySelector('.readout').innerHTML = `${g === 1 ? `Week of ${esc(day(i * 7))}` : `${esc(day(i * g * 7))} to ${esc(day((i + 1) * g * 7 - 1))}`}: <b>${plural(n, 'play')}</b>`;
  }
}

// ---------- the story: full-screen cards about a period ----------

const PERSONAS = {
  nightOwl: { title: 'Night Owl', icon: 'moon', why: (m) => `${pct(m.night)} of your plays happened between 22:00 and 04:00.` },
  earlyBird: { title: 'Early Bird', icon: 'sun', why: (m) => `${pct(m.morning)} of your plays came between 5 and 9 in the morning.` },
  loyalist: { title: 'Loyalist', icon: 'heart', why: (m) => `${esc(m.topArtist)} alone made up ${pct(m.topShare)} of your listening.` },
  explorer: { title: 'Explorer', icon: 'explore', why: (m) => `${pct(m.newShare)} of your plays were artists you had never heard before.` },
  onRepeat: { title: 'On Repeat', icon: 'repeat', why: (m) => (m.loop >= 5 ? `You played one song ${m.loop} times in a row.` : `One song got ${m.obsession} plays in a single week.`) },
  weekender: { title: 'Weekend Warrior', icon: 'weekend', why: (m) => `${pct(m.weekend)} of your plays fell on Saturdays and Sundays.` },
  crateDigger: { title: 'Crate Digger', icon: 'album', why: (m) => `${pct(m.vinyl)} of everything you played came off your record shelf.` },
  genreHopper: { title: 'Genre Hopper', icon: 'shuffle', why: (m) => `${m.genres} different genres, each with a real share of your plays.` },
  allRounder: { title: 'All-Rounder', icon: 'star', why: () => 'No single habit stands out: no artist, hour or genre takes most of your plays.' },
};

let STORY = null;
let storySlides = [];
let storyIdx = 0;

function openStory(id) {
  STORY = null;
  storySlides = [{ cls: 'deep', wait: true, html: '<div class="t m in">Putting it together…</div>' }];
  $('#story').hidden = false;
  syncBack();
  applyStoryTint(null);
  showSlide(0);
  send({ t: 'story', range: id });
}
window.onStory = (s) => {
  if ($('#story').hidden) return;
  STORY = s;
  applyStoryTint(s);
  storySlides = buildSlides(s);
  showSlide(0);
};
function closeStory() {
  $('#story').hidden = true;
  $('#story').style.transform = '';
  $('#story').style.borderRadius = '';
  $('#story').style.willChange = '';
  $('#storySlide').innerHTML = '';
  Tilt.refresh();
  syncBack();
}

function showSlide(i) {
  storyIdx = Math.max(0, Math.min(storySlides.length - 1, i));
  const sl = storySlides[storyIdx];
  $('#storyBars').innerHTML = storySlides.map((x, k) => `<i class="${k < storyIdx ? 'done' : k === storyIdx && !x.wait ? 'now' : ''}" style="--dur:${x.dur || 7}s"><b></b></i>`).join('');
  $('#storySlide').innerHTML = `<div class="slide ${sl.cls}"><div class="aur bg"><i></i><i></i><i></i></div>${sl.html}</div>`;
  $$('#storySlide [data-fit]').forEach((el) => {
    const big = el.classList.contains('xl') ? 96 : el.classList.contains('pt') ? 40 : 54;
    fitText(el, +el.dataset.fit, big, el.classList.contains('pt') ? 22 : 26);
  });
  $('#story').classList.remove('paused');
  requestAnimationFrame(() => requestAnimationFrame(() => {
    $$('#storySlide [data-w]').forEach((el) => { el.style.width = el.dataset.w; });
    countUp();
  }));
  const bar = $('#storyBars i.now b');
  if (bar) bar.addEventListener('animationend', () => { if (storyIdx < storySlides.length - 1) showSlide(storyIdx + 1); }, { once: true });
  Tilt.refresh();
}

function countUp() {
  $$('#storySlide [data-count]').forEach((el) => {
    const to = +el.dataset.count;
    const t0 = performance.now();
    const step = (t) => {
      const p = Math.min(1, (t - t0) / 1200);
      el.textContent = num(Math.round(to * (1 - Math.pow(1 - p, 3))));
      if (p < 1 && el.isConnected) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  });
}

const dly = (s) => `style="--d:${s}s"`;

/** The story's colors: three hues from the period's top album (or top artist's photo), mixed differently on each slide. */
let storyTintUrl = '';
function applyStoryTint(s) {
  const x = s || STORY;
  const seed = x ? periodName(x.range) : 'Heavy Rotation';
  const al = x ? (x.albums || [])[0] : null, ar = x ? (x.artists || [])[0] : null;
  storyTintUrl = (al && al.cover) || (ar && ar.cover) || '';
  const c = storyTintUrl ? TINTS[storyTintUrl] : null;
  if (storyTintUrl && !c) send({ t: 'palette', url: storyTintUrl });
  let h1 = hue(seed + ' story'), h2 = (h1 + 55) % 360, k = 1;
  if (c) {
    const v = hexHsl(c.vibrant), d = hexHsl(c.dominant);
    h1 = v[0];
    h2 = Math.abs(v[0] - d[0]) < 25 ? (v[0] + 50) % 360 : d[0];
    k = Math.max(v[1], d[1]) < 0.12 ? 0.12 : 1; // black-and-white covers: an almost gray story
  }
  const st = $('#story').style;
  st.setProperty('--s1', h1.toFixed(0));
  st.setProperty('--s2', h2.toFixed(0));
  st.setProperty('--s3', ((h1 + 300) % 360).toFixed(0));
  st.setProperty('--ss', String(k));
}

/** A small seeded random generator, so the stars land in the same places every time. */
function seededRandom(seed) {
  let a = seed | 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** The day as a sundial: one ray per hour, as long as its plays, the golden hour lit. */
function storyClock(hours, gh) {
  const max = Math.max(1, ...hours), C = 150, r0 = 58, band = 74;
  const at = (r, deg) => [(C + r * Math.sin((deg * Math.PI) / 180)).toFixed(1), (C - r * Math.cos((deg * Math.PI) / 180)).toFixed(1)];
  const rays = hours.map((v, i) => {
    const [x1, y1] = at(r0, i * 15), [x2, y2] = at(r0 + 6 + band * (v / max), i * 15);
    return `<line class="${i === gh ? 'on' : ''}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" pathLength="1" style="--d:${(0.25 + i * 0.03).toFixed(2)}s;opacity:${i === gh ? 1 : (0.25 + 0.55 * (v / max)).toFixed(2)}"/>`;
  }).join('');
  const ticks = [0, 6, 12, 18].map((hh) => { const [x, y] = at(146, hh * 15); return `<text x="${x}" y="${(+y + 4).toFixed(1)}">${hh}</text>`; }).join('');
  return `<svg class="sundial in" viewBox="0 0 300 300"><circle cx="${C}" cy="${C}" r="${r0 - 8}" class="face"/>${rays}${ticks}<text class="hr" x="${C}" y="${C + 11}">${pad(gh)}:00</text></svg>`;
}

/** A 7" single spinning at 45: the song's cover as the label, the big hole in the middle. */
function singleHtml(cover, seed) {
  const label = cover ? `background-image:url('${cssUrl(cover)}')` : '';
  return `<div class="single in" style="--h:${hue(seed)}"><div class="sv"><i class="lab" style="${label}">${cover ? '' : '<b>45</b>'}</i><i class="hole"></i></div></div>`;
}

function buildSlides(s) {
  const out = [];
  const name = periodName(s.range);
  const per = periodWord(s.range.kind);
  const sm = s.summary;
  if (!sm.scrobbles) return [{ cls: 'deep', dur: 5, html: `<div class="t m in">No plays in ${esc(name)}.</div>` }];
  const album = (s.albums || [])[0];
  const disc = (cover, hueKey) => `<div class="spinner-disc bg" style="--h:${hue(hueKey)}"><i style="${cover ? `background-image:url('${cssUrl(cover)}')` : ''}"></i></div>`;
  // Intro: a record rising from the bottom of the screen
  out.push({ cls: 'deep intro', dur: 5, html: `${disc(album && album.cover, name)}<div class="k in">${s.range.kind === 'all' ? 'Your whole history' : `Your ${per}`} on Heavy Rotation</div>
    <div class="t xl in" ${dly(0.15)} data-fit="3">${esc(name)}</div><p class="p in" ${dly(0.35)}>on record.</p>
    <p class="hint bg in" ${dly(0.9)}>Tap right for next, left for back, hold to pause</p>` });
  // Volume: one huge number and a ticker running across
  let vs = '';
  if (sm.prev && sm.prev.scrobbles > 0) {
    const p = Math.round(((sm.scrobbles - sm.prev.scrobbles) * 100) / sm.prev.scrobbles);
    vs = `<span class="badge in" ${dly(0.6)}>${icon(p >= 0 ? 'trend-up' : 'down-trend')}${Math.abs(p)}% ${p >= 0 ? 'more' : 'less'} than ${esc(lastWord(s.range.kind))}</span>`;
  }
  const tick = `≈ ${num(sm.hours)} hours of music, ${sm.days} of ${sm.spanDays} days, ${num(sm.scrobbles)} plays, `;
  out.push({ cls: 'bright', html: `<div class="k in">You played</div><div class="t huge in" ${dly(0.1)}><span data-count="${sm.scrobbles}">0</span></div>
    <div class="k in" ${dly(0.2)}>tracks</div>
    <p class="p in" ${dly(0.3)}>That's about <b>${num(sm.hours)} hours</b> of music, on ${sm.days} of ${sm.spanDays} days.</p>${vs}
    <div class="ticker bg"><div><span>${esc(tick.repeat(3))}</span><span>${esc(tick.repeat(3))}</span></div></div>` });
  // Personality: its icon inside a turning ring of words
  const ps = s.personality;
  if (ps && PERSONAS[ps.id]) {
    const P = PERSONAS[ps.id];
    const R = ps.runnerUp && PERSONAS[ps.runnerUp];
    const words = 'Your listening personality   '.toUpperCase().repeat(2);
    out.push({ cls: 'deep', html: `<div class="ring in"><svg viewBox="0 0 200 200"><defs><path id="ringPath" d="M100,100 m-82,0 a82,82 0 1,1 164,0 a82,82 0 1,1 -164,0"/></defs>
      <text><textPath href="#ringPath" textLength="512" lengthAdjust="spacing">${words}</textPath></text></svg><span class="core">${icon(P.icon)}</span></div>
      <div class="t m in" ${dly(0.2)} data-fit="2">${P.title}</div><p class="p in" ${dly(0.4)}>${P.why(ps.metrics)}</p>
      ${R ? `<span class="badge in" ${dly(0.7)}>${icon(R.icon)}with a touch of ${R.title}</span>` : ''}` });
  }
  // Top artist: the photo full-bleed in two colors, the name across the bottom
  const a0 = (s.artists || [])[0];
  if (a0) {
    const bg = a0.cover
      ? `<div class="duo bg"><i class="img" style="background-image:url('${cssUrl(a0.cover)}')"></i><i class="lo"></i><i class="hi"></i><i class="fade"></i></div>`
      : `<div class="duo bg none"><b>${esc(String(a0.name).trim().charAt(0).toUpperCase())}</b><i class="fade"></i></div>`;
    out.push({ cls: 'deep photo', html: `${bg}<div class="k in">Your top artist</div>
      <div class="t xl in" ${dly(0.15)} data-fit="3">${esc(a0.name)}</div><p class="p mono in" ${dly(0.35)}>${plural(a0.plays, 'play')}, ${pct(a0.share)} of everything, on ${plural(a0.days, 'day')}</p>` });
  }
  if ((s.artists || []).length >= 3) {
    out.push({ cls: 'deep', html: `<div class="k in">Your top artists</div><div class="list5">${s.artists.map((x, i) => `<div class="row in" ${dly(0.1 + i * 0.12)}><span class="n">${i + 1}</span>
      ${coverHtml(x.cover, x.name, x.name, 'round')}<span class="nm">${esc(x.name)}<small>${plural(x.plays, 'play')}</small></span><i class="bar5" data-w="${(x.plays / s.artists[0].plays) * 100}%"></i></div>`).join('')}</div>` });
  }
  // You against everyone: the top artist, all time
  const cmp = s.vs;
  if (cmp && cmp.avg > 0) {
    const big = Math.min(1, cmp.ratio >= 1 ? 1 : cmp.ratio), small = Math.min(1, cmp.ratio >= 1 ? 1 / cmp.ratio : 1);
    const line = cmp.ratio >= 1.15 ? `as much ${esc(cmp.name)} as their average listener` : cmp.ratio >= 0.85 ? `about as much ${esc(cmp.name)} as their average listener` : `of what their average listener played`;
    out.push({ cls: 'deep', html: `<div class="k in">You vs everyone</div>
      <div class="dots2 in" ${dly(0.15)}><span class="you" style="--s:${Math.sqrt(big).toFixed(3)}"><em>you</em></span><span class="avg" style="--s:${Math.sqrt(small).toFixed(3)}"><em>average</em></span></div>
      <div class="t huge in" ${dly(0.3)}>${cmp.ratio >= 0.85 ? fmtX(cmp.ratio) : pct(cmp.ratio)}</div>
      <p class="p in" ${dly(0.45)}>${line}. All time on Last.fm: <b>${plural(cmp.mine, 'play')}</b> from you, ${num(Math.round(cmp.avg))} from the average of ${compact(cmp.listeners)} listeners.</p>` });
  }
  // Song: a 7" single
  const t0 = (s.tracks || [])[0];
  if (t0) {
    const best = s.songBestDay && s.songBestDay.count > 1 ? `, ${s.songBestDay.count} of them on ${esc(dayName(s.songBestDay.day))} alone` : '';
    out.push({ cls: 'bright alt', html: `${singleHtml(t0.cover, t0.title + t0.artist)}<div class="k in" ${dly(0.1)}>${ofThe(s.range.kind, 'Song')}</div>
      <div class="t m in" ${dly(0.2)} data-fit="2">${esc(t0.title)}</div><p class="p in" ${dly(0.4)}>by ${esc(t0.artist)}. <b>${plural(t0.plays, 'play')}</b>${best}.</p>
      ${s.tracks.length > 1 ? `<p class="p mono small in" ${dly(0.7)}>Then: ${s.tracks.slice(1, 4).map((x) => esc(x.title)).join(', ')}</p>` : ''}` });
  }
  // Album, as a card that moves with the phone
  if (album) {
    out.push({ cls: 'deep', html: `<div class="k in">${ofThe(s.range.kind, 'Album')}</div><div class="in" ${dly(0.1)}>${holoCover(album.cover, album.title, album.artist, { owned: album.owned, key: 'story-album' })}</div>
      <div class="t m in" ${dly(0.25)} data-fit="2">${esc(album.title)}</div><p class="p in" ${dly(0.4)}>${esc(album.artist)}, ${plural(album.plays, 'play')}${album.owned ? ', on your shelf' : ''}</p>` });
  }
  // On stage: the concerts you went to, as ticket stubs
  const cs = s.concerts || [];
  if (cs.length) {
    const best = cs.filter((c) => c.after != null).sort((a, b) => b.after - a.after)[0];
    const rnd = seededRandom(hue(name) + cs.length * 13);
    out.push({ cls: 'paper live', dur: 8, html: `<div class="k in">On stage</div>
      <div class="t m in" ${dly(0.1)} data-fit="2">${cs.length === 1 ? `You saw ${esc(cs[0].artist)} live` : `${cs.length} concerts`}</div>
      <div class="stubs">${cs.slice(0, 3).map((c, i) => { const d = new Date(`${c.date}T12:00:00`); return `<div class="stub in" style="--d:${(0.3 + i * 0.2).toFixed(2)}s;--r:${((rnd() - 0.5) * 5).toFixed(1)}deg"><span class="sd"><b>${d.getDate()}</b><small>${esc(d.toLocaleDateString([], { month: 'short' }))}</small></span><span class="si"><b>${esc(c.artist)}</b><small>${esc([c.venue, c.city].filter(Boolean).join(', '))}</small></span></div>`; }).join('')}</div>
      ${cs.length > 3 ? `<p class="p mono small in" ${dly(0.8)}>and ${cs.length - 3} more</p>` : ''}
      ${best && best.after > best.before ? `<p class="p in" ${dly(0.9)}>After ${esc(best.artist)} ${best.venue ? `at ${esc(best.venue)}` : 'live'}, you played them <b>${plural(best.after, 'time')}</b> ${best.running ? 'since' : 'in four weeks'}, against ${best.before} in the four weeks before.</p>` : ''}` });
  }
  // Golden hour, on a sundial
  const gh = s.goldenHour;
  const mood = gh >= 5 && gh < 10 ? 'Mostly in the morning' : gh >= 10 && gh < 17 ? 'Mostly during the day' : gh >= 17 && gh < 22 ? 'Mostly in the evening' : 'Mostly at night';
  out.push({ cls: 'deep', html: `<div class="k in">Your golden hour</div>${storyClock(s.hours, gh)}
    <div class="t m in" ${dly(0.4)}>${mood}</div><p class="p in" ${dly(0.55)}>More plays start between ${pad(gh)}:00 and ${pad((gh + 1) % 24)}:00 than in any other hour.</p>` });
  // Discovery: one star per new artist
  const na = s.newArtists || {};
  if (na.count > 0) {
    const rnd = seededRandom(hue(name) * 7919 + na.count);
    const stars = Array.from({ length: Math.min(na.count, 160) }, (_, i) => {
      const big = i < 6;
      return `<i style="left:${(3 + rnd() * 94).toFixed(1)}%;top:${(4 + rnd() * 92).toFixed(1)}%;--z:${big ? 5 : 2 + rnd() * 2}px;--d:${(rnd() * 2.4).toFixed(2)}s;--t:${(2.2 + rnd() * 3).toFixed(1)}s"></i>`;
    }).join('');
    out.push({ cls: 'deep night', html: `<div class="stars bg">${stars}</div><div class="k in">New to you</div>
      <div class="t huge in" ${dly(0.15)}><span data-count="${na.count}">0</span></div><p class="p in" ${dly(0.35)}>artists you had never played before.${na.best ? ` The one that stuck: <b>${esc(na.best.name)}</b>, ${plural(na.best.plays, 'play')} since ${esc(dateLabel(na.best.first))}.` : ''}</p>` });
  }
  // On repeat: the title, again and again
  if (s.loop || s.obsession) {
    const lp = s.loop;
    const ob = s.obsession;
    const x = lp || ob;
    const what = lp ? `<b>${lp.run} times in a row</b> on ${esc(dayName(lp.at))}` : `<b>${ob.count} plays</b> in the week of ${esc(dateLabel(ob.week))}`;
    out.push({ cls: 'bright', html: `<div class="k in">On repeat</div><div class="stutter">${[0, 1, 2, 3, 4, 5].map((i) => `<div class="in" style="--d:${(0.1 + i * 0.09).toFixed(2)}s;--o:${(1 - i * 0.17).toFixed(2)}">${esc(x.track)}</div>`).join('')}</div>
      <p class="p in" ${dly(0.7)}>by ${esc(x.artist)}: ${what}.</p>` });
  }
  // Genres, as a cloud of words
  const gs = s.genres || [];
  if (gs.length) {
    const rnd = seededRandom(hue(gs[0].name) + 17);
    const cloud = gs.map((g, i) => `<span class="gw in${i === 0 ? ' top' : ''}" style="--d:${(0.15 + i * 0.12).toFixed(2)}s;--fs:${(18 + 44 * Math.sqrt(g.share / gs[0].share)).toFixed(0)}px;--r:${i === 0 ? 0 : ((rnd() - 0.5) * 12).toFixed(1)}deg">${esc(g.name)}<sup>${pct(g.share)}</sup></span>`);
    // the biggest word in the middle
    const order = cloud.slice(1);
    order.splice(Math.floor(order.length / 2), 0, cloud[0]);
    out.push({ cls: 'paper', html: `<div class="k in">Your sound</div><div class="cloud">${order.join('')}</div>
      <p class="p in" ${dly(0.8)}><b>${esc(gs[0].name)}</b> leads, with ${pct(gs[0].share)} of everything with a known genre.</p>` });
  }
  // Vinyl
  const v = s.vinyl || {};
  if (v.count > 0) {
    const r0 = (v.top || [])[0];
    out.push({ cls: 'deep', html: `${r0 ? `<div class="in">${holoCover(r0.cover, r0.title, r0.artist, { owned: true, key: 'story-vinyl' })}</div>` : ''}<div class="k in" ${dly(0.15)}>From your shelf</div>
      <div class="t m in" ${dly(0.2)}><span data-count="${v.count}">0</span> tracks on vinyl</div><p class="p in" ${dly(0.4)}>${pct(v.share)} of your listening${r0 ? `. Most spun: <b>${esc(r0.title)}</b>` : ''}.</p>` });
  }
  // Streak: one dot per day in a row
  const st = s.streak || {};
  if (st.longest >= 2) {
    const n = Math.min(st.longest, 70);
    out.push({ cls: 'bright alt', html: `<div class="k in">Longest streak</div>
      <div class="t huge in" ${dly(0.1)}><span data-count="${st.longest}">0</span></div><div class="k in" ${dly(0.15)}>days in a row with music</div>
      <div class="days">${Array.from({ length: n }, (_, i) => `<i style="--d:${(0.3 + i * (1.4 / n)).toFixed(2)}s"></i>`).join('')}${st.longest > n ? '<span>…</span>' : ''}</div>
      <p class="p in" ${dly(0.5)}>${esc(dateLabel(st.longestFrom))} to ${esc(dateLabel(st.longestTo))}.${s.busiestDay ? ` Biggest day: <b>${esc(dayName(s.busiestDay.day))}</b>, ${plural(s.busiestDay.count, 'play')}.` : ''}</p>` });
  }
  // Summary: the back of the sleeve, as a poster
  const P = ps && PERSONAS[ps.id];
  const side = (list, letter, f) => list.map((x, i) => `<p><span>${letter}${i + 1}</span><b>${esc(f(x))}</b><em>${num(x.plays)}</em></p>`).join('');
  out.push({ cls: 'paper poster', dur: 14, html: `<div class="sheet in">
      <div class="ph"><span>Heavy Rotation</span><span>HR–${esc(String(s.range.id).toUpperCase())}</span></div>
      <div class="ptop"><div><div class="pt" data-fit="2">${esc(name)}</div><div class="ps">on record, ${num(sm.scrobbles)} plays, ≈${num(sm.hours)} h</div></div>${coverHtml(album && album.cover, (album && album.title) || name, '', 'stamp')}</div>
      <h6>Side A: Artists</h6>${side(s.artists || [], 'A', (x) => x.name)}
      <h6>Side B: Songs</h6>${side(s.tracks || [], 'B', (x) => x.title)}
      <div class="pnums"><div><small>Top genre</small><b>${gs.length ? esc(gs[0].name) : '–'}</b></div><div><small>Personality</small><b>${P ? P.title : '–'}</b></div><div><small>Golden hour</small><b>${pad(gh)}:00</b></div></div>
      <div class="pfoot"><span class="code"></span><small>Pressed from your Last.fm history</small></div>
    </div>` });
  return out;
}

// ---------- settings ----------

/** A status dot, a [title] (markup) and a [sub] line (plain text: it can carry a song's title from another app). */
function statusLine(dot, title, sub) {
  return `<span class="dot ${dot}"></span><span class="grow">${title}${sub ? `<small>${esc(sub)}</small>` : ''}</span>`;
}

function renderServices() {
  const on = services();
  $('#svcChips').innerHTML = Object.entries(SERVICES).map(([k, s]) => `<button class="chip ${on.includes(k) ? 'on' : ''}" data-svc="${k}">${esc(s.name)}</button>`).join('');
}

function renderTidalSettings() {
  const t = ST.tidal || {};
  let st;
  if (t.busy) st = statusLine('busy', 'Talking to TIDAL…', '');
  else if (t.loggedIn) st = statusLine('ok', 'Connected and signed in', 'Exact links, and playlists straight into TIDAL');
  else if (t.hasApp) st = statusLine('ok', 'Exact links are on', t.builtIn ? 'Sign in to make playlists' : `Client ID ${t.clientHint || ''}, sign in to make playlists`);
  else st = statusLine('', 'Not set up', 'The TIDAL buttons open a search instead');
  $('#tdStatus').innerHTML = st;
  $('#tdSetup').hidden = !!t.hasApp && !showTidalForm;
  $('#tdConnected').hidden = !t.hasApp || showTidalForm;
  $('#tdLogin').hidden = !!t.loggedIn;
  $('#tdSignOut').hidden = !t.loggedIn;
  $('#tdSave').disabled = !!t.busy;
  $('#tdRedirect').textContent = t.redirect || 'recordshelf://tidal';
  $('#tdError').hidden = !t.error;
  $('#tdError').textContent = t.error || '';
}

function renderSetlistSettings() {
  const s = ST.setlist || {};
  $('#slStatus').innerHTML = s.hasKey ? statusLine('ok', 'Set up', s.builtIn ? 'Artist pages show their recent setlists' : `API key ${s.keyHint || ''}, artist pages show their recent setlists`)
    : statusLine('', 'Not set up', 'Recent setlists and concert playlists on artist pages');
  $('#slSetup').hidden = !!s.hasKey;
  $('#slConnected').hidden = !s.hasKey;
  $('#slForget').hidden = !!s.builtIn;
  if (document.activeElement !== $('#slUser')) $('#slUser').value = s.user || '';
  $('#slUserRefresh').hidden = !s.user || !!s.concertsBusy;
  $('#slUserStatus').textContent = !s.user ? '' : s.concertsBusy ? 'Fetching your concerts from setlist.fm…' : s.concertsError
    || (s.concerts ? `${plural(s.concerts, 'concert')}, updated ${whenLabel(s.concertsAt)}.` : '');
}

// ---- fix names before they're scrobbled ----

const FX_FIELD = { artist: 'Artist', title: 'Song', album: 'Album' };
const FX_HOW = { is: 'is', contains: 'contains', matches: 'matches' };
/** One name through one rule, as the app does it (scrobble/FixRules.kt). */
function fxApply(v, r) {
  if (!r.find || !v) return v;
  let out = v;
  if (r.how === 'is') out = v.trim().toLowerCase() === r.find.trim().toLowerCase() ? r.to : v;
  else if (r.how === 'contains') out = v.split(new RegExp(r.find.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i')).join(r.to);
  else { try { out = v.replace(new RegExp(r.find, 'gi'), r.to); } catch (_) { out = v; } }
  out = out.replace(/\s{2,}/g, ' ').trim();
  return out || v;
}
const fxRuleText = (r) => `${FX_FIELD[r.field] || 'Song'} ${FX_HOW[r.how] || 'contains'} "${r.find}"`;
const fxToText = (r) => `${r.to ? `→ "${r.to}"` : r.how === 'is' ? '' : '→ taken out'}${r.artist ? `, only for ${r.artist}` : ''}`;

function renderFixSettings() {
  const f = ST.fix || { presets: [], rules: [] };
  const on = f.presets.filter((p) => p.on).length + f.rules.filter((r) => r.on !== false).length;
  $('#fxStatus').innerHTML = statusLine(on ? 'ok' : '', on ? `${on} ${on === 1 ? 'fix' : 'fixes'} on` : 'None on', on ? 'Names are fixed before they\'re scrobbled' : 'Take "Remastered" off titles and the like');
  $('#fxPresets').innerHTML = f.presets.map((p) => `<label class="switch-row"><span>${esc(p.label)}<small>${esc(p.example)}</small></span><input type="checkbox" data-fxp="${p.id}"${p.on ? ' checked' : ''}><i class="switch"></i></label>`).join('');
  $('#fxRules').innerHTML = f.rules.length
    ? `<div class="list">${f.rules.map((r) => `<button class="item fx-rule" data-fx="${esc(r.id)}"><span class="grow"><b>${esc(fxRuleText(r))}</b><small>${esc(fxToText(r))}</small></span>${icon('chevron-right')}</button>`).join('')}</div>`
    : '<p class="hint">None yet. A rule can rename an artist ("Beatles, The" to "The Beatles"), take "(Live)" off one artist\'s songs, or anything a regular expression can match.</p>';
}

/** A sheet to add or change a rule, tried on your recent scrobbles. */
/** A rule of Fix names to change ([id]), or a new one, started from [draft] when given (a song held on a list). */
function openFixSheet(id, draft) {
  const f = ST.fix || { rules: [] };
  const old = f.rules.find((r) => r.id === id);
  const r = { id: '', field: 'title', how: 'contains', find: '', to: '', artist: '', on: true, ...(old || draft || {}) };
  const seen = new Set();
  const recent = ACT.slice().sort((a, b) => b.ts - a.ts).filter((e) => { const k = `${e.artist}|${e.track}`; if (seen.has(k)) return false; seen.add(k); return true; }).slice(0, 4);
  let sample = recent[0] || null;
  const seg = (sid, map, v) => `<div class="seg small" id="${sid}">${Object.entries(map).map(([k, l]) => `<button data-v="${k}" class="${k === v ? 'on' : ''}">${l}</button>`).join('')}</div>`;
  openSheet(`<div class="sheet-title">${old ? 'Change the rule' : 'A new rule'}</div>
    <div class="row-label"><span>Look at the</span></div>${seg('fxField', FX_FIELD, r.field)}
    <div class="row-label"><span>When it</span></div>${seg('fxHow', { is: 'is', contains: 'contains', matches: 'matches (regex)' }, r.how)}
    <label class="field"><span>Text</span><input type="text" id="fxFind" autocapitalize="off" spellcheck="false" value="${esc(r.find)}"></label>
    <label class="field"><span id="fxToLabel">Change it to</span><input type="text" id="fxTo" autocapitalize="off" spellcheck="false" value="${esc(r.to)}"></label>
    <label class="field"><span>Only for this artist (optional)</span><input type="text" id="fxArtist" autocapitalize="off" spellcheck="false" value="${esc(r.artist)}"></label>
    ${recent.length ? `<div class="row-label"><span>Try it on</span></div><div class="chips" id="fxRecent">${recent.map((e, i) => `<button class="chip${i === 0 ? ' on' : ''}" data-fxs="${i}">${esc(e.track)} <small>${esc(e.artist)}</small></button>`).join('')}</div>` : ''}
    <div class="fx-try" id="fxTry"></div>
    <div class="sheet-actions">${old ? '<button class="btn text" id="fxDel">Delete</button>' : ''}<button class="btn text" id="fxNo">Cancel</button><button class="btn filled" id="fxYes">Save</button></div>`);
  const read = () => ({ ...r, field: $('#fxField .on').dataset.v, how: $('#fxHow .on').dataset.v, find: $('#fxFind').value, to: $('#fxTo').value, artist: $('#fxArtist').value.trim() });
  const show = () => {
    const x = read();
    $('#fxToLabel').textContent = x.how === 'is' ? 'Change it to' : 'Change it to (empty: take it out)';
    if (!sample) { $('#fxTry').innerHTML = ''; return; }
    const before = { artist: sample.artist, title: sample.track, album: sample.album || '' }[x.field];
    const applies = !x.artist || x.artist.toLowerCase() === String(sample.artist).trim().toLowerCase();
    const after = applies ? fxApply(before, x) : before;
    $('#fxTry').innerHTML = !before ? `<small>That scrobble has no ${FX_FIELD[x.field].toLowerCase()} name.</small>`
      : after === before ? `<span>${esc(before)}</span><small>${x.find ? 'stays as it is' : 'Type what to look for'}</small>` : `<span><s>${esc(before)}</s></span><b>${esc(after)}</b>`;
  };
  $('#fxField').onclick = $('#fxHow').onclick = (e) => {
    const b = e.target.closest('[data-v]');
    if (!b) return;
    $$(`#${e.currentTarget.id} button`).forEach((x) => x.classList.toggle('on', x === b));
    show();
  };
  ['fxFind', 'fxTo', 'fxArtist'].forEach((k) => $('#' + k).addEventListener('input', show));
  if ($('#fxRecent')) $('#fxRecent').onclick = (e) => {
    const b = e.target.closest('[data-fxs]');
    if (!b) return;
    sample = recent[+b.dataset.fxs];
    $$('#fxRecent .chip').forEach((x) => x.classList.toggle('on', x === b));
    if (!$('#fxFind').value) $('#fxFind').value = { artist: sample.artist, title: sample.track, album: sample.album || '' }[read().field];
    show();
  };
  $('#fxNo').onclick = closeSheet;
  if ($('#fxDel')) $('#fxDel').onclick = () => { send({ t: 'fixDelete', id: r.id }); closeSheet(); };
  $('#fxYes').onclick = () => {
    const x = read();
    if (!x.find.trim()) { toast('Type what to look for'); return; }
    if (x.how === 'is' && !x.to.trim()) { toast('Say what the name should become'); return; }
    if (x.how === 'matches') { try { new RegExp(x.find); } catch (_) { toast('That isn\'t a regular expression the app can read'); return; } }
    send({ t: 'fixSave', rule: x });
    closeSheet();
  };
  show();
}

function renderSettings() {
  const l = ST.lastfm || {}, d = ST.discogs || {};
  $$('#ambSeg button').forEach((b) => b.classList.toggle('on', b.dataset.v === ambientMode()));
  renderServices();
  renderTidalSettings();
  renderSetlistSettings();
  renderFixSettings();
  const td = (STATS && STATS.tidy) || {};
  const more = td.count ? `${plural(td.count, 'track')} (${plural(td.plays, 'play')}) look like videos or apps.` : '';
  $('#tidyStatus').textContent = td.hiddenPlays ? `${plural(td.hiddenPlays, 'play')} hidden from your stats. ${more}`
    : more ? `${more} Hide them to keep your stats about music; nothing changes on Last.fm.`
      : 'Videos, app sounds and other non-music scrobbles can be hidden from your stats. Nothing changes on Last.fm.';
  const mode = showKeyForm || !l.hasKey ? 'setup' : l.connected ? 'connected' : l.waiting ? 'waiting' : l.needsAuth ? 'reconnect' : 'setup';
  const lfStatus = {
    connected: statusLine('ok', `Connected as <b>${esc(l.user)}</b>`, l.keyHint ? `API key ${l.keyHint}` : ''),
    waiting: statusLine('busy', 'Waiting for your approval', 'in the browser'),
    reconnect: statusLine('', 'Access expired', l.user ? `was ${l.user}` : ''),
    setup: statusLine(l.connected ? 'ok' : '', l.connected ? `Connected as <b>${esc(l.user)}</b>` : 'Not connected', l.connected ? 'Enter a new key to switch' : 'Scrobbles wait on this phone until you connect'),
  }[mode];
  $('#lfStatus').innerHTML = lfStatus;
  // With the app's own key, connecting is one tap; the key form is there for people who want their own.
  const quick = mode === 'setup' && l.builtIn && !showKeyForm;
  $('#lfQuick').hidden = !quick;
  $('#lfQuickBtn').disabled = !!l.busy;
  $('#lfSetup').hidden = mode !== 'setup' || quick;
  $('#lfWaiting').hidden = mode !== 'waiting';
  $('#lfConnected').hidden = mode !== 'connected';
  $('#lfReconnect').hidden = mode !== 'reconnect';
  $('#lfSave').disabled = !!l.busy;
  $('#lfSave').textContent = l.busy ? 'Connecting…' : 'Save and connect';
  $('#lfReconnectBtn').disabled = !!l.busy;
  $('#lfError').hidden = !l.error;
  $('#lfError').textContent = l.error || '';

  let dgStatus;
  if (d.connected && d.syncing) dgStatus = statusLine('busy', `Syncing <b>${esc(d.user)}</b>`, d.pages ? `${plural(d.done, 'record')}, page ${d.page} of ${d.pages}` : 'starting…');
  else if (d.connected) dgStatus = statusLine('ok', `Connected as <b>${esc(d.user)}</b>`, `${plural(d.count || 0, 'record')}${d.public ? ', public collection' : ''}${d.lastSync ? `, synced ${ago(d.lastSync)}` : ''}`);
  else if (d.syncing) dgStatus = statusLine('busy', 'Looking for your collection…', '');
  else dgStatus = statusLine('', 'Not connected', 'Your collection shows up on the shelf once connected');
  $('#dgStatus').innerHTML = dgStatus;
  $('#dgSetup').hidden = !!d.connected;
  $('#dgConnected').hidden = !d.connected;
  $('#dgSave').disabled = !!d.syncing;
  $('#dgUserSave').disabled = !!d.syncing;
  $('#dgSync').disabled = !!d.syncing;
  const prog = $('#dgProgress');
  prog.hidden = !d.syncing;
  prog.classList.toggle('indet', !d.pages);
  prog.style.setProperty('--p', d.pages ? `${Math.round((d.page / d.pages) * 100)}%` : '30%');
  $('#dgError').hidden = !d.error;
  $('#dgError').textContent = d.error || '';

  const def = ST.prefs.defaultTrackSec;
  $('#defLen').innerHTML = DEF_LENGTHS.map((s) => `<button class="chip ${s === def ? 'on' : ''}" data-sec="${s}">${fmtDur(s)}</button>`).join('');
  $$('#themeSeg button').forEach((b) => b.classList.toggle('on', b.dataset.v === (ST.prefs.theme || 'system')));
  $$('#lookSeg button').forEach((b) => b.classList.toggle('on', b.dataset.v === lookMode()));
  $$('#nowSeg button').forEach((b) => b.classList.toggle('on', b.dataset.v === nowStyle()));
  $$('#pageSeg button').forEach((b) => b.classList.toggle('on', b.dataset.v === (pageTabs() ? 'tabs' : 'long')));
  $$('#mainSeg button').forEach((b) => b.classList.toggle('on', b.dataset.v === (mainCompact() ? 'compact' : 'long')));
  $$('#statsSeg button').forEach((b) => b.classList.toggle('on', b.dataset.v === statsView()));
  const dv = ST.discover || {};
  $('#discoverSw').checked = dv.on !== false;
  $('#dvNo').hidden = !dv.no;
  $('#dvNoText').textContent = dv.no ? `${plural(dv.no, 'artist')} you said aren't for you stay out of the suggestions.` : '';
  try { $('#tiltSw').checked = localStorage.getItem('tilt') !== 'off'; } catch (_) { $('#tiltSw').checked = true; }
  $('#version').textContent = ST.version ? `Heavy Rotation ${ST.version}` : '';
  renderPhoneSettings();
}

// ---------- wiring ----------

function build() {
  watchLongPress();
  // The tab you're on goes back to its top.
  $('#tabs').onclick = (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.tab === tab) window.scrollTo({ top: 0, behavior: 'smooth' });
    else showTab(b.dataset.tab);
  };
  $('#settingsBtn').onclick = () => openSettings();
  $('#syncBtn').onclick = () => { send({ t: 'sync' }); toast('Syncing your collection…'); };
  document.addEventListener('click', (e) => {
    if (e.target.closest('#settingsPage [data-histcheck]')) { openHistoryCheck(); return; }
    if (e.target.closest('[data-hcfix]')) {
      HCHECK = { busy: true, step: 'Fetching the missing years…' };
      $('#hcBody').innerHTML = hcHtml();
      send({ t: 'historyCheck', repair: true });
      return;
    }
    const back = e.target.closest('[data-back]');
    if (back) { closePage(); return; }
    const flip = e.target.closest('[data-flip]');
    if (flip) { turnSleeve(flip); return; }
    if (e.target.closest('[data-tidy]')) { openTidy(); return; }
    if (e.target.closest('[data-hide-all]')) { hideAllSuggestions(); return; }
    const hide = e.target.closest('[data-hide],[data-unhide],[data-keep]');
    if (hide) { tidyAction(hide); return; }
    const ls = e.target.closest('[data-listen]');
    if (ls) { openListen(ls); return; }
    const pt = e.target.closest('[data-ptab]');
    if (pt) { tabTap(pt.closest('.page, .tab'), pt.dataset.ptab); return; }
    const pl = e.target.closest('[data-pl]');
    if (pl) { openPlaylist(pl.dataset.pl, undefined, pl); return; }
    const rk = e.target.closest('[data-ranks]');
    if (rk) { openRanks(rk.dataset.ranks, rk.dataset.rrange, rk.dataset, !!rk.dataset.rshown); return; }
    if (e.target.closest('[data-nowpage]')) { openNow(); return; }
    const tr = e.target.closest('[data-track]');
    if (tr) { openTrack(tr.dataset.a, tr.dataset.track, { album: tr.dataset.al, cover: tr.dataset.cover }); return; }
    const alb = e.target.closest('[data-album]');
    if (alb) { openAlbum(alb.dataset.a, alb.dataset.album, { cover: alb.dataset.cover, dz: alb.dataset.dz }); return; }
    const df = e.target.closest('[data-dfilter]');
    if (df) { discoFilter = df.dataset.dfilter; if (ARTIST && $('#discoSlot')) $('#discoSlot').innerHTML = discoHtml(ARTIST.name); return; }
    const fp = e.target.closest('[data-fpick]');
    if (fp) { openFilterSheet(fp.dataset.fpick); return; }
    const fc = e.target.closest('[data-fclear]');
    if (fc) { if (fc.dataset.fclear === 'decade') shelf.decade = 0; else shelf.genre = ''; renderShelf(); return; }
    const sg = e.target.closest('[data-statsgenre]');
    if (sg) { while (pageStack.length) closePage(); statsGenre = sg.dataset.statsgenre; showTab('stats'); return; }
    const gn = e.target.closest('[data-genre]');
    if (gn) { openGenre(gn.dataset.genre, gn.dataset.glabel); return; }
    if (e.target.closest('[data-genremore]')) { openGenreMore(); return; }
    const tn = e.target.closest('[data-topn]');
    if (tn) { const [k, n] = tn.dataset.topn.split(':'); setTopN(k, +n); return; }
    if (e.target.closest('[data-genremap]')) {
      while (pageStack.length) closePage();
      // Your taste is on every period's page but the day's.
      if (STATS && statsGroup(STATS) === 'day') statsRange = statsAsked = periodId('month', Date.now());
      statsGoTo = 'taste';
      showTab('stats');
      afterStats();
      return;
    }
    const atl = e.target.closest('[data-atlas]');
    if (atl) { openAtlas(atl.dataset.atlas || ''); return; }
    if (e.target.closest('[data-gpick]')) { openGenreSheet(); return; }
    if (e.target.closest('[data-gclear]')) { setStatsGenre(''); return; }
    if (e.target.closest('[data-dpick]')) { openDecadeSheet(); return; }
    if (e.target.closest('[data-dclear]')) { setStatsDecade(0); return; }
    const dc = e.target.closest('[data-decade]');
    if (dc) { openDecade(+dc.dataset.decade); return; }
    if (e.target.closest('[data-newlist]')) { openNewArtists(); return; }
    const la = e.target.closest('[data-lyrawake]');
    if (la) {
      const on = !lyricsAwake();
      try { localStorage.setItem('lyricsAwake', on ? 'on' : 'off'); } catch (_) { /* preview without storage */ }
      la.classList.toggle('on', on);
      la.setAttribute('aria-pressed', String(on));
      syncAwake();
      toast(on ? 'The screen stays on while the lyrics play' : 'The screen turns off as usual');
      return;
    }
    if (e.target.closest('[data-likethem]')) { openLikeThem(); return; }
    const gb = e.target.closest('[data-goblock]');
    if (gb) { goBlock(gb.dataset.goblock); return; }
    if (e.target.closest('#openVs')) { openVs(); return; }
    if (e.target.closest('#openConcerts')) { openConcerts(); return; }
    // A day or month from outside Stats (On this day on Explore): Stats for it.
    const dd = e.target.closest('[data-day]');
    if (dd && !e.target.closest('#statsBody, #blockBody')) {
      while (pageStack.length) closePage();
      statsRange = statsAsked = dd.dataset.day;
      showTab('stats');
      return;
    }
    const rz = e.target.closest('[data-rzoom]');
    if (rz) {
      const st = RECS[rz.dataset.rec];
      if (st) zoomRecord(rz.dataset.rec, rz.dataset.rzoom === 'in' ? (st.z || 1) * 1.8 : rz.dataset.rzoom === 'out' ? (st.z || 1) / 1.8 : 1);
      return;
    }
    const canvas = e.target.closest('canvas[data-record]');
    if (canvas) { if (Date.now() > recGesture.quietUntil) onRecordTap(canvas, e); return; }
    const ar = e.target.closest('[data-artist]');
    if (ar) { openArtist(ar.dataset.artist); return; }
    const show = e.target.closest('[data-show]');
    if (show) { openLivePlaylist(+show.dataset.show); return; }
    const cc = e.target.closest('[data-concert]');
    if (cc) { openConcertPlaylist(CONCERT_BY_ID[cc.dataset.concert]); return; }
    if (e.target.closest('[data-livepl]')) { openLivePlaylist(-1); return; }
    const go = e.target.closest('[data-go]');
    if (go) { openSettings(go.dataset.go); return; }
    const url = e.target.closest('[data-url]');
    if (url) openUrl(url.dataset.url);
  });
  $('#scrim').onclick = closeSheet;

  $('#sortBtn').onclick = openSortSheet;
  $('#filterBtn').onclick = openFilterChoice;
  $('#searchFab').onclick = openSearch;
  $('#gSearchClose').onclick = (e) => { e.preventDefault(); closeSearch(); };
  $('#gSearch').addEventListener('input', onSearchInput);
  $('#gSearch').addEventListener('keydown', (e) => { if (e.key === 'Enter') e.target.blur(); });
  $('#searchBody').onclick = (e) => {
    $('#gSearch').blur();
    const r = e.target.closest('.rec[data-id]');
    if (r) openRecord(+r.dataset.id, r);
  };
  $('#npPeek').onclick = () => { if (ST.spin) { openPage('spinPage'); renderSpin(true); } };
  $('#nowBody').addEventListener('click', (e) => {
    if (e.target.closest('[data-npapp]')) { const n = (ST.phone || {}).now; if (n) send({ t: 'openPhoneApp', pkg: n.pkg }); return; }
    const b = e.target.closest('[data-np]');
    if (b) { npControl(b.dataset.np); return; }
    if (e.target.closest('[data-sleep]')) { openSleepSheet(); return; }
    const q = e.target.closest('[data-npq]');
    if (q) { npControl('queue', +q.dataset.npq); return; }
    const lt = e.target.closest('[data-lt]');
    const n = (ST.phone || {}).now;
    if (lt && n && (n.media || {}).canSeek) { lyrUserUntil = 0; npControl('seek', +lt.dataset.lt); }
  });
  // Scrolling the lyrics yourself: they leave you there for a few seconds.
  for (const ev of ['touchstart', 'wheel']) {
    $('#nowBody').addEventListener(ev, (e) => { if (e.target.closest('.lyr-lines')) lyrUserUntil = Date.now() + 4000; }, { passive: true });
  }
  $('#nowBody').addEventListener('input', (e) => { if (e.target.id === 'npSeek') tickNow(); });
  $('#nowBody').addEventListener('change', (e) => { if (e.target.id === 'npSeek') npControl('seek', +e.target.value); });
  $('#nowStats').onclick = () => {
    const n = (ST.phone || {}).now;
    if (n) openTrack(n.artist, n.title, { album: n.album, cover: n.cover });
  };
  $('#grid').onclick = (e) => { const b = e.target.closest('.rec'); if (b) openRecord(+b.dataset.id, b); };
  $('#shelfEmpty').onclick = (e) => { if (e.target.closest('#emptySync')) send({ t: 'sync' }); };
  $('#spinSlot').onclick = (e) => { if (e.target.closest('#spinCard')) { openPage('spinPage'); renderSpin(true); } };
  $('#queueCard').onclick = (e) => { if (e.target.closest('#flushBtn')) send({ t: 'flush' }); };
  const openCompare = () => {
    openPage('comparePage');
    renderCompare();
    if (!CMP) send({ t: 'comparePano' });
  };
  $('#phoneCard').onclick = (e) => {
    if (e.target.closest('#phIntroNo')) { dismiss('phoneIntro'); renderPhoneCard(); return; }
    if (e.target.closest('#phAccessBtn2')) { send({ t: 'openNotifAccess' }); return; }
    if (e.target.closest('#cmpOpen')) { openCompare(); return; }
    const on = e.target.closest('[data-app-on]');
    if (on) { send({ t: 'setPhoneApp', pkg: on.dataset.appOn, on: true }); return; }
    const info = e.target.closest('[data-app-info]');
    if (info) send({ t: 'openAppInfo', pkg: info.dataset.appInfo });
  };
  $('#compareBody').onclick = (e) => { if (e.target.closest('#cmpRun')) send({ t: 'comparePano' }); };
  $('#statsHead').onclick = (e) => {
    if (e.target.closest('[data-histcheck]')) { openHistoryCheck(); return; }
    if (e.target.closest('#histImport')) send({ t: 'historyImport' });
    if (e.target.closest('#histStop')) send({ t: 'historyCancel' });
  };
  statsRange = periodId('month', Date.now());
  $('#periodKind').onclick = (e) => {
    const b = e.target.closest('[data-k]');
    if (!b || !STATS || STATS.empty) return;
    // Keep the moment in view: switching from October 2024 to Year shows 2024.
    const anchor = Math.min(STATS.range.to * 1000 - 1, Date.now());
    requestStats(periodId(b.dataset.k, Math.max(anchor, STATS.range.from * 1000)));
  };
  $('#statsFilter').onclick = openStatsFilter;
  const stepStats = (k) => {
    if (!STATS || STATS.empty) return;
    // Taps quicker than the answers: each goes on from the period asked for last, not the one on screen.
    const pending = statsAsked && statsAsked !== STATS.range.id;
    const id = pending ? stepPeriod(statsAsked, k, STATS.range.historyStart) : k < 0 ? STATS.range.prev : STATS.range.next;
    if (id) requestStats(id);
  };
  $('#pPrev').onclick = () => stepStats(-1);
  $('#pNext').onclick = () => stepStats(1);
  const statsBody = $('#statsBody');
  statsBody.addEventListener('pointerdown', (e) => onVizPointer(e, STATS));
  statsBody.addEventListener('pointermove', (e) => { if (e.buttons) onVizPointer(e, STATS); });
  const blockBody = $('#blockBody');
  blockBody.addEventListener('pointerdown', (e) => onVizPointer(e, STATS));
  blockBody.addEventListener('pointermove', (e) => { if (e.buttons) onVizPointer(e, STATS); });
  blockBody.addEventListener('input', (e) => { if (e.target.matches('[data-asearch]')) onArtistSearch(e.target); });
  const statsClick = (e) => {
    const bk = e.target.closest('[data-block]');
    if (bk) { openBlock(bk.dataset.block); return; }
    const sc = e.target.closest('[data-story]');
    if (sc) { openStory(sc.dataset.story); return; }
    if (e.target.closest('#arrangeBtn')) { openArrange(); return; }
    const day = e.target.closest('[data-day]');
    if (day) { if (isOpen('blockPage')) closePage(); requestStats(day.dataset.day); window.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    const ink = e.target.closest('[data-ink]');
    if (ink) { setSpot(ink.dataset.ink, +ink.dataset.k); return; }
    const pick = e.target.closest('[data-pick]');
    if (pick) {
      const input = pick.closest('.asearch').querySelector('input');
      const st = RECS[input.dataset.asearch];
      if (st) { st.spot = -1; setSpot(input.dataset.asearch, +pick.dataset.pick); }
      input.value = '';
      pick.closest('.sugg').hidden = true;
      return;
    }
    const rec = e.target.closest('[data-rec]');
    if (rec) { openRecord(+rec.dataset.rec, rec); return; }
    const tk = e.target.closest('[data-top]');
    if (tk) { topKind = tk.dataset.top; renderStats(); }
  };
  statsBody.onclick = statsClick;
  blockBody.onclick = statsClick;
  statsBody.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target.dataset.block) openBlock(e.target.dataset.block); });
  const artistBody = $('#artistBody');
  artistBody.addEventListener('pointerdown', (e) => onVizPointer(e, ARTIST));
  artistBody.onclick = (e) => { const rec = e.target.closest('[data-rec]'); if (rec) openRecord(+rec.dataset.rec, rec); };
  ['artistPage', 'albumPage', 'genrePage'].forEach((id) => {
    const p = $('#' + id);
    p.addEventListener('scroll', () => syncPageTabs(p), { passive: true });
    tabSwipe(p);
  });
  $('#trackBody').addEventListener('pointerdown', (e) => onVizPointer(e, TRACK));
  $('#albumBody').addEventListener('pointerdown', (e) => onVizPointer(e, ALBUM));
  $('#albumBody').onclick = (e) => { const rec = e.target.closest('[data-rec]'); if (rec) openRecord(+rec.dataset.rec, rec); };
  $('#genreBody').addEventListener('pointerdown', (e) => onVizPointer(e, GENRE));
  $('#genreBody').onclick = (e) => {
    const rec = e.target.closest('[data-rec]');
    if (rec) openRecord(+rec.dataset.rec, rec);
    if (e.target.closest('[data-genreall]')) { GENRE.showAll = true; renderGenre(); }
    if (e.target.closest('[data-genresubs]')) { GENRE.showSubs = true; renderGenre(); }
    if (e.target.closest('[data-genrepl]')) openGenrePlaylist(GENRE.key, GENRE.label);
  };
  watchBar(window, $('#appbar'), (away) => document.body.classList.toggle('bar-away', away));
  sheetDrag();
  Pill.init();
  syncBack();
  $$('.page').forEach((p) => { const bar = p.querySelector(':scope > .appbar'); if (bar) watchBar(p, bar, (away) => p.classList.toggle('bar-gone', away)); });
  $('#atlasSeg').onclick = (e) => { const b = e.target.closest('[data-av]'); if (b) { atlasView = b.dataset.av; renderAtlas(); } };
  $('#atlasBody').onclick = (e) => {
    const t = e.target;
    const z = t.closest('[data-uz]');
    if (z) { const f = z.dataset.uz; if (f === 'fit') { UNI.sel = -1; UNI.cluster = -1; uniCard(); $$('.uni-cons .chip').forEach((c) => c.classList.remove('on')); uniFly({ x: 0, y: 0, k: 1 }); } else uniZoomAt(UNI.w / 2, UNI.h / 2, f === 'in' ? 1.6 : 1 / 1.6); return; }
    const i = t.closest('[data-uni-i]');
    if (i) { const f = $('#uniFind'); if (f) { f.value = ''; $('#uniFound').innerHTML = ''; } uniSelect(+i.dataset.uniI); return; }
    const go = t.closest('[data-uni-go]');
    if (go) {
      const k = UNI.nodes.findIndex((n) => norm(n.name) === norm(go.dataset.uniGo));
      if (k >= 0) uniSelect(k); else openArtist(go.dataset.uniGo);
      return;
    }
    const c = t.closest('[data-uni-c]');
    if (c) { uniShowCluster(+c.dataset.uniC); return; }
    const y = t.closest('[data-uni-y]');
    if (y) { UNI.year = +y.dataset.uniY; $$('.uni-years .chip').forEach((b) => b.classList.toggle('on', +b.dataset.uniY === UNI.year)); uniCard(); drawUni(); return; }
    if (t.closest('[data-uni-x]')) { uniClear(); return; }
    const tr = t.closest('[data-tree]');
    if (tr) { const id = +tr.dataset.tree; if (TREE_OPEN.has(id)) TREE_OPEN.delete(id); else TREE_OPEN.add(id); renderAtlas(); return; }
    const ld = t.closest('[data-land]');
    if (ld && !t.closest('[data-artist]')) { const k = +ld.dataset.land; landSel = landSel === k ? -1 : k; renderAtlas(); }
  };
  const exploreClick = (e) => {
    const no = e.target.closest('[data-nope]');
    if (no) { discoverNope(no.dataset.nope); return; }
    const why = e.target.closest('[data-why]');
    if (why) { openWhy(why.dataset.why); return; }
    const rc = e.target.closest('[data-reach]');
    if (rc) { DISCOVER = { ...DISCOVER, reach: +rc.dataset.reach }; renderExplore(); renderForYou(); send({ t: 'discoverReach', v: +rc.dataset.reach }); return; }
    if (e.target.closest('[data-dmore]')) { if (mainCompact()) openForYou(); else { discoverAll = true; renderExplore(); } return; }
    if (e.target.closest('[data-dagain]')) { DISCOVER = null; send({ t: 'discover', again: true }); renderExplore(); return; }
    if (e.target.closest('#exploreGoStats')) showTab('stats');
  };
  $('#exploreBody').onclick = (e) => {
    const xl = e.target.closest('[data-xlist]');
    if (xl) { openXList(xl.dataset.xlist); return; }
    exploreClick(e);
  };
  $('#forYouBody').onclick = exploreClick;
  $('#activityBtn').onclick = () => openPage('activityPage');
  $('#concertsBody').onclick = (e) => {
    if (e.target.closest('#concertsRefresh')) send({ t: 'concertsRefresh' });
  };
  $('#svcChips').onclick = (e) => {
    const c = e.target.closest('[data-svc]');
    if (!c) return;
    const on = services();
    setServices(on.includes(c.dataset.svc) ? on.filter((k) => k !== c.dataset.svc) : Object.keys(SERVICES).filter((k) => k === c.dataset.svc || on.includes(k)));
    renderServices();
  };
  $('#artistLastfm').onclick = () => { if (ARTIST && ARTIST.name) openUrl(lastfmUrl(ARTIST.name)); };
  $('#artistHide').onclick = () => {
    const name = ARTIST && ARTIST.name;
    if (!name) return;
    confirmSheet(`Hide ${name}?`, 'All their plays leave your stats, stories and top lists. Nothing changes on Last.fm; Settings → Stats → Tidy up shows them again.', 'Hide', () => {
      send({ t: 'hide', kind: 'artist', artist: name });
      $('#statsBody').classList.add('stale');
      closePage();
      toast(`${name} is hidden from your stats`);
    });
  };
  $('#tiltSw').onchange = (e) => {
    try { localStorage.setItem('tilt', e.target.checked ? 'on' : 'off'); } catch (_) { /* preview without storage */ }
    Tilt.refresh();
  };
  // Moving covers follow a mouse, or a sideways drag on touch screens.
  document.addEventListener('pointermove', (e) => {
    const h = e.target.closest('[data-holo]');
    if (!h || (e.pointerType !== 'mouse' && !e.buttons)) {
      if (e.pointerType === 'mouse') Tilt.px = null;
      return;
    }
    const r = h.getBoundingClientRect();
    Tilt.px = Math.max(-1, Math.min(1, ((e.clientX - r.left) / r.width) * 2 - 1));
    Tilt.py = Math.max(-1, Math.min(1, ((e.clientY - r.top) / r.height) * 2 - 1));
  });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((t) => document.addEventListener(t, () => { Tilt.px = null; }));
  let resizeTimer = 0;
  window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(drawRecords, 150); });
  statsBody.addEventListener('input', (e) => { if (e.target.matches('[data-asearch]')) onArtistSearch(e.target); });
  // The story: tap right to go on, left to go back, hold to pause.
  const story = $('#story');
  let downAt = 0, holdTimer = 0;
  story.addEventListener('pointerdown', (e) => {
    if (e.target.closest('#storyClose')) return;
    downAt = performance.now();
    holdTimer = setTimeout(() => story.classList.add('paused'), 220);
  });
  story.addEventListener('pointerup', (e) => {
    clearTimeout(holdTimer);
    if (e.target.closest('#storyClose')) return;
    const held = performance.now() - downAt > 220;
    story.classList.remove('paused');
    if (held) return;
    if (e.clientX < story.clientWidth * 0.3) showSlide(storyIdx - 1);
    else if (storyIdx < storySlides.length - 1) showSlide(storyIdx + 1);
    else closeStory();
  });
  story.addEventListener('pointercancel', () => { clearTimeout(holdTimer); story.classList.remove('paused'); });
  $('#storyClose').onclick = closeStory;
  $('#phMode').onclick = (e) => { const b = e.target.closest('button'); if (b) setPhoneMode(b.dataset.v); };
  $('#phAccessBtn').onclick = () => send({ t: 'openNotifAccess' });
  $('#phAppInfo').onclick = () => send({ t: 'openAppInfo' });
  $('#phPanoInfo').onclick = () => send({ t: 'openAppInfo', pkg: 'com.arn.scrobble' });
  $('#phBattery').onclick = () => send({ t: 'openBattery' });
  $('#phApps').onchange = (e) => { const pkg = e.target.dataset.pkg; if (pkg) send({ t: 'setPhoneApp', pkg, on: e.target.checked }); };

  $('#recordBody').onclick = (e) => {
    if (e.target.closest('#scrobbleBtn')) return openScrobble();
    if (e.target.closest('#recordSpinCard')) { openPage('spinPage'); renderSpin(true); return; }
    const side = e.target.closest('[data-side]');
    if (side) return openScrobble({ side: side.dataset.side });
    const tr = e.target.closest('.track');
    if (tr) return openScrobble({ track: +tr.dataset.i });
    if (e.target.closest('#retryRel')) { delete REL[recordId]; send({ t: 'openRelease', id: recordId }); renderRecord(); return; }
    if (e.target.closest('#refreshLen')) send({ t: 'openRelease', id: recordId, refresh: true });
  };
  $('#recordDiscogs').onclick = $('#recordCredit').onclick = () => {
    const r = REL[recordId];
    openUrl((r && r.uri) || `https://www.discogs.com/release/${recordId}`);
  };

  $('#spinBody').onclick = (e) => {
    if (e.target.closest('#spFlip')) { send({ t: 'spinFlip' }); toast(`${sideLabel(ST.spin.nextSide)} started`); return; }
    if (e.target.closest('#spStop')) {
      confirmSheet('Stop scrobbling?', 'Tracks that have played at least halfway still count. The rest are dropped.', 'Stop', () => send({ t: 'spinStop' }));
      return;
    }
    const tr = e.target.closest('.track');
    if (!tr || !ST.spin) return;
    const it = ST.spin.items[+tr.dataset.i];
    if (!it || it.state === 'skipped' || +tr.dataset.i === lastCur) return;
    confirmSheet(`Playing “${it.title}” now?`, 'The tracks after it move to follow on from now. Earlier tracks that haven\'t counted yet are scrobbled as played.', 'Yes, it\'s playing', () => send({ t: 'spinResync', i: +tr.dataset.i }));
  };

  $('#tdSave').onclick = () => {
    const clientId = $('#tdId').value.trim(), secret = $('#tdSecret').value.trim();
    if (!clientId || !secret) { toast('Copy both the Client ID and the Client Secret from your app on the TIDAL dashboard.'); return; }
    showTidalForm = false;
    send({ t: 'saveTidal', clientId, secret });
    $('#tdSecret').value = '';
  };
  $('#tdLogin').onclick = () => send({ t: 'tidalLogin' });
  $('#tdSignOut').onclick = () => send({ t: 'tidalSignOut' });
  $('#tdChange').onclick = () => { showTidalForm = true; renderTidalSettings(); };
  $('#tdForget').onclick = () => confirmSheet('Disconnect TIDAL?', 'Heavy Rotation forgets your TIDAL app and login. Playlists already in TIDAL stay there.', 'Disconnect', () => send({ t: 'tidalForget' }));
  $('#tdCopy').onclick = () => {
    const text = (ST.tidal || {}).redirect || 'recordshelf://tidal';
    if (Native) send({ t: 'copyText', text, label: 'Redirect URI' });
    else if (navigator.clipboard) navigator.clipboard.writeText(text).then(() => toast('Copied'), () => toast(text));
  };
  $('#lfSave').onclick = () => {
    const key = $('#lfKey').value.trim(), secret = $('#lfSecret').value.trim();
    if (!/^[0-9a-f]{32}$/i.test(key) || !/^[0-9a-f]{32}$/i.test(secret)) {
      toast('The API key and the secret are both 32 letters and digits. Copy them again from Last.fm.');
      return;
    }
    send({ t: 'saveLastfm', key, secret });
    $('#lfKey').value = '';
    $('#lfSecret').value = '';
    showKeyForm = false;
  };
  $('#slSave').onclick = () => {
    const key = $('#slKey').value.trim();
    if (key.length < 20) { toast('That looks too short for a setlist.fm API key. Copy it again from setlist.fm.'); return; }
    send({ t: 'saveSetlist', key });
    $('#slKey').value = '';
  };
  $('#slUserSave').onclick = () => { send({ t: 'saveSetlistUser', user: $('#slUser').value.trim() }); $('#slUser').blur(); };
  $('#slUserRefresh').onclick = () => send({ t: 'concertsRefresh' });
  $('#slForget').onclick = () => confirmSheet('Remove the setlist.fm key?', 'Artist pages stop showing setlists until you add a key again.', 'Remove', () => send({ t: 'setlistForget' }));
  $('#fxPresets').onchange = (e) => { const id = e.target.dataset.fxp; if (id) send({ t: 'fixPreset', id, on: e.target.checked }); };
  $('#fxRules').onclick = (e) => { const b = e.target.closest('[data-fx]'); if (b) openFixSheet(b.dataset.fx); };
  $('#fxAdd').onclick = () => openFixSheet('');
  $('#rankBody').onclick = (e) => {
    const k = e.target.closest('[data-rkind]');
    if (k && RANKS && k.dataset.rkind !== RANKS.kind) {
      RANKS = { ...RANKS, kind: k.dataset.rkind, rows: {}, total: 0, pos: -1, loading: true, req: ++REQ, scrolled: false, more: -1 };
      renderRanks();
      askRanks(0, true);
      return;
    }
    const g = e.target.closest('[data-rgap]');
    if (g && RANKS && RANKS.more < 0) {
      RANKS.more = +g.dataset.rgap;
      renderRanks();
      askRanks(RANKS.more, false);
    }
  };
  $('#lfCheck').onclick = () => send({ t: 'lastfmCheck' });
  $('#lfReopen').onclick = () => send({ t: 'lastfmConnect' });
  $('#lfReconnectBtn').onclick = () => send({ t: 'lastfmConnect' });
  $('#lfDisconnect').onclick = () => confirmSheet('Disconnect Last.fm?', 'New scrobbles will wait on this phone until you connect again.', 'Disconnect', () => send({ t: 'lastfmDisconnect' }));
  $('#lfChangeKey').onclick = () => { showKeyForm = true; renderSettings(); };
  $('#lfQuickBtn').onclick = () => send({ t: 'lastfmConnect' });
  $('#lfOwnKey').onclick = () => { showKeyForm = true; renderSettings(); };
  $('#dgUserSave').onclick = () => {
    const user = $('#dgUser').value.trim().replace(/^@/, '');
    if (!user) { toast('Type your Discogs username.'); return; }
    send({ t: 'saveDiscogs', user });
  };
  $('#dgSave').onclick = () => {
    const token = $('#dgToken').value.trim();
    if (token.length < 20) { toast('That looks too short for a Discogs token. Copy it again.'); return; }
    send({ t: 'saveDiscogs', token });
    $('#dgToken').value = '';
  };
  $('#dgSync').onclick = () => send({ t: 'sync' });
  $('#dgDisconnect').onclick = () => confirmSheet('Disconnect Discogs?', 'Your shelf will be empty until you connect again. Your scrobbles are not affected.', 'Disconnect', () => send({ t: 'discogsDisconnect' }));
  $('#defLen').onclick = (e) => { const b = e.target.closest('[data-sec]'); if (b) send({ t: 'setDefaultTrack', sec: +b.dataset.sec }); };
  $('#themeSeg').onclick = (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    try { localStorage.setItem('reopenSettings', String(Date.now())); } catch (_) { /* preview without storage */ }
    send({ t: 'setTheme', v: b.dataset.v });
  };
  $('#nowSeg').onclick = (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    try { localStorage.setItem('nowStyle', b.dataset.v); } catch (_) { /* preview without storage */ }
    renderSpinCard();
    renderSettings();
  };
  $('#mainSeg').onclick = (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    // Stats keeps its own choice from now on (see statsView).
    statsView();
    try { localStorage.setItem('mainLayout', b.dataset.v); } catch (_) { /* preview without storage */ }
    renderSettings();
    renderExplore();
  };
  $('#statsSeg').onclick = (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    try { localStorage.setItem('statsView', b.dataset.v); } catch (_) { /* preview without storage */ }
    renderSettings();
    renderStats();
  };
  $('#discoverSw').onchange = (e) => {
    const on = e.target.checked;
    ST.discover = { ...(ST.discover || {}), on };
    DISCOVER = null;
    send({ t: 'discoverOn', v: on });
    renderExplore();
  };
  $('#dvClear').onclick = () => { send({ t: 'discoverClearNo' }); toast('They can be suggested again'); };
  $('#pageSeg').onclick = (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    try { localStorage.setItem('pageLayout', b.dataset.v); } catch (_) { /* preview without storage */ }
    renderSettings();
    renderArtist();
    renderAlbum();
    renderGenre();
  };
  $('#lookSeg').onclick = (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    try { localStorage.setItem('look', b.dataset.v); } catch (_) { /* preview without storage */ }
    document.documentElement.dataset.look = lookMode();
    renderSettings();
  };
  $('#bkExport').onclick = () => {
    const ui = {};
    try { for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); ui[k] = localStorage.getItem(k); } } catch (_) { /* preview without storage */ }
    send({ t: 'backupExport', ui });
  };
  $('#bkImport').onclick = () => {
    openSheet(`<div class="sheet-title">Restore a backup?</div>
      <p class="body">The backup replaces this app's history, hide list, activity, collection and settings. Heavy Rotation starts again afterwards; then connect Last.fm (and TIDAL) again.</p>
      <div class="btn-row"><button class="btn filled" id="bkGo">Choose the backup file</button></div>`);
    $('#sheetContent').onclick = (e) => { if (e.target.closest('#bkGo')) { closeSheet(); send({ t: 'backupImport' }); } };
  };
  $('#ambSeg').onclick = (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    try { localStorage.setItem('ambient', b.dataset.v); } catch (_) { /* preview without storage */ }
    applyAmbient();
    renderSettings();
  };
  applyAmbient();

  try {
    const s = JSON.parse(localStorage.getItem('shelfSort') || 'null');
    if (s && SORTS.some((x) => x.id === s.sort)) { shelf.sort = s.sort; shelf.rev = !!s.rev; }
    const v = localStorage.getItem('shelfView');
    if (SHELF_VIEWS.some(([x]) => x === v)) shelf.view = v;
  } catch (_) { /* preview without storage */ }
  $('#shelfViews').onclick = (e) => {
    const b = e.target.closest('[data-view]');
    if (!b) return;
    shelf.view = b.dataset.view;
    try { localStorage.setItem('shelfView', shelf.view); } catch (_) { /* preview without storage */ }
    renderShelf();
  };
  setInterval(tickUi, 1000);
  renderShelf();
}

document.addEventListener('DOMContentLoaded', () => {
  build();
  if (!Native) Mock.init();
  send({ t: 'hello' });
});
