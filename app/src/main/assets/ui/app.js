'use strict';

/*
 * UI for Record Shelf (Material You). Inside the app, window.ShelfNative is the bridge to the
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
const shelf = { q: '', sort: 'added', rev: false, decade: 0, genre: '', view: 'grid' };
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
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const norm = (s) => String(s || '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '');
const pad = (n) => String(n).padStart(2, '0');
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;

function fmtDur(sec) {
  const s = Math.max(0, Math.round(sec));
  const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
  return h ? `${h}:${pad(m)}:${pad(x)}` : `${m}:${pad(x)}`;
}
const fmtLong = (sec) => (sec >= 3600 ? `${Math.floor(sec / 3600)} h ${Math.round(sec / 60) % 60} min` : `${Math.max(1, Math.round(sec / 60))} min`);
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
  const img = url ? `<img src="${esc(url)}" alt="" loading="lazy" decoding="async" onerror="this.remove()">` : '';
  const eq = playing ? '<span class="eq"><i></i><i></i><i></i></span>' : '';
  return `<div class="cover ${cls}" style="--h:${hue((title || '') + (artist || ''))}"><span>${ini}</span>${img}${eq}</div>`;
}

/**
 * A big cover that tilts with the phone (see Tilt). Records on your shelf ([opts.owned]) are the
 * collector's edition: holo foil, a foil sticker and the record sliding out of the sleeve. Every
 * other cover tilts and catches the light, nothing more.
 */
function holoCover(url, title, artist, opts = {}) {
  const owned = !!opts.owned;
  const withDisc = owned && opts.disc !== false;
  const lbl = url ? `background-image:url('${esc(url)}')` : '';
  const disc = withDisc ? `<div class="disc"><div class="vinyl"><i class="lbl" style="--h:${hue((title || '') + (artist || ''))};${lbl}"></i></div></div>` : '';
  const foil = owned ? '<i class="sheen"></i><i class="sticker" aria-label="On your shelf">★</i>' : '';
  const back = opts.back ? '<div class="sleeve-back"></div>' : '';
  return `<div class="holo ${owned ? 'foil' : 'plain'} ${withDisc ? 'with-disc' : ''} ${opts.frame ? 'framed' : ''}" data-holo ${opts.back ? 'data-flip' : ''} ${opts.rel ? `data-rel="${opts.rel}"` : ''} data-key="${esc(opts.key || url || title)}" data-cover="${esc(url || '')}" style="--h:${hue((title || '') + (artist || ''))}">
    <div class="h3d">${disc}<div class="sleeve">${coverHtml(url, title, artist, 'big')}${foil}<i class="glare"></i></div>${back}</div></div>`;
}

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

let toastTimer = 0;
function toast(msg) {
  const t = $('#snackbar');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 3200);
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

window.onState = (s) => {
  const hadSpin = !!(ST.spin);
  ST = s;
  renderState();
  applyAmbient();
  vsStateTick();
  if (!hadSpin && s.spin && pendingSpinOpen) { pendingSpinOpen = false; openPage('spinPage'); renderSpin(true); }
};
window.onCollection = (c) => {
  COLL = c.map((r) => Object.assign(r, {
    _s: norm([r.title, r.artist, r.label, r.year, (r.styles || []).join(' '), (r.genres || []).join(' ')].join(' ')),
    _artist: String(r.artist || '').replace(/^the\s+/i, ''),
  }));
  renderShelf();
  if (isOpen('recordPage')) renderRecord();
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
window.onStats = (s) => { STATS = s; $('#statsBody').classList.remove('stale'); renderStats(); if (isOpen('settingsPage')) renderSettings(); };
window.onOpenSpin = () => { if (ST.spin) { openPage('spinPage'); renderSpin(true); } };
window.onTheme = (th) => {
  const root = document.documentElement;
  root.dataset.theme = th.dark ? 'dark' : 'light';
  root.style.colorScheme = th.dark ? 'dark' : 'light';
  for (const k in th.c) root.style.setProperty('--md-' + k, th.c[k]);
  drawRecords();
};
window.onInsets = (i) => {
  const root = document.documentElement.style;
  root.setProperty('--inset-top', `${i.top || 0}px`);
  root.setProperty('--inset-bottom', `${i.bottom || 0}px`);
  root.setProperty('--ime', `${i.ime || 0}px`);
};
window.onBack = () => {
  if (!$('#story').hidden) { closeStory(); return true; }
  if (!$('#sheet').hidden) { closeSheet(); return true; }
  if (pageStack.length) { closePage(); return true; }
  if (tab !== 'shelf') { showTab('shelf'); return true; }
  if (shelf.q) { $('#search').value = ''; shelf.q = ''; renderShelf(); return true; }
  return false;
};

// ---------- navigation ----------

function showTab(name) {
  tab = name;
  $$('.tab').forEach((t) => t.classList.toggle('active', t.id === 'tab-' + name));
  $$('#tabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === name));
  $('#appbarTitle').textContent = { shelf: 'Shelf', stats: 'Stats', explore: 'Explore', activity: 'Activity' }[name];
  window.scrollTo(0, 0);
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
  playlistPage: { get: () => [PLAYLIST, TIDALJOB], set: (s) => { [PLAYLIST, TIDALJOB] = s; renderPlaylist(); } },
  vsPage: { get: () => VS, set: (s) => { VS = s; renderVs(); } },
  atlasPage: { get: () => atlasView, set: (s) => { atlasView = s; renderAtlas(); } },
  genrePage: { get: () => GENRE, set: (s) => { GENRE = s; renderGenre(); } },
};
/** Per back-stack entry: what a page further down showed (state and scroll), while another of its kind is on top. */
const pageSaved = [];

/** Opens a page on top. Callers set the page's state after this, so the one below can be remembered first. */
function openPage(id) {
  const p = $('#' + id);
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
  if (bar) bar.classList.remove('away', 'scrolled');
  Tilt.refresh();
}

/**
 * Top bars step aside while you scroll down and come back as soon as you scroll up; over
 * scrolled content they fade into the page instead of cutting it off.
 */
function watchBar(scroller, bar, onAway) {
  const el = scroller === window ? document.scrollingElement : scroller;
  let last = 0;
  scroller.addEventListener('scroll', () => {
    const y = el.scrollTop;
    bar.classList.toggle('scrolled', y > 4);
    const d = y - last;
    if (Math.abs(d) < 8 && y > 4) return;
    const away = d > 0 && y > 90;
    if (away !== bar.classList.contains('away')) {
      bar.classList.toggle('away', away);
      if (onAway) onAway(away);
    }
    last = y;
  }, { passive: true });
}
function closePage() {
  const id = pageStack.pop();
  pageSaved.length = pageStack.length;
  if (!id) return;
  const p = $('#' + id);
  const i = pageStack.lastIndexOf(id);
  const saved = i >= 0 ? pageSaved[i] : null;
  if (saved) {
    // The same kind of page further down: show what it showed, where it was.
    pageSaved[i] = undefined;
    PAGE_STATE[id].set(saved.state);
    p.style.zIndex = 10 + i + 1;
    p.scrollTop = saved.scroll;
  } else if (i < 0) p.hidden = true;
  if (id === 'settingsPage') showKeyForm = false;
  Tilt.refresh();
}
/** An answer for a page that may have been covered meanwhile: [apply] it to whichever state asked ([req]). */
let REQ = 0;
function deliver(pageId, req, current, apply) {
  if (current && current.req === req) return apply(current, true);
  pageStack.forEach((pid, i) => {
    const s = pageSaved[i];
    if (pid === pageId && s && s.state && s.state.req === req) s.state = apply(s.state, false);
  });
  return current;
}
function openSettings(section) {
  openPage('settingsPage');
  renderSettings();
  if (section) setTimeout(() => { const s = $('#' + section); if (s) $('#settingsPage').scrollTop = s.offsetTop - 72; }, 30);
}

function openSheet(html) {
  $('#sheetContent').innerHTML = html;
  $('#sheet').hidden = false;
}
function closeSheet() {
  $('#sheet').hidden = true;
  $('#sheetContent').innerHTML = '';
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
function renderSpinCard() {
  const sp = ST.spin;
  const slot = $('#spinSlot');
  if (!sp) { renderPhoneNow(slot); return; }
  const sig = sp.id + '|' + sp.cover;
  if (sig !== spinCardSig) {
    spinCardSig = sig;
    slot.innerHTML = `<button class="spin-card" id="spinCard">${coverHtml(sp.cover, sp.album, sp.artist, 'small')}
      <div class="txt"><small id="scLabel"></small><b id="scTitle"></b><span>${esc(sp.artist)} — ${esc(sp.album)}</span></div>
      <span class="eq plain"><i></i><i></i><i></i></span><div class="bar"><i id="scBar"></i></div></button>`;
  }
  tickUi();
}

/**
 * With no record spinning, the same card shows what a music app on the phone plays (TIDAL and
 * the like), with its cover; a tap opens the song's page.
 */
function renderPhoneNow(slot) {
  const n = (ST.phone || {}).now;
  if (!n || !n.title) { spinCardSig = ''; slot.innerHTML = ''; return; }
  const sig = ['p', n.pkg, n.artist, n.title, n.cover || '', n.playing].join('|');
  if (sig === spinCardSig) return;
  spinCardSig = sig;
  slot.innerHTML = `<button class="spin-card phone" ${trackAttrs(n.artist, n.title, { album: n.album, cover: n.cover })}>${coverHtml(n.cover, n.album || n.title, n.artist, 'small')}
    <div class="txt"><small>${n.playing ? 'Now playing' : 'Paused'} · ${esc(n.app)}</small><b>${esc(n.title)}</b><span>${esc(n.artist)}${n.album ? ` — ${esc(n.album)}` : ''}</span></div>
    ${n.playing ? '<span class="eq plain"><i></i><i></i><i></i></span>' : '<span></span>'}<div class="bar"><i id="phNowBar"></i></div></button>`;
  tickPhone();
}

let lastCur = -2;
/** Once a second while a record spins: progress, times and which track is current. */
function tickUi() {
  tickPhone();
  const sp = ST.spin;
  if (!sp) return;
  const now = Date.now();
  const n = spinNow(sp, now);
  const it = n.item;
  const pct = it ? Math.min(100, ((now - it.start) / (it.dur * 1000)) * 100) : 0;
  const left = it ? (it.start + it.dur * 1000 - now) / 1000 : 0;
  if ($('#scTitle')) {
    $('#scTitle').textContent = it ? it.title : n.next ? n.next.title : sp.album;
    $('#scLabel').textContent = it ? `Now spinning${it.side ? ' · ' + sideLabel(it.side) : ''} · ${fmtDur(left)} left` : n.next ? `Up next at ${fmtTime(n.next.start)}` : 'Now spinning';
    $('#scBar').style.width = pct + '%';
  }
  if (isOpen('spinPage') && $('#spTitle')) {
    tickTurntable(n, now);
    $('#spTitle').textContent = it ? it.title : n.next ? n.next.title : sp.album;
    $('#spSide').textContent = it ? [it.side ? sideLabel(it.side) : '', `${n.live.indexOf(it) + 1} of ${n.live.length}`].filter(Boolean).join(' · ') : n.next ? `Starts at ${fmtTime(n.next.start)}` : '';
    $('#spBar').style.width = pct + '%';
    $('#spElapsed').textContent = it ? fmtDur((now - it.start) / 1000) : '';
    $('#spLeft').textContent = it ? '−' + fmtDur(left) : '';
    $('#spEnd').textContent = n.end > now ? `ends about ${fmtTime(n.end)}` : '';
    if (n.cur !== lastCur) renderSpinList();
  }
}

// ---------- shelf ----------

function sorted() {
  const q = norm(shelf.q.trim());
  const s = SORTS.find((x) => x.id === shelf.sort) || SORTS[0];
  const list = (q ? COLL.filter((r) => q.split(/\s+/).every((w) => r._s.includes(w))) : COLL.slice()).filter((r) => shelfFilterOk(r));
  list.sort(s.cmp);
  if (shelf.rev) list.reverse();
  return list;
}

function renderShelf() {
  $('#sortChips').innerHTML = SORTS.map((s) => {
    const on = shelf.sort === s.id;
    return `<button class="chip ${on ? 'on' : ''}" data-sort="${s.id}">${on ? icon('flip') : ''}${s.labels[on && shelf.rev ? 1 : 0]}</button>`;
  }).join('');
  const list = sorted();
  shownCount = list.length;
  const spinId = ST.spin && ST.spin.releaseId;
  $('#shelfTools').hidden = !COLL.length;
  $('#shelfCount').textContent = shelf.q.trim() || shelfFiltered() ? `${list.length} of ${plural(COLL.length, 'record')}` : plural(COLL.length, 'record');
  renderFilterChips();
  $('#shelfSummary').innerHTML = shelfSummaryHtml(list);
  $('#shelfViews').innerHTML = SHELF_VIEWS.map(([v, ic, label]) => `<button class="icon-btn small${shelf.view === v ? ' on' : ''}" data-view="${v}" aria-label="${label}">${icon(ic)}</button>`).join('');
  $('#grid').className = `grid ${shelf.view}`;
  $('#grid').innerHTML = list.map((r) => {
    const cover = coverHtml(shelf.view === 'list' ? r.thumb || r.cover : r.cover || r.thumb, r.title, r.artist, '', r.id === spinId);
    if (shelf.view === 'list') {
      const meta = [r.year || '', (r.label || '').split(' · ')[0], (r.format || '').split(',')[0]].filter(Boolean).join(' · ');
      const styles = (r.styles || []).slice(0, 2).map((x) => `<span class="mini-chip">${esc(x)}</span>`).join('');
      return `<button class="rec" data-id="${r.id}">${cover}<span class="lmain"><b>${esc(r.title)}</b><small>${esc(r.artist)}</small><small class="meta">${esc(meta)}</small>${styles ? `<span class="lchips">${styles}</span>` : ''}</span>
        <span class="lplays"><b>${r.plays ? compact(r.plays) : '–'}</b><small>${r.plays === 1 ? 'play' : 'plays'}</small></span></button>`;
    }
    let meta = '';
    if (shelf.sort === 'plays' || shelf.view === 'big') meta = `<small class="meta">${[shelf.view === 'big' && r.year ? r.year : '', r.plays ? plural(r.plays, 'play') : 'no plays yet'].filter(Boolean).join(' · ')}</small>`;
    else if (shelf.sort === 'year' && r.year) meta = `<small class="meta">${r.year}</small>`;
    return `<button class="rec" data-id="${r.id}">${cover}<b>${esc(r.title)}</b><small>${esc(r.artist)}</small>${meta}</button>`;
  }).join('');
  renderShelfEmpty();
}

let shownCount = 0;
function renderShelfEmpty() {
  const d = ST.discogs || {};
  const e = $('#shelfEmpty');
  let h = '';
  if (!COLL.length && d.connected && d.syncing) h = `${icon('album', 'i big')}<p>Loading your collection…${d.done ? `<br>${plural(d.done, 'record')} so far` : ''}</p>`;
  else if (!COLL.length && d.connected) h = `${icon('album', 'i big')}<p>No records yet.<br>Add some to your Discogs collection, then sync.</p><button class="btn tonal" id="emptySync">${icon('sync')}Sync now</button>`;
  else if (COLL.length && !shownCount && shelf.q.trim()) h = `<p>No records match “${esc(shelf.q.trim())}”</p>`;
  e.innerHTML = h;
  e.hidden = !h;
}

// ---------- record page ----------

function openRecord(id) {
  openPage('recordPage');
  recordId = id;
  const r = REL[id];
  if (!r || r.error || !r.tracks) send({ t: 'openRelease', id });
  renderRecord();
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
  const meta = [info.year || '', info.label, info.format].filter(Boolean).join(' · ');
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
      <span class="flip-hint">Tap the sleeve to turn it over</span>
      <h1>${esc(title)}</h1>
      <div class="artist"><button class="artist-link" data-artist="${esc(artist)}">${esc(artist)}</button></div>
      <div class="meta">${esc(meta)}</div>
      <div class="stats">${plays ? `<button class="chip link" data-url="${esc(lastfmUrl(artist, title))}">${plural(plays, 'play')}${rel && rel.tracks.length && plays >= rel.tracks.length ? ` · ≈${Math.round(plays / rel.tracks.length)}× through` : ' on Last.fm'}${icon('open')}</button>` : ''}${spins ? `<span class="chip static">${plural(spins, 'spin')} logged here</span>` : ''}${(ST.history || {}).count ? `<button class="chip link" ${albumAttrs(artist, title)} data-cover="${esc(coverUrl || '')}">${icon('chart')}Your stats</button>` : ''}</div>
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
      h += `<div class="side-head"><h2>${esc(sideLabel(g.side))}</h2><span class="sub">${plural(g.idx.length, 'track')} · ${fmtLong(dur)}</span><span class="grow"></span>
        ${g.side ? `<button class="btn text small" data-side="${esc(g.side)}">${icon('play')}Scrobble</button>` : ''}</div>
        <div class="list">${g.idx.map((i) => trackRow(rel, i)).join('')}</div>`;
    }
    const srcs = [...new Set(rel.tracks.map((t) => t.src).filter(Boolean))].map((s) => SRC_NAMES[s] || s);
    const unknown = rel.tracks.filter((t) => !(t.dur > 0)).length;
    h += `<div class="note">${srcs.length ? `Track lengths from ${srcs.join(' and ')}.` : 'No source knows the track lengths.'}
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
    const chip = (v, label, sub) => `<button class="chip ${st.what === v ? 'on' : ''}" data-what="${esc(v)}">${st.what === v ? icon('check') : ''}${esc(label)}${sub ? ` <small>${sub}</small>` : ''}</button>`;
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
        ${[['now', 'Starting now'], ['at', 'Earlier'], ['finished', 'Just finished']].map(([v, l]) => `<button data-v="${v}" class="${st.when === v ? 'on' : ''}">${icon('check', 'i check')}${l}</button>`).join('')}
      </div>`;
    if (st.when === 'at') h += `<label class="field"><span>Started at</span><input type="datetime-local" id="atInput" value="${toLocalInput(st.at)}"></label>`;

    let blocked = !plan;
    if (plan) {
      const n = sel.length;
      let sub;
      if (st.when === 'now') sub = `Until about ${fmtTime(plan.end)}. Each track is scrobbled once half of it has played, and shows as now playing on Last.fm.`;
      else if (plan.end > now) sub = `${whenLabel(plan.start)} – ${fmtTime(plan.end)}. Tracks that have played are scrobbled now, the rest as they play.`;
      else sub = `${whenLabel(plan.start)} – ${fmtTime(plan.end)}. Scrobbled right away, each at the time it played.`;
      h += `<div class="summary"><b>${plural(n, 'track')} · ${fmtLong(plan.total / 1000)}</b><small>${sub}</small></div>`;
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
      <div class="artist">${esc(sp.artist)} — ${esc(sp.album)}</div>
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
    const sub = it.state === 'skipped' ? 'Not played' : now ? `${fmtTime(it.start)} · playing${it.state === 'scrobbled' ? ', scrobbled' : ''}` : it.state === 'scrobbled' ? `${fmtTime(it.start)} · scrobbled` : `${fmtTime(it.start)}${it.est ? ' · length guessed' : ''}`;
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
    <span class="grow"><b>${esc(e.track)}</b><small>${esc(e.artist)}${e.album ? ' · ' + esc(e.album) : ''}</small>${why}</span>
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
        <p>Record Shelf can take over from Pano Scrobbler. Start with shadow mode: it only logs what it would scrobble, so you can compare it with Pano first.</p>
        <div class="btn-row"><button class="btn filled" data-go="phSection">Set up</button><button class="btn tonal" id="phIntroNo">Not now</button></div></div>`;
    }
  } else if (!p.access) {
    h = `<div class="card warn"><div class="card-title">${icon('phone')}Phone scrobbling needs access</div>
      <p>Allow notification access so Record Shelf can see what music apps play. Nothing is scrobbled from the phone until then.</p>
      <div class="btn-row"><button class="btn tonal" id="phAccessBtn2">Allow access</button></div></div>`;
  } else {
    const n = p.now;
    const mode = p.mode === 'shadow' ? `<span class="chip static mode">${icon('eye')}Shadow</span>` : `<span class="chip static mode on">${icon('check')}Scrobbling</span>`;
    if (n) {
      h = `<div class="card phone-now"><div class="pn-top"><span class="chip static">${esc(n.app)}</span>${mode}</div>
        <b>${esc(n.title)}</b><small>${esc(n.artist)}${n.album ? ' · ' + esc(n.album) : ''}</small>
        <div class="bar"><i id="pnBar"></i></div><small class="pn-text" id="pnText"></small>
        ${n.on ? '' : `<button class="btn tonal small" data-app-on="${esc(n.pkg)}">Scrobble ${esc(n.app)}</button>`}</div>`;
    } else {
      h = `<div class="listen-line">${icon('phone')}<span>Waiting for music on this phone</span>${mode}</div>`;
    }
    if (p.mode === 'shadow') {
      const sum = CMP ? `${caughtPct(CMP)}% of what Pano sent to Last.fm was caught here; ${plural(CMP.onlyTheirs.length, 'track')} missed. Checked ${ago(CMP.at)}.`
        : 'After a day or two of listening, compare what Record Shelf logged with what Pano sent to Last.fm.';
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
  else t = `Counts in ${fmtDur(left / 1000)}${n.playing ? '' : ' · paused'}`;
  text.textContent = t;
}

function renderCompare() {
  const p = ST.phone || {};
  const c = CMP;
  let h = '';
  if (p.comparing) h += `<div class="lin inline indet"><i></i></div><p class="hint">Reading your scrobbles from Last.fm…</p>`;
  if (p.compareError) h += `<div class="card bad"><p>${esc(p.compareError)}</p></div>`;
  if (!c) {
    h += `<div class="empty">${icon('compare', 'i big')}<p>Nothing compared yet.<br>This reads what reached Last.fm while shadow mode was on and puts it next to what Record Shelf logged.</p></div>`;
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
    const row = (x, sub) => `<div class="cmp-row"><span class="t">${esc(dayLabel(x.ts * 1000))}<br>${esc(fmtTime(x.ts * 1000))}</span><span class="w"><div>${esc(x.artist)} — ${esc(x.title)}</div>${sub ? `<small>${esc(sub)}</small>` : ''}</span></div>`;
    if (c.onlyTheirs.length) {
      h += `<div class="section-head"><h2>Only on Last.fm</h2></div>
        <p class="hint">Pano (or another scrobbler) sent these; Record Shelf didn't log them. Was the app switched on here, or the track skipped early?</p>
        <div class="list">${c.onlyTheirs.slice().sort((a, b) => b.ts - a.ts).map((x) => row(x)).join('')}</div>`;
    }
    if (c.renamed.length) {
      h += `<div class="section-head"><h2>Named differently</h2></div>
        <p class="hint">Same time, different names. Pano may clean up titles with its edit rules.</p>
        <div class="list">${c.renamed.slice().sort((a, b) => b.ours.ts - a.ours.ts).map((r) => row(r.theirs, `here: ${r.ours.artist} — ${r.ours.title}`)).join('')}</div>`;
    }
    if (c.onlyOurs.length) {
      h += `<div class="section-head"><h2>Only here</h2></div>
        <p class="hint">Record Shelf would have scrobbled these, but Last.fm has nothing at that time.</p>
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
  if (v !== 'off' && !p.access) toast('Now allow notification access for Record Shelf');
}

function renderPhoneSettings() {
  const p = ST.phone || {};
  const mode = p.mode || 'off';
  $$('#phMode button').forEach((b) => b.classList.toggle('on', b.dataset.v === mode));
  let st;
  if (mode === 'off') st = statusLine('', 'Off', 'Nothing on this phone is scrobbled by Record Shelf');
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
  if (n >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
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
  const d = new Date(r.from * 1000);
  switch (r.kind) {
    case 'week': {
      const e = new Date(d);
      e.setDate(e.getDate() + 6);
      return `${d.toLocaleDateString([], { day: 'numeric', month: 'short' })} – ${e.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' })}`;
    }
    case 'month': return d.toLocaleDateString([], { month: 'long', year: 'numeric' });
    case 'year': return String(d.getFullYear());
    case '12m': return 'Last 12 months';
    default: return 'All time';
  }
}
const periodWord = (kind) => ({ week: 'week', month: 'month', year: 'year', '12m': 'year' }[kind] || 'time');
const ofThe = (kind, what) => (kind === 'all' ? `${what} of all time` : `${what} of the ${periodWord(kind)}`);
const lastWord = (kind) => ({ week: 'last week', month: 'last month', year: 'last year', '12m': 'the year before' }[kind] || 'before');

const isoDate = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
/** The id of the [kind] period that contains [ms] (see Stats.range in Kotlin). */
function periodId(kind, ms) {
  const d = new Date(ms);
  if (kind === 'week') return 'w' + isoDate(new Date(d.getFullYear(), d.getMonth(), d.getDate() - ((d.getDay() + 6) % 7)));
  if (kind === 'month') return `m${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
  if (kind === 'year') return 'y' + d.getFullYear();
  return 'all';
}

function requestStats(id) {
  if (id) statsRange = id;
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
    h = `<div class="card"><div class="card-title">${icon('download')}Importing your history · ${p}%</div>
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
    h = `<div class="listen-line">${icon('history')}<span>${bits.filter(Boolean).join(' · ')}</span></div>`;
  }
  const slot = $('#statsHead');
  if (slot.dataset.html !== h) { slot.innerHTML = h; slot.dataset.html = h; }
  if (hs.done && hs.count > 0 && (!STATS || STATS.empty) && tab === 'stats' && !renderStatsHead.asked) {
    renderStatsHead.asked = true;
    send({ t: 'stats', range: statsRange });
  }
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

function deltaHtml(cur, prev, kind) {
  if (prev == null || !(prev > 0)) return '';
  const p = Math.round(((cur - prev) * 100) / prev);
  return `<span class="delta">${icon(p >= 0 ? 'trend-up' : 'down-trend')}<b>${p >= 0 ? '+' : ''}${p}%</b>&nbsp;vs ${esc(lastWord(kind))}</span>`;
}
const tile = (label, value, sub, delta, ic) => `<div class="tile"><span class="tl">${ic ? `<i class="tile-ic">${icon(ic)}</i>` : ''}${esc(label)}</span><b>${esc(value)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}${delta || ''}</div>`;

function storyCardHtml(s) {
  if (!s.summary.scrobbles) return '';
  const name = periodName(s.range);
  const al = (s.top.albums || []).find((x) => x.cover) || {};
  // The colors the story itself opens with: the top album's, once its palette is known.
  const c = al.cover && TINTS[al.cover];
  const h1 = c ? hexHsl(c.vibrant)[0] : hue(name + ' story');
  return `<button class="story-card" data-story="${esc(s.range.id)}" style="--s1:${h1.toFixed(0)};--s2:${((h1 + 55) % 360).toFixed(0)}">
    <div class="spinner-disc" style="--h:${hue(name)}"><i style="${al.cover ? `background-image:url('${esc(al.cover)}')` : ''}"></i></div>
    <div class="k">${s.range.kind === 'all' ? 'Your whole history' : `Your ${periodWord(s.range.kind)}`}${s.genre || s.decade ? ` ${esc(filterWords(s.genre, s.decade))}` : ''}</div>
    <h2>${esc(name)}, on record</h2>
    <p>Your top artist, ${ofThe(s.range.kind, 'song')}, listening personality and more.</p>
    <span class="go">${icon('play')}Play the story</span></button>`;
}

/** One observation in words, with its icon. */
function insightText(x, kind) {
  switch (x.kind) {
    case 'volume': return [x.pct >= 0 ? 'trend-up' : 'down-trend', `You listened <b>${Math.abs(x.pct)}% ${x.pct >= 0 ? 'more' : 'less'}</b> than ${lastWord(kind)}: ${num(x.count)} plays against ${num(x.prev)}.`];
    case 'stillTop': return ['star', `<b>${esc(x.artist)}</b> stays on top: your number one ${lastWord(kind)} as well.`];
    case 'topArtist': return ['heart', `<b>${esc(x.artist)}</b> made up ${pct(x.share)} of everything you played.`];
    case 'song': return ['repeat', `${ofThe(kind, 'Song')}: <b>${esc(x.track)}</b> by ${esc(x.artist)}, ${plural(x.plays, 'play')}.`];
    case 'peak': return ['schedule', `Your busiest day is <b>${DOW_LONG[x.dow].replace(/s$/, '')}</b>, your busiest hours <b>${pad(x.hour)}:00–${pad((x.hour + 2) % 24)}:00</b>.`];
    case 'night': return ['moon', `<b>${pct(x.share)}</b> of your plays came between 22:00 and 04:00.`];
    case 'weekend': return ['weekend', x.share >= 0.4 ? `Weekend listener: <b>${pct(x.share)}</b> of your plays fell on Saturdays and Sundays.` : `Mostly a weekday thing: only <b>${pct(x.share)}</b> on weekends.`];
    case 'discovery': return ['explore', `You found <b>${plural(x.count, 'new artist')}</b>. The one that stuck: <b>${esc(x.best)}</b>, ${plural(x.plays, 'play')}.`];
    case 'comeback': return ['history', `Welcome back, <b>${esc(x.artist)}</b>: your first plays since ${esc(dateLabel(x.lastBefore))}.`];
    case 'bigDay': return ['calendar', `Biggest day: <b>${esc(dayName(x.day))}</b>, ${plural(x.count, 'play')}.`];
    case 'streak': return ['fire', `Music on <b>${x.days} days in a row</b>, until ${esc(dateLabel(x.to))}.`];
    case 'vinyl': return ['album', `<b>${plural(x.count, 'track')}</b> came from your records, ${pct(x.share)} of your listening.`];
    default: return null;
  }
}
const INSIGHT_ORDER = ['comeback', 'discovery', 'volume', 'stillTop', 'peak', 'night', 'song', 'streak', 'vinyl', 'weekend', 'bigDay', 'topArtist'];

function highlightsViz(s) {
  const list = (s.insights || []).slice().sort((a, b) => INSIGHT_ORDER.indexOf(a.kind) - INSIGHT_ORDER.indexOf(b.kind)).slice(0, 6)
    .map((x) => insightText(x, s.range.kind)).filter(Boolean);
  if (!list.length) return '';
  return `<div class="viz"><h2>Highlights</h2>${list.map(([ic, text]) => `<div class="hl"><span class="hl-ic">${icon(ic)}</span><span>${text}</span></div>`).join('')}</div>`;
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
    ? `${new Date(a).toLocaleDateString([], { day: 'numeric', month: 'short' })} – ${dateLabel(b)}` : `${dateLabel(a)} – ${dateLabel(b)}`);
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
      ${areaViz(counts, { col: 'tl', avg: t.avg, peakLabel: (i) => `${esc(periodLabel(t.starts[i], t.unit, true))} · ${num(counts[i])}` })}
      <div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div>
      <div class="readout" id="ro-tl">Most: <b>${esc(periodLabel(t.starts[best], t.unit))}</b>, ${plural(counts[best], 'play')}</div></div>`;
  }
  return `<div class="viz"><h2>Listening over time</h2><span class="sub">Plays per ${t.unit}</span>
    <div class="cols"><span class="ymax">${num(max)}</span>${counts.map((v, i) => `<div class="c" data-col="tl" data-i="${i}"><i style="height:${(v / max) * 100}%"></i></div>`).join('')}
      <div class="avg" style="bottom:${(t.avg / max) * 100}%"><span>avg ${t.avg}</span></div></div>
    <div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div>
    <div class="readout" id="ro-tl">Most: <b>${esc(periodLabel(t.starts[best], t.unit))}</b>, ${plural(counts[best], 'play')}</div></div>`;
}

/** The river: plays per bin, the eight top artists in their inks stacked on everyone else. */
function riverViz(rv) {
  const B = (rv && rv.starts) ? rv.starts.length : 0;
  if (B < 2 || !rv.artists.length) return '';
  const W = 340, H = 150;
  const totals = Array.from({ length: B }, (_, b) => rv.series.reduce((a, s) => a + s[b], 0) + rv.other[b]);
  const max = Math.max(1, ...totals);
  const x = (b) => (b / (B - 1)) * W;
  const y = (v) => H - (v / max) * (H - 4);
  const layers = rv.series.map((s, k) => ({ s, color: INKS[k] })).concat([{ s: rv.other, color: 'var(--md-outline-variant)' }]);
  let base = new Array(B).fill(0);
  let paths = '';
  for (const L of layers) {
    const top = base.map((v, b) => v + L.s[b]);
    const up = top.map((v, b) => `${x(b).toFixed(1)},${y(v).toFixed(1)}`).join('L');
    const down = base.map((v, b) => `${x(b).toFixed(1)},${y(v).toFixed(1)}`).reverse().join('L');
    paths += `<path d="M${up}L${down}Z" fill="${L.color}"/>`;
    base = top;
  }
  const cw = W / (B - 1);
  const hits = rv.starts.map((s, b) => `<rect class="hit" data-rv="${b}" x="${Math.max(0, x(b) - cw / 2)}" y="0" width="${cw}" height="${H}"/>`).join('');
  const legend = rv.artists.map((n, k) => `<button class="ink" data-artist="${esc(n)}"><i class="sw" style="background:${INKS[k]}"></i><span>${esc(n)}</span></button>`).join('')
    + '<span class="ink"><i class="sw" style="background:var(--md-outline-variant)"></i><span>Everyone else</span></span>';
  return `<div class="viz"><h2>The river</h2><span class="sub">Plays per ${rv.unit}: your top artists on top of everyone else</span>
    <div class="river"><svg viewBox="0 0 ${W} ${H + 14}">${paths}<line class="cursor" id="rvCursor" x1="0" x2="0" y1="0" y2="${H}" visibility="hidden"/>${hits}
      <text x="0" y="${H + 12}">${esc(periodLabel(rv.starts[0], rv.unit, true))}</text><text x="${W}" y="${H + 12}" text-anchor="end">${esc(periodLabel(rv.starts[B - 1], rv.unit, true))}</text></svg></div>
    <div class="readout" id="ro-rv">Tap the river for a ${rv.unit}.</div><div class="inks">${legend}</div></div>`;
}
function riverText(rv, b) {
  const parts = rv.artists.map((n, k) => [n, rv.series[k][b]]).filter((p) => p[1] > 0).sort((p, q) => q[1] - p[1]).slice(0, 3)
    .map(([n, v]) => `${esc(n)} ${v}`);
  const total = rv.series.reduce((a, s) => a + s[b], 0) + rv.other[b];
  return `${esc(periodLabel(rv.starts[b], rv.unit))}: <b>${plural(total, 'play')}</b>${parts.length ? ` — ${parts.join(', ')}` : ''}${rv.other[b] ? `, everyone else ${rv.other[b]}` : ''}`;
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
  return `<div class="viz"><h2>New artists</h2><span class="sub">Artists you heard for the first time, per ${na.unit}</span>
    <div class="cols"><span class="ymax">${num(max)}</span>${cols}</div><div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div>
    <div class="readout" id="ro-na">${newArtistsText(last, na.unit)}</div></div>`;
}
function newArtistsText(x, unit) {
  if (!x) return '';
  const who = x.top && x.top.length ? ` — ${esc(x.top.join(', '))}` : '';
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
    <p class="hint">Genres from Discogs styles for ${pct(cov.discogs)} of plays (records you own and their artists), Last.fm tags for ${pct(cov.lastfm)}, unknown for ${pct(cov.none)}.</p></div>`;
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
      `<button class="r" ${trackAttrs(o.artist, o.track)}><span class="n">${i + 1}</span><span class="t"><b>${esc(o.track)}</b><small>${esc(o.artist)} · week of ${esc(dateLabel(o.week))}</small></span><span class="v">${num(o.count)}×</span></button>`).join('')}</div>`;
  }
  if (loops.length) {
    h += `<span class="sub" style="margin-top:${list.length ? 20 : 0}px">Back to back: the same song again and again, nothing in between</span><div class="rank">${loops.map((o, i) =>
      `<button class="r" ${trackAttrs(o.artist, o.track)}><span class="n">${i + 1}</span><span class="t"><b>${esc(o.track)}</b><small>${esc(o.artist)} · ${esc(dateLabel(o.at))}</small></span><span class="v">${o.run}× in a row</span></button>`).join('')}</div>`;
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
    <div class="rec-wrap"><canvas data-record="${id}"></canvas></div>
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
  const c = $(`canvas[data-record="${id}"]`);
  if (c) drawRecord(c);
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

function drawRecord(c) {
  const st = RECS[c.dataset.record];
  if (!st) return;
  const rec = st.rec;
  const dpr = Math.min(3, window.devicePixelRatio || 1);
  c.width = Math.round((c.clientWidth || 300) * dpr);
  c.height = c.width;
  const ctx = c.getContext('2d');
  const g = recordGeometry(c, rec);
  // the vinyl, with faint grooves
  ctx.fillStyle = '#121214';
  ctx.beginPath(); ctx.arc(g.cx, g.cy, g.R, 0, 2 * Math.PI); ctx.fill();
  ctx.strokeStyle = 'rgba(255,255,255,.035)';
  ctx.lineWidth = dpr;
  for (let r = g.r0; r < g.R; r += 3 * dpr) { ctx.beginPath(); ctx.arc(g.cx, g.cy, r, 0, 2 * Math.PI); ctx.stroke(); }
  // the plays: everyone else first, the eight inks on top, a spotlit artist above all
  const ds = Math.max(1.4 * dpr, Math.min(3 * dpr, g.step * 1.3));
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
  // the label
  ctx.fillStyle = themeColor('primary-container') || '#4f378b';
  ctx.beginPath(); ctx.arc(g.cx, g.cy, g.r0 - 3 * dpr, 0, 2 * Math.PI); ctx.fill();
  ctx.fillStyle = '#121214';
  ctx.beginPath(); ctx.arc(g.cx, g.cy, 2.5 * dpr, 0, 2 * Math.PI); ctx.fill();
  ctx.fillStyle = themeColor('on-primary-container') || '#eaddff';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  const lab = spot >= 0 ? rec.artists[spot] : st.label;
  ctx.font = `500 ${Math.round(11 * dpr)}px Roboto, system-ui, sans-serif`;
  ctx.fillText(lab.length > 16 ? lab.slice(0, 15) + '…' : lab, g.cx, g.cy - 10 * dpr);
  ctx.font = `${Math.round(9 * dpr)}px Roboto, system-ui, sans-serif`;
  ctx.fillText(`${compact(spot >= 0 ? rec.plays[spot] : n)} plays`, g.cx, g.cy + 9 * dpr);
  // the clock around the rim
  ctx.fillStyle = 'rgba(255,255,255,.5)';
  const rt = g.R - (g.R - g.r1) / 2;
  [['12', 0, -1], ['18', 1, 0], ['0', 0, 1], ['6', -1, 0]].forEach(([t, x, y]) => ctx.fillText(t, g.cx + x * rt, g.cy + y * rt));
}

/** Tap on a record: the nearest play, its artist lit up across the disc. */
function onRecordTap(c, e) {
  const id = c.dataset.record;
  const st = RECS[id];
  if (!st) return;
  const rec = st.rec;
  const box = c.getBoundingClientRect();
  const k = c.width / box.width;
  const px = (e.clientX - box.left) * k, py = (e.clientY - box.top) * k;
  const g = recordGeometry(c, rec);
  if (Math.hypot(px - g.cx, py - g.cy) < g.r0) { if (st.spot >= 0) setSpot(id, st.spot); return; }
  let best = -1, bestD = 22 * k;
  for (let i = 0; i < rec.d.length; i++) {
    const [x, y] = recordPos(g, rec, i);
    const d = Math.hypot(x - px, y - py);
    if (d < bestD) { bestD = d; best = i; }
  }
  if (best < 0) return;
  st.spot = -1;
  setSpot(id, rec.a[best]);
  const when = new Date(rec.start + rec.d[best] * 864e5);
  const name = rec.artists[rec.a[best]];
  const ro = $('#ro-rec-' + id);
  if (ro) {
    ro.innerHTML = `${esc(when.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' }))}, ${pad(Math.floor(rec.m[best] / 60))}:${pad(rec.m[best] % 60)} · <b>${esc(rec.tracks[rec.t[best]])}</b> by
      <button class="artist-link" data-artist="${esc(name)}">${esc(name)}</button> (${plural(rec.plays[rec.a[best]], 'play')}, lit up). Tap the label to show everyone.`;
  }
}

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
/** Settings → Look → Style: 'glass' (the cover behind frosted controls) or 'soft' (raised shapes in the cover's color); see looks.css. */
function lookMode() {
  try { return localStorage.getItem('look') === 'soft' ? 'soft' : 'glass'; } catch (_) { return 'glass'; }
}
document.documentElement.dataset.look = lookMode();
let ambientUrl = '';
function applyAmbient() {
  const root = document.documentElement;
  const mode = ambientMode();
  root.dataset.ambient = mode;
  if (mode === 'off') return;
  if (mode === 'wallpaper') {
    root.style.setProperty('--amb1', 'var(--md-primary)');
    root.style.setProperty('--amb2', 'var(--md-tertiary)');
    root.style.setProperty('--amb3', 'var(--md-secondary)');
    return;
  }
  const sp = ST.spin;
  const e = sp ? null : (ACT || []).slice().sort((a, b) => b.ts - a.ts)[0];
  const src = sp ? { cover: sp.cover, seed: sp.album + sp.artist } : e ? { cover: e.cover, seed: (e.album || e.track) + e.artist } : { cover: '', seed: 'Record Shelf' };
  ambientUrl = src.cover || '';
  const c = ambientUrl ? TINTS[ambientUrl] : null;
  if (ambientUrl && !c) send({ t: 'palette', url: ambientUrl });
  let h1, h2, sat;
  if (c) {
    const v = hexHsl(c.vibrant), d = hexHsl(c.dominant);
    [h1, h2, sat] = [v[0], d[0], Math.max(v[1], d[1])];
  } else [h1, h2, sat] = [hue(src.seed), (hue(src.seed) + 48) % 360, 0.6];
  const k = sat < 0.12 ? 0 : 1; // black-and-white covers glow gray
  root.style.setProperty('--amb1', `hsl(${h1.toFixed(0)} ${70 * k}% 46%)`);
  root.style.setProperty('--amb2', `hsl(${h2.toFixed(0)} ${60 * k}% 40%)`);
  root.style.setProperty('--amb3', `hsl(${((h1 + 320) % 360).toFixed(0)} ${55 * k}% 36%)`);
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
 * Re-rendering a page keeps its moving sleeve (turned over or not, record out) when it shows the
 * same record; a better picture arriving is swapped into it instead of building a new one.
 */
function keepHolo(body, html) {
  const old = body.querySelector('[data-holo]');
  body.innerHTML = html;
  const fresh = body.querySelector('[data-holo]');
  if (old && fresh && old.dataset.key === fresh.dataset.key) {
    if (old.dataset.cover !== fresh.dataset.cover) {
      const oc = old.querySelector('.sleeve .cover'), nc = fresh.querySelector('.sleeve .cover');
      if (oc && nc) oc.replaceWith(nc);
      const ol = old.querySelector('.vinyl .lbl'), nl = fresh.querySelector('.vinyl .lbl');
      if (ol && nl) ol.setAttribute('style', nl.getAttribute('style') || '');
      old.dataset.cover = fresh.dataset.cover;
    }
    fresh.replaceWith(old);
  }
  fillSleeveBacks();
  Tilt.refresh();
}

/** Turns a sleeve over; the tilt holds still while it turns, so the phone only has to move the two faces. */
function turnSleeve(el) {
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
    <div class="sb-foot"><small>${esc([info.label || base.label, info.format || base.format].filter(Boolean).join(' · '))}</small><span class="barcode"></span></div>
    ${plays ? `<div class="sb-notes">played ${num(plays)}×</div>` : ''}`;
}

/** Now spinning as a turntable: the record on the platter with its cover as the label. */
function turntableHtml(sp) {
  const label = sp.cover ? `style="background-image:url('${esc(sp.cover)}')"` : '';
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
  return `--tint-d:hsl(${h.toFixed(0)} ${sat}% ${sat ? 70 : 78}%);--tint-l:hsl(${h.toFixed(0)} ${sat}% ${sat ? 38 : 32}%);`
    + `--glow-d:hsl(${gh.toFixed(0)} ${gsat}% 24%);--glow-l:hsl(${gh.toFixed(0)} ${gsat}% 86%)`;
}
/**
 * The page behind [el] takes the picture at [url] as its background: shrunk to a few dozen pixels
 * on a canvas once (softened and made more colorful there), then simply stretched over the page,
 * which blurs it for free. No filter runs while the page scrolls or the sleeve tilts.
 */
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
  bd.classList.remove('ready');
  const img = new Image();
  img.onload = () => {
    if (bd.dataset.url !== url) return;
    const cv = bd.firstChild;
    const small = document.createElement('canvas');
    small.width = cv.width;
    small.height = cv.height;
    const sx = small.getContext('2d');
    sx.imageSmoothingQuality = 'high';
    sx.filter = 'saturate(1.6)';
    // The upper middle of the cover, stretched to the page's shape.
    const side = Math.min(img.width, img.height);
    sx.drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, small.width, small.height);
    const ctx = cv.getContext('2d');
    ctx.clearRect(0, 0, cv.width, cv.height);
    ctx.filter = 'blur(3px)';
    ctx.drawImage(small, 0, 0);
    bd.classList.add('ready');
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
      ${areaViz(x.months, { col: 'am', peakLabel: (i) => `${esc(mDate(i).toLocaleDateString([], { month: 'short', year: 'numeric' }))} · ${num(x.months[i])}` })}
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
    <span class="dcover">${coverHtml(x.cover, x.title, artist)}${x.owned ? '<i class="sticker mini" aria-label="On your shelf">★</i>' : ''}${isNew(x.date) ? '<i class="new">New</i>' : ''}</span>
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
    .map(([k, l]) => `<button class="chip ${discoFilter === k ? 'on' : ''}" data-dfilter="${k}">${discoFilter === k ? icon('check', 'i small') : ''}${esc(l)}</button>`).join('');
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
        <span class="v">${p ? plural(p, 'play') : ''}</span>${p ? `<div class="bar"><i style="width:${(p / max) * 100}%"></i></div>` : ''}</button>`;
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
  const dec = shelf.decade
    ? `<button class="chip on" data-fclear="decade">${shelf.decade}s${icon('close', 'i small')}</button>`
    : `<button class="chip" data-fpick="decade">${icon('calendar', 'i small')}Decade</button>`;
  const gen = shelf.genre
    ? `<button class="chip on" data-fclear="genre">${esc(shelf.genre.slice(2))}${icon('close', 'i small')}</button>`
    : `<button class="chip" data-fpick="genre">${icon('album', 'i small')}Genre or style</button>`;
  $('#filterChips').innerHTML = dec + gen;
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
  const known = !STATS || !STATS.genreOptions || STATS.genreOptions.some((o) => o.key === gk);
  return `<div class="card sel-sum"><div class="card-title">${icon('album')}${esc(name)} on your shelf</div>
    <div class="sum-nums"><div><b>${list.length}</b><small>${list.length === 1 ? 'record' : 'records'}</small></div><div><b>${artists}</b><small>${artists === 1 ? 'artist' : 'artists'}</small></div>
      <div><b>${compact(plays)}</b><small>plays on Last.fm</small></div><div><b>${never}</b><small>never played</small></div></div>
    ${top.length ? `<p class="body">Most played: ${top.map((r) => `<b>${esc(r.title)}</b>`).join(', ')}</p>` : ''}
    ${bars.length > 1 ? `<p class="label">${shelf.genre ? 'By decade' : 'By style'}</p><div class="mini-bars">${bars.map(([k, n]) => `<div><span>${esc(k)}</span><i><b style="width:${(n / max) * 100}%"></b></i><small>${n}</small></div>`).join('')}</div>` : ''}
    ${gk && known && (ST.history || {}).count ? `<div class="btn-row"><button class="btn tonal" data-statsgenre="${esc(gk)}">${icon('chart')}Your ${esc(shelf.genre.slice(2))} listening</button></div>` : ''}</div>`;
}

// ---- the Stats tab for one genre ----

let statsGenre = '';
let statsDecade = 0;
/** "in Post Punk", "from the 1970s", "in Post Punk from the 1970s", or "" without filters. */
const filterWords = (genre, decade) => [genre ? `in ${genreLabel(genre)}` : '', decade ? `from the ${decade}s` : ''].filter(Boolean).join(' ');
const genreLabel = (key) => ((STATS && STATS.genreOptions) || []).find((o) => o.key === key)?.label || key.replace(/\b\w/g, (c) => c.toUpperCase());
function renderGenreRow() {
  const row = $('#genreRow');
  if (!row) return;
  const g = statsGenre
    ? `<button class="chip on" data-gclear>${icon('album', 'i small')}${esc(genreLabel(statsGenre))}${icon('close', 'i small')}</button>`
    : `<button class="chip" data-gpick>${icon('album', 'i small')}All genres${icon('down', 'i small')}</button>`;
  const d = statsDecade
    ? `<button class="chip on" data-dclear>${icon('calendar', 'i small')}${statsDecade}s${icon('close', 'i small')}</button>`
    : `<button class="chip" data-dpick>${icon('calendar', 'i small')}All decades${icon('down', 'i small')}</button>`;
  row.innerHTML = g + d;
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
  return `<div class="viz"><h2>Decades</h2><span class="sub">When the music you played came out (${share}% of plays have a known year). Tap one for its stats.</span>
    <div class="decades">${ds.map((d) => `<button class="dec${statsDecade === d.decade ? ' on' : ''}" data-setdecade="${d.decade}"><span>${d.decade}s</span><i><b style="width:${(d.plays / max) * 100}%"></b></i><small>${compact(d.plays)}</small></button>`).join('')}</div></div>`;
}
function openGenreSheet() {
  const opts = (STATS && STATS.genreOptions) || [];
  openSheet(`<div class="sheet-title">Stats for one genre</div><p class="body">Everything on the Stats tab and in the story, for the plays whose main genre is this one.</p>
    <div class="chips">${opts.map((o) => `<button class="chip" data-gset="${esc(o.key)}">${esc(o.label)}<small class="n">${compact(o.plays)}</small></button>`).join('') || '<p class="hint">Genres show up once they are looked up.</p>'}</div>`);
  $('#sheetContent').onclick = (e) => {
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
    const next = { ...s, ...g, label: s.label || g.label, req: s.req, loading: false };
    if (shown) { GENRE = next; renderGenre(); }
    return next;
  });
};

function renderGenre() {
  const g = GENRE;
  if (!g || !isOpen('genrePage')) return;
  const shelfRecs = COLL.filter((r) => [...(r.genres || []), ...(r.styles || [])].some((x) => genreKeyOf(x) === g.key));
  let h = `<div class="genre-hero page-hero"><i class="glow"></i><span class="k">Genre</span><h1>${esc(g.label)}</h1>
    ${g.plays ? `<div class="meta">${pct(g.share)} of everything you play${g.rank ? `, your number ${g.rank} genre` : ''}</div>` : ''}</div>`;
  if (g.loading) h += '<div class="lin inline indet"><i></i></div>';
  else if (g.missing) h += `<div class="empty small"><p>None of your plays has ${esc(g.label)} as its main genre${shelfRecs.length ? ', but some of your records do' : ''}.</p></div>`;
  else {
    const top = g.top || {};
    h += `<div class="tiles">
      ${tile('Plays', num(g.plays), `on ${plural(g.days, 'day')}`, '', 'music')}
      ${tile('Artists', num(g.artists), (top.artists || [])[0] ? `most: ${top.artists[0].name}` : '', '', 'heart')}
      ${tile('Since', esc(monthYear(g.first)), `last played ${esc(dateLabel(g.last))}`, '', 'history')}
      ${tile('Biggest day', plural(g.bestDay.count, 'play'), esc(dateLabel(g.bestDay.day)), '', 'fire')}
    </div>
    <div class="btn-row"><button class="btn tonal" data-statsgenre="${esc(g.key)}">${icon('chart')}Full stats for ${esc(g.label)}</button></div>`;
    if ((top.artists || []).length) {
      h += `<div class="viz"><h2>Your ${esc(g.label)} artists</h2><span class="sub">Most played, all time</span>
        <div class="xcards">${top.artists.map((a) => `<button class="xcard" data-artist="${esc(a.name)}">${coverHtml(a.photo, a.name, a.name, 'round')}<b>${esc(a.name)}</b><small class="meta">${plural(a.plays, 'play')}</small></button>`).join('')}</div></div>`;
    }
    const lost = g.lost || [], lostAlbums = g.lostAlbums || [];
    if (lost.length || lostAlbums.length) {
      h += `<div class="viz"><h2>Rediscover</h2><span class="sub">${esc(g.label)} you loved, quiet for half a year or more</span>
        <div class="xcards">${lost.map((a) => `<button class="xcard" data-artist="${esc(a.name)}">${coverHtml(a.photo || a.cover, a.name, a.name, 'round')}<b>${esc(a.name)}</b><small>last ${esc(monthYear(a.last))}</small><small class="meta">${plural(a.plays, 'play')}</small></button>`).join('')}
        ${lostAlbums.map((a) => `<button class="xcard" ${albumAttrs(a.artist, a.title)} data-cover="${esc(a.cover)}">${coverHtml(a.cover, a.title, a.artist)}<b>${esc(a.title)}</b><small>${esc(a.artist)}</small><small class="meta">last ${esc(monthYear(a.last))}</small></button>`).join('')}</div></div>`;
    }
    if ((top.albums || []).length) {
      h += `<div class="viz"><h2>Albums</h2><div class="xcards">${top.albums.map((a) => `<button class="xcard" ${albumAttrs(a.artist, a.title)} data-cover="${esc(a.cover)}">${coverHtml(a.cover, a.title, a.artist)}<b>${esc(a.title)}</b><small>${esc(a.artist)}</small><small class="meta">${plural(a.plays, 'play')}</small></button>`).join('')}</div></div>`;
    }
    if ((top.tracks || []).length) {
      const max = Math.max(1, ...top.tracks.map((t) => t.plays));
      h += `<div class="viz"><h2>Songs</h2><div class="rank">${top.tracks.map((t, i) => `<button class="r" ${trackAttrs(t.artist, t.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}</small></span><span class="v">${num(t.plays)}</span><div class="bar"><i style="width:${(t.plays / max) * 100}%"></i></div></button>`).join('')}</div></div>`;
    }
    const all = g.all || [];
    if (all.length > (top.artists || []).length) {
      const shown = g.showAll ? all : all.slice(0, 30);
      h += `<div class="viz"><h2>Everyone in ${esc(g.label)}</h2><span class="sub">All ${num(g.artists)} artists you played, most first</span>
        <div class="chips everyone">${shown.map((a) => `<button class="chip" data-artist="${esc(a.name)}">${esc(a.name)}<small class="n">${compact(a.plays)}</small></button>`).join('')}</div>
        ${all.length > shown.length ? `<button class="btn text" data-genreall>Show all ${num(all.length)}</button>` : ''}</div>`;
    }
    const ds = (g.decades && g.decades.decades) || [];
    if (ds.length > 1) {
      const max = Math.max(1, ...ds.map((d) => d.plays));
      h += `<div class="viz"><h2>Decades</h2><span class="sub">When your ${esc(g.label)} came out</span><div class="decades">${ds.map((d) => `<div class="dec"><span>${d.decade}s</span><i><b style="width:${(d.plays / max) * 100}%"></b></i><small>${compact(d.plays)}</small></div>`).join('')}</div></div>`;
    }
    h += lifeCharts(g, 'it', '', g.label + g.label);
  }
  if (shelfRecs.length) {
    h += `<div class="viz"><h2>On your shelf</h2><span class="sub">${plural(shelfRecs.length, 'record')} with ${esc(g.label)} on Discogs</span>
      <div class="xcards">${shelfRecs.map((r) => `<button class="xcard" data-rec="${r.id}">${coverHtml(r.cover || r.thumb, r.title, r.artist)}<b>${esc(r.title)}</b><small>${esc(r.artist)}</small><small class="meta">${r.year || ''}</small></button>`).join('')}</div></div>`;
  }
  $('#genreBody').innerHTML = h;
  tint($('#genreBody'), '', g.label + g.label);
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

function renderTrack() {
  const t = TRACK;
  if (!t || !isOpen('trackPage')) return;
  const al = t.album && typeof t.album === 'object' ? t.album : null;
  const albumTitle = al ? al.title : t.albumTitle;
  const cover = (al && al.cover) || t.cover;
  const shelfId = (al && al.owned) || (albumTitle ? shelfIdOf(al ? al.artist : t.artist, albumTitle) : 0);
  let h = `<div class="hero page-hero"><i class="glow"></i>${holoCover(cover, albumTitle || t.title, t.artist, { owned: !!shelfId, back: !!shelfId, rel: shelfId, key: `track|${t.artist}|${t.title}|${shelfId}` })}
    <h1>${esc(t.title)}</h1>
    <div class="artist"><button class="artist-link" data-artist="${esc(t.artist)}">${esc(t.artist)}</button>${albumTitle ? `<span class="dot">·</span><button class="artist-link" ${albumAttrs(al ? al.artist : t.artist, albumTitle)}>${esc(albumTitle)}</button>` : ''}</div>
    ${listenRow('tracks', t.artist, t.title)}</div>`;
  if (t.loading) h += '<div class="lin inline indet"><i></i></div>';
  else if (t.missing) h += `<div class="empty"><p>${esc(missingNote())}</p></div>`;
  else {
    const r = t.range || {};
    h += `<div class="tiles">
      ${tile('Plays', num(t.plays), `#${t.rank} of ${compact(t.tracks)} songs`, '', 'music')}
      ${tile(periodName(r), r.plays ? `#${r.rank}` : '–', r.plays ? plural(r.plays, 'play') : 'no plays', '', 'calendar')}
      ${tile('First heard', esc(monthYear(t.first)), `last played ${esc(dateLabel(t.last))}`, '', 'history')}
      ${tile('Biggest day', plural(t.bestDay.count, 'play'), esc(dateLabel(t.bestDay.day)), '', 'fire')}
    </div>
    <p class="hint center">${pct(t.shareOfArtist)} of your ${esc(t.artist)} plays, on ${plural(t.days, 'day')}.</p>`;
    h += vsBlock(t.global, 'it', t.globalRank ? ` It's ${esc(t.artist)}'s #${t.globalRank} most played song there.` : '');
    h += lifeCharts(t, 'it', cover, (albumTitle || t.title) + t.artist);
    if (t.next && t.next.length) {
      h += `<div class="viz"><h2>What comes next</h2><span class="sub">What you played right after it</span><div class="rank">${t.next.map((x, i) =>
        `<button class="r" ${trackAttrs(x.artist, x.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)}</small></span><span class="v">${x.count}×</span></button>`).join('')}</div></div>`;
    }
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
    ${shelfId ? '<span class="flip-hint">Tap the sleeve to turn it over</span>' : ''}
    <h1>${esc(a.title)}</h1>
    <div class="artist"><button class="artist-link" data-artist="${esc(a.artist)}">${esc(a.artist)}</button></div>
    ${listenRow('albums', a.artist, a.title)}
    ${a.dzInfo && a.dzInfo.date ? `<div class="meta">${esc(a.dzInfo.date.slice(0, 4))}${a.dzInfo.label ? `, ${esc(a.dzInfo.label)}` : ''}</div>` : ''}
    ${shelfId ? `<div class="stats"><button class="chip link" data-rec="${shelfId}">${icon('album')}On your shelf</button></div>` : ''}</div>`;
  if (a.loading) h += '<div class="lin inline indet"><i></i></div>';
  else if (a.missing) {
    h += `<div class="empty small"><p>${(ST.history || {}).count > 0 ? 'You haven\'t played this one yet.' : esc(missingNote())}</p></div>`;
    h += tracklistHtml(a, cover);
  } else {
    const r = a.range || {};
    h += `<div class="tiles">
      ${tile('Plays', num(a.plays), `#${a.rank} of ${compact(a.albums)} albums`, '', 'music')}
      ${tile(periodName(r), r.plays ? `#${r.rank}` : '–', r.plays ? plural(r.plays, 'play') : 'no plays', '', 'calendar')}
      ${tile('First heard', esc(monthYear(a.first)), `last played ${esc(dateLabel(a.last))}`, '', 'history')}
      ${tile('Biggest day', plural(a.bestDay.count, 'play'), esc(dateLabel(a.bestDay.day)), '', 'fire')}
    </div>`;
    const songs = a.dzInfo && !a.dzInfo.error && a.dzInfo.tracks ? a.dzInfo.tracks.length : 0;
    if (songs) h += `<p class="hint center">Plays count songs, not whole albums: ${num(a.plays)} song plays are about ${a.plays >= songs ? `${num(Math.round(a.plays / songs))} times` : 'less than once'} through its ${plural(songs, 'song')}.</p>`;
    const tl = a.trackList || [];
    const tracklist = tracklistHtml(a, cover);
    if (tracklist) h += tracklist;
    else if (tl.length) {
      const max = Math.max(1, ...tl.map((x) => x.plays));
      h += `<div class="viz"><h2>The songs you play</h2><span class="sub">${plural(tl.length, 'different song')} from this album</span><div class="rank">${tl.map((x, i) =>
        `<button class="r" ${trackAttrs(a.artist, x.title, { album: a.title, cover })}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b></span><span class="v">${num(x.plays)}</span><div class="bar"><i style="width:${(x.plays / max) * 100}%"></i></div></button>`).join('')}</div></div>`;
    }
    h += vsBlock(a.global, 'it');
    h += lifeCharts(a, 'it', cover, a.title + a.artist);
  }
  keepHolo($('#albumBody'), h);
  tint($('#albumBody'), cover, a.title + a.artist);
}

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
      <button class="uh" data-artist="${esc(n.name)}">${coverHtml(n.photo, n.name, n.name, 'tiny round')}<span><b>${esc(n.name)}</b><small>${[n.genre, plural(UNI.year ? uniPlays(n) : n.plays, 'play') + (UNI.year ? ` in ${UNI.year}` : ''), c ? `${c.name} & co.` : 'a loose star'].filter(Boolean).map(esc).join(' · ')}</small></span>${icon('chevron-right')}</button>
      <p>${how}${ba ? ` Usually played ${ba}.` : ''}</p>
      ${near ? `<div class="chip-row">${near}</div>` : '<p class="hint">No strong links: a star of its own.</p>'}`;
    card.hidden = false;
  } else if (UNI.cluster >= 0) {
    const c = u.clusters[UNI.cluster];
    const members = u.nodes.map((n, i) => [n, i]).filter(([n]) => n.c === UNI.cluster);
    card.innerHTML = `<button class="icon-btn x" data-uni-x aria-label="Close">${icon('close')}</button>
      <div class="uh"><i class="dot big" style="background:${starColor(UNI.cluster)}"></i><span><b>${esc(c.name)} &amp; co.</b><small>${[plural(c.size, 'artist'), c.genre ? `mostly ${c.genre}` : '', `${pct(c.plays / Math.max(1, u.plays))} of these plays`].filter(Boolean).map(esc).join(' · ')}</small></span></div>
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
  const meta = [depth ? `found ${monthYear(n.at)}` : '', n.kept ? plural(n.plays, 'play') : 'a stepping stone', n.d ? `led to ${n.d}` : ''].filter(Boolean).join(' · ');
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
/** "23×", "1.4×", "0.6×": your plays over the average listener's. */
const fmtX = (r) => `${r >= 10 ? Math.round(r) : r >= 1 ? r.toFixed(1).replace(/\.0$/, '') : r.toFixed(1)}×`;
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
    <div class="rank">${top.map((x) => `<button class="r${x.mine ? '' : ' unheard'}" ${trackAttrs(a.name, x.title)}><span class="n">${x.rank}</span><span class="t"><b>${esc(x.title)}</b><small>${compact(x.plays)} plays worldwide</small></span><span class="v">${x.mine ? `${num(x.mine)}×` : 'not yet'}</span></button>`).join('')}</div></div>`;
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
/** While the counts are being looked up, the page shows how far; when that ends it asks again. */
function vsStateTick() {
  const busy = !!(ST.history || {}).globalsBusy;
  if (vsBusy && !busy && (isOpen('vsPage') || VS)) send({ t: 'vs' });
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
  const busy = hs.globalsBusy ? `<div class="card"><div class="card-title">${icon('download')}Asking Last.fm · ${num(hs.globalsDone)} of ${num(hs.globalsTotal)}</div>
    <p>Everyone's play counts for your top artists and songs, a few a second. The first time takes a minute or two; after that only new ones, once a month.</p><div class="lin inline"><i style="--p:${p}%"></i></div></div>` : '';
  if (!v) { body.innerHTML = busy + '<div class="lin inline indet"><i></i></div>'; return; }
  if (v.empty) { body.innerHTML = `<div class="empty"><p>Import your Last.fm history on the Stats tab first.</p></div>`; return; }
  if (!v.lastfm) { body.innerHTML = `<div class="empty"><p>Connect Last.fm in Settings first: the comparisons use your Last.fm API key.</p></div>`; return; }
  if (!v.knownArtists) { body.innerHTML = busy || `<div class="empty"><p>Looking up your artists on Last.fm…</p></div>`; return; }
  const cover = v.knownPlays / Math.max(1, v.plays);
  let h = `<div class="vs-hero page-hero"><i class="glow"></i><span class="k">You vs everyone</span>
    <h1>Half of your plays go to artists with fewer than <em>${compact(v.median)}</em> listeners</h1>
    <div class="meta">Against everyone on Last.fm, all time · counts known for ${pct(cover)} of your plays</div></div>${busy}`;
  // From household names to the underground
  const tiers = v.tiers || [];
  const tot = Math.max(1, tiers.reduce((s, t) => s + t.plays, 0));
  h += `<div class="viz"><h2>From household names to the underground</h2><span class="sub">Your plays by how many people listen to the artist</span>
    <div class="tierbar">${tiers.map((t, k) => (t.plays ? `<i style="flex:${t.plays};background:${tierColor(k)}" title="${esc(VS_TIERS[k][0])}"></i>` : '')).join('')}</div>
    <div class="tierlist">${tiers.map((t, k) => `<div class="tr"><span class="sw" style="background:${tierColor(k)}"></span><span class="nm"><b>${VS_TIERS[k][0]}</b><small>${VS_TIERS[k][1]} · ${plural(t.artists, 'artist')}${t.top ? ` · most: <button class="link" data-artist="${esc(t.top)}">${esc(t.top)}</button>` : ''}</small></span><span class="pc">${pct(t.plays / tot)}</span></div>`).join('')}</div></div>`;
  // Bigger fan than most
  const fans = v.fans || [];
  if (fans.length) {
    h += `<div class="viz"><h2>A bigger fan than most</h2><span class="sub">Your plays against the average listener's (the tick)</span>
      <div class="rank vs-rank">${fans.map((a) => `<button class="r with-cover" data-artist="${esc(a.name)}">${coverHtml(a.photo, a.name, a.name, 'tiny round')}<span class="t"><b>${esc(a.name)}</b><small>you ${num(a.mine)} · average ${num(Math.round(a.avg))}</small></span><span class="v x">${fmtX(a.ratio)}</span>${vsBar(a.mine, a.avg)}</button>`).join('')}</div></div>`;
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
      <div class="rank vs-rank">${songs.map((t, i) => `<button class="r" ${trackAttrs(t.artist, t.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)} · you ${num(t.mine)} · average ${num(Math.round(t.avg))}</small></span><span class="v x">${fmtX(t.ratio)}</span>${vsBar(t.mine, t.avg)}</button>`).join('')}</div></div>`;
  }
  // Your favorite song by each top artist against everyone's
  const taste = v.taste || [];
  if (taste.length) {
    h += `<div class="viz"><h2>Your favorite vs everyone's</h2><span class="sub">Your most played song by each of your top artists, and theirs on Last.fm</span>
      <div class="taste">${taste.map((x) => {
        const same = x.mine.rank === 1;
        const where = same ? 'their most played too' : x.mine.rank ? `their #${x.mine.rank} on Last.fm` : 'not in their top 50 on Last.fm';
        return `<div class="tc"><button class="th" data-artist="${esc(x.artist)}">${coverHtml(x.photo, x.artist, x.artist, 'tiny round')}<span class="tn"><b>${esc(x.artist)}</b><small>you've heard ${x.heard} of their top ${x.of}</small></span></button>
          <button class="tl" ${trackAttrs(x.artist, x.mine.title)}><span class="lb">Yours</span><b>${esc(x.mine.title)}</b><small>${plural(x.mine.plays, 'play')} · ${where}</small></button>
          ${same ? '' : `<button class="tl" ${trackAttrs(x.artist, x.theirs.title)}><span class="lb">Everyone's</span><b>${esc(x.theirs.title)}</b><small>${compact(x.theirs.plays)} plays · ${x.theirs.mine ? `you: ${plural(x.theirs.mine, 'play')}` : 'you haven\'t played it'}</small></button>`}</div>`;
      }).join('')}</div></div>`;
  }
  h += `<p class="hint center">Counts from Last.fm, all time, for everyone (you included), looked up again once a month. The average listener is all plays divided by all listeners; a few heavy listeners pull that up, so most people played less.</p>`;
  body.innerHTML = h;
  tint(body, '', 'You vs everyone');
}

// ---- explore: the long tail ----

let EXPLORE = null;
function requestExplore() {
  if (!(ST.history || {}).count) { EXPLORE = null; renderExplore(); return; }
  if (!EXPLORE) renderExplore();
  send({ t: 'explore' });
}
window.onExplore = (x) => { EXPLORE = x; renderExplore(); };

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
  const tail = x.tail || {};
  let h = `<p class="xp-intro">You've played <b>${num(tail.artists)}</b> artists${tail.few ? `, <b>${num(tail.few)}</b> of them fewer than ${tail.fewPlays} times` : ''}. Here's where to dig.</p>
    <div class="xp-tiles">
      <button class="xp-tile" data-atlas>${ATLAS_ART}<b>Musical atlas</b><small>Universe, family tree, landscape</small></button>
      <button class="xp-tile" id="openVs">${VS_ART}<b>You vs everyone</b><small id="vsTileLine">Compared with all of Last.fm</small></button>
    </div>
    <button class="btn tonal xp-dig" data-pl="dig">${icon('shuffle')}Crate dig: a random playlist</button>`;
  const la = x.lostArtists || [];
  if (la.length) {
    h += xSection('Lost favorites', 'Artists you played a lot that have gone quiet for half a year or more', 'lost',
      `<div class="xcards">${la.map((a) => `<button class="xcard" data-artist="${esc(a.name)}">${coverHtml(a.photo || a.cover, a.name, a.name)}<b>${esc(a.name)}</b>
        <small>${plural(a.plays, 'play')} · last ${esc(monthYear(a.last))}</small><small class="meta">Big in ${esc(monthYear(a.peak.start))}</small></button>`).join('')}</div>`);
  }
  const lb = x.lostAlbums || [];
  if (lb.length) {
    h += xSection('Albums to rediscover', 'Albums you played through, then left on the shelf', 'lostAlbums',
      `<div class="xcards">${lb.map((a) => `<button class="xcard" ${albumAttrs(a.artist, a.title)} data-cover="${esc(a.cover)}">${coverHtml(a.cover, a.title, a.artist)}<b>${esc(a.title)}</b>
        <small>${esc(a.artist)}</small><small class="meta">${plural(a.plays, 'play')} · last ${esc(monthYear(a.last))}</small></button>`).join('')}</div>`);
  }
  const rg = x.regulars || [];
  if (rg.length) {
    h += xSection('Quiet regulars', 'Never on top, always around: artists you come back to month after month', 'regulars',
      `<div class="rank">${rg.map((a) => `<button class="r with-cover" data-artist="${esc(a.name)}">${coverHtml(a.photo || a.cover, a.name, a.name, 'tiny round')}<span class="t"><b>${esc(a.name)}</b><small>${a.track ? `${esc(a.track)} · ` : ''}since ${esc(monthYear(a.first))}</small></span><span class="v">${a.months} months</span></button>`).join('')}</div>`);
  }
  const od = x.oneDay || [];
  if (od.length) {
    h += xSection('One-day wonders', 'Played all day once, never again. Worth another try?', 'oneDay',
      `<div class="rank">${od.map((a) => `<button class="r with-cover" data-artist="${esc(a.name)}">${coverHtml(a.photo || a.cover, a.name, a.name, 'tiny round')}<span class="t"><b>${esc(a.name)}</b><small>${esc(dateLabel(a.first))}${a.track ? ` · ${esc(a.track)}` : ''}</small></span><span class="v">${plural(a.plays, 'play')}</span></button>`).join('')}</div>`);
  }
  const dc = x.deepCuts || [];
  if (dc.length) {
    h += xSection('Deep cuts', 'Songs by your favorite artists you\'ve played only once or twice', 'deepCuts',
      `<div class="rank">${dc.map((t) => `<button class="r" ${trackAttrs(t.artist, t.title, { album: t.album })}><span class="n">${icon('music', 'i small')}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}${t.album ? ` · ${esc(t.album)}` : ''}</small></span><span class="v">${t.plays}×</span></button>`).join('')}</div>`);
  }
  const otd = x.onThisDay || [];
  if (otd.length) {
    h += xSection('On this day', `What you played on ${esc(new Date().toLocaleDateString([], { day: 'numeric', month: 'long' }))} in earlier years`, 'onThisDay',
      otd.map((y) => `<div class="otd"><div class="yr"><b>${y.year}</b><small>${plural(y.plays, 'play')} · ${y.artists.map(esc).join(', ')}</small></div>
        <div class="rank">${y.tracks.map((t) => `<button class="r" ${trackAttrs(t.artist, t.title, { album: t.album })}><span class="n">${icon('music', 'i small')}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}</small></span><span class="v">${t.plays > 1 ? `${t.plays}×` : ''}</span></button>`).join('')}</div></div>`).join(''));
  }
  body.innerHTML = h;
  renderVsCard();
  if (!VS) send({ t: 'vs' });
}

// ---- playlists ----

let PLAYLIST = null;
let TIDALJOB = null;
function openPlaylist(kind, range) {
  openPage('playlistPage');
  PLAYLIST = { loading: true, kind };
  TIDALJOB = null;
  renderPlaylist();
  send({ t: 'playlist', kind, range: range || statsRange });
}
window.onPlaylist = (p) => {
  if (p.kind === 'top' && p.range) { p.name = `${periodName(p.range)} on record`; p.note = `Your most played songs of ${periodName(p.range)}`; }
  PLAYLIST = p;
  renderPlaylist();
};
const playlistText = (p) => p.tracks.map((t) => `${t.artist} - ${t.title}`).join('\n');

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
  if (p.loading) { body.innerHTML = '<div class="lin inline indet"><i></i></div>'; return; }
  if (p.empty || !p.tracks || !p.tracks.length) { body.innerHTML = `<div class="empty">${icon('playlist', 'i big')}<p>No songs for this playlist yet.</p></div>`; return; }
  body.innerHTML = `<div class="pl-head"><div class="pl-icon">${icon('playlist')}</div><div><h1>${esc(p.name)}</h1><p>${esc(p.note)} · ${plural(p.tracks.length, 'song')}</p></div></div>
    <div class="btn-row">
      <button class="btn filled" id="plCopy">${icon('copy')}Copy list</button>
      <button class="btn tonal" id="plShare">${icon('share')}Share</button>
      ${p.kind === 'dig' ? `<button class="btn text" data-pl="dig">${icon('shuffle')}Dig again</button>` : ''}
    </div>
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
    <div class="rank">${p.tracks.map((t, i) => `<button class="r" ${trackAttrs(t.artist, t.title, { album: t.album })}><span class="n">${i + 1}</span><span class="t"><b>${esc(t.title)}</b><small>${esc(t.artist)}${t.album ? ` · ${esc(t.album)}` : ''}</small></span><span class="v">${t.plays}×</span></button>`).join('')}</div>`;
  $('#plTidalBlock').onclick = (e) => {
    if (e.target.closest('#plTidalLogin')) send({ t: 'tidalLogin' });
    if (e.target.closest('#plTidal')) {
      TIDALJOB = { state: 'finding', done: 0, total: p.tracks.length, found: 0, name: p.name };
      $('#plTidalBlock').innerHTML = tidalBlock();
      send({ t: 'tidalPlaylist', name: p.name, note: `${p.note}. Made with Record Shelf.`, tracks: p.tracks.map((t) => ({ artist: t.artist, title: t.title })) });
    }
  };
  $('#plCopy').onclick = () => {
    const text = playlistText(p);
    if (Native) send({ t: 'copyText', text, label: p.name });
    else if (navigator.clipboard) navigator.clipboard.writeText(text).then(() => toast('Copied'), () => toast('Copying failed'));
  };
  $('#plShare').onclick = () => {
    const text = `${p.name}\n\n${playlistText(p)}`;
    if (Native) send({ t: 'shareText', text, subject: p.name });
    else if (navigator.share) navigator.share({ title: p.name, text }).catch(() => {});
    else toast('Sharing works in the app');
  };
}

// ---- one artist ----

let ARTIST = null;
function openArtist(name) {
  if (!name) return;
  openPage('artistPage');
  ARTIST = { name, loading: true, req: ++REQ };
  requestDiscography(name);
  $('#artistBody').innerHTML = `<div class="empty"><p>Looking up ${esc(name)}…</p></div>`;
  send({ t: 'artist', name, range: statsRange, req: ARTIST.req });
}
window.onArtist = (a) => {
  ARTIST = deliver('artistPage', a.req, ARTIST, (s, shown) => {
    const next = { ...a, req: s.req };
    if (shown) { const changed = !sameData(next, s); ARTIST = next; if (changed) renderArtist(); }
    return next;
  });
};

function renderArtist() {
  const a = ARTIST;
  if (!a || a.loading || !isOpen('artistPage')) return;
  if (a.missing) {
    $('#artistBody').innerHTML = `<div class="hero"><h1>${esc(a.name)}</h1>${listenRow('artists', a.name)}</div><div class="empty small"><p>${(ST.history || {}).count > 0 ? 'No plays of this artist in your history yet.' : 'Import your Last.fm history on the Stats tab to see their story here.'}</p></div>
      <div id="discoSlot">${discoHtml(a.name)}</div>`;
    return;
  }
  const mo = (ms) => new Date(ms).toLocaleDateString([], { month: 'short', year: 'numeric' });
  const pic = a.photo || a.cover || '';
  let h = `<div class="artist-hero page-hero"><i class="glow"></i>
      ${holoCover(pic, a.name, a.name, { frame: true, key: `artist|${a.name}` })}
      <div class="ah-text"><span class="k">Artist</span><h1 class="fit">${esc(a.name)}</h1>
        <div class="meta">${plural(a.plays, 'play')} since ${esc(mo(a.first.ts))}</div></div></div>
    ${a.genres.length ? `<div class="tags">${a.genres.map((g) => `<button class="chip link" data-genre="${esc(genreKeyOf(g))}" data-glabel="${esc(g)}">${esc(g)}</button>`).join('')}</div>` : ''}
    ${listenRow('artists', a.name)}
    <p class="hint center">First heard ${esc(dateLabel(a.first.ts))}: ${esc(a.first.track)}</p>
    <div class="tiles">
      ${tile('Plays', num(a.plays), `on ${plural(a.days, 'day')}`, '', 'music')}
      ${tile(periodName(a.range), a.range.rank ? `#${a.range.rank}` : '–', a.range.plays ? plural(a.range.plays, 'play') : 'no plays', '', 'calendar')}
      ${tile('Busiest month', mo(a.busiestMonth.start), plural(a.busiestMonth.count, 'play'), '', 'chart')}
      ${tile('Longest streak', plural(a.streak, 'day'), a.streak > 1 ? `until ${dateLabel(a.streakEnd)}` : '', '', 'fire')}
    </div>`;
  h += vsBlock(a.global, esc(a.name));
  h += originHtml(a);
  h += `<div id="discoSlot">${discoHtml(a.name)}</div>`;
  h += lifeCharts(a, 'them', pic, a.name + a.name);
  if (STATS && STATS.record && STATS.record.days) {
    const idx = STATS.record.artists.findIndex((n) => norm(n) === norm(a.name));
    if (idx >= 0) {
      RECS.artist = { rec: STATS.record, spot: idx, label: periodName(STATS.range) };
      h += recordViz(STATS.record, 'artist', 'On your record', `Their plays lit up among everything you played (${periodName(STATS.range)}).`, { legend: false });
    }
  }
  if (a.tracks.length) {
    h += `<div class="viz"><h2>Top tracks</h2><span class="sub">Not music? ${icon('eye-off', 'i inl')} hides a track from your stats.</span><div class="rank">${a.tracks.map((x, i) => `<div class="r with-act" ${trackAttrs(a.name, x.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b></span><span class="v">${num(x.plays)}</span>
      <button class="icon-btn small" data-hide ${hideAttrs('track', a.name, x.title)} aria-label="Hide ${esc(x.title)} from stats">${icon('eye-off')}</button></div>`).join('')}</div></div>`;
  }
  if (a.albums.length) {
    h += `<div class="viz"><h2>Albums</h2><div class="rank">${a.albums.map((x) => `<button class="r with-cover" ${albumAttrs(a.name, x.title)} data-cover="${esc(x.cover)}">${coverHtml(x.cover, x.title, a.name, 'tiny')}<span class="t"><b>${esc(x.title)}</b><small>${x.owned ? 'on your shelf' : ''}</small></span><span class="v">${num(x.plays)}</span></button>`).join('')}</div></div>`;
  }
  h += globalTopHtml(a);
  if (a.owned.length) {
    h += `<div class="viz"><h2>On your shelf</h2><div class="covers">${a.owned.map((x) => `<button class="rec" data-rec="${x.id}">${coverHtml(x.cover, x.title, x.artist)}<b>${esc(x.title)}</b><small>${x.year || ''}</small></button>`).join('')}</div></div>`;
  }
  keepHolo($('#artistBody'), h);
  tint($('#artistBody'), pic, a.name + a.name);
  fitText($('#artistBody .fit'));
  drawRecords();
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
  return `<div class="r"><span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)} · ${plural(x.plays, 'play')} · last ${esc(dateLabel(x.last))}</small>${why ? `<span class="whys">${why}</span>` : ''}</span>
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
  body.innerHTML = h;
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
    if (isOpen('artistPage') && ARTIST && ARTIST.name) send({ t: 'artist', name: ARTIST.name, range: statsRange });
  }
  const what = it.kind === 'artist' ? it.artist : `“${it.title}”`;
  toast(t === 'hide' ? `${what} is hidden from your stats` : t === 'unhide' ? `${what} is back in your stats` : `${what} stays in your stats`);
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
  return `<div class="viz"><h2>Wishlist ideas</h2><span class="sub">Albums you stream a lot but don't own on vinyl. Tap to look on Discogs.</span><div class="rank">${w.map((x) =>
    `<button class="r with-cover" data-url="${esc(x.search)}">${coverHtml(x.image, x.album, x.artist, 'tiny')}<span class="t"><b>${esc(x.album)}</b><small>${esc(x.artist)} · ${x.tracks} tracks</small></span><span class="v">${plural(x.plays, 'play')}</span></button>`).join('')}</div></div>`;
}

function topViz(top) {
  if (!top) return '';
  const list = top[topKind] || [];
  const max = Math.max(1, ...list.map((x) => x.plays));
  const rows = list.map((x, i) => {
    const name = topKind === 'artists' ? x.name : x.title;
    const sub = topKind === 'artists' ? '' : x.artist;
    const open = topKind === 'tracks' ? trackAttrs(x.artist, x.title) : topKind === 'albums' ? `${albumAttrs(x.artist, x.title)} data-cover="${esc(x.cover)}"` : `data-artist="${esc(x.name)}"`;
    const pic = topKind === 'albums' ? coverHtml(x.cover, x.title, x.artist, 'tiny') : topKind === 'artists' ? coverHtml(x.photo, x.name, x.name, 'tiny round') : `<span class="n">${i + 1}</span>`;
    return `<button class="r ${topKind === 'tracks' ? '' : 'with-cover'}" ${open}>${pic}<span class="t"><b>${esc(name)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</span><span class="v">${num(x.plays)}</span><div class="bar"><i style="width:${(x.plays / max) * 100}%"></i></div></button>`;
  }).join('');
  return `<div class="viz"><h2>Top 10</h2><div class="seg small" id="topSeg">${['artists', 'albums', 'tracks'].map((k) =>
    `<button data-top="${k}" class="${k === topKind ? 'on' : ''}">${icon('check', 'i check')}${k.charAt(0).toUpperCase() + k.slice(1)}</button>`).join('')}</div>
    <div class="rank">${rows || '<p class="hint">Nothing in this period.</p>'}</div>
    ${rows ? `<div class="btn-row">${plButton('top', 'Playlist of the top 40')}</div>` : ''}</div>`;
}

function renderStats() {
  const s = STATS;
  const body = $('#statsBody');
  renderPeriod(s);
  if (!s || s.empty) { body.innerHTML = ''; return; }
  statsRange = s.range.id;
  const sm = s.summary;
  const pv = sm.prev || {};
  const k = s.range.kind;
  if (!sm.scrobbles) {
    body.innerHTML = `<div class="empty">${icon('album', 'i big')}<p>No plays in ${esc(periodName(s.range))}.</p></div>`;
    return;
  }
  let h = storyCardHtml(s);
  h += `<div class="tiles">
      ${tile('Plays', compact(sm.scrobbles), `${sm.perDay} a day`, deltaHtml(sm.scrobbles, pv.scrobbles, k), 'music')}
      ${tile('Hours', `≈${compact(sm.hours)}`, 'at ~3.5 min a track', deltaHtml(sm.hours, pv.hours, k), 'schedule')}
      ${tile('Artists', compact(sm.artists), `${compact(sm.tracks)} different tracks`, deltaHtml(sm.artists, pv.artists, k), 'heart')}
      ${tile('New artists', sm.historyStartsHere ? '–' : compact(sm.newArtists), sm.historyStartsHere ? 'your history starts here' : sm.newArtists ? 'first time ever' : 'only familiar names', sm.historyStartsHere ? '' : deltaHtml(sm.newArtists, pv.newArtists, k), 'explore')}
    </div>`;
  h += tidyBanner(s.tidy);
  h += highlightsViz(s);
  if (!RECS.stats || RECS.stats.rec !== s.record) RECS.stats = { rec: s.record, spot: -1, label: periodName(s.range) };
  h += recordViz(s.record, 'stats', 'Your record') + timelineViz(s.timeline) + riverViz(s.river) + clockViz(s.clock, 'stats')
    + streakViz(s.streaks, s) + pulseViz(s.pulse) + newArtistsViz(s.newArtists) + genreViz(s.genres) + decadesViz(s.decades) + obsessionsViz(s.obsessions, s.loops)
    + rarelyViz(s.rarely) + wishlistViz(s.wishlist) + topViz(s.top);
  body.innerHTML = h;
  drawRecords();
}

/** Tap (or drag across) a heat cell, a column, a ridge or the river: its numbers show in the line under the chart. */
function onVizPointer(e, data) {
  const cell = e.target.closest('[data-heat]');
  if (cell) {
    const ro = $('#ro-' + cell.dataset.heat);
    $$('.heat i.sel').forEach((x) => x.classList.remove('sel'));
    cell.classList.add('sel');
    const d = +cell.dataset.d, hr = +cell.dataset.h;
    if (ro) ro.innerHTML = `<b>${plural(+cell.dataset.v, 'play')}</b> · ${DOW[d]}, ${pad(hr)}:00–${pad(hr + 1)}:00`;
    return;
  }
  const rvHit = e.target.closest('[data-rv]');
  if (rvHit && data && data.river) {
    const b = +rvHit.dataset.rv;
    const B = data.river.starts.length;
    const cur = $('#rvCursor');
    if (cur) { cur.setAttribute('x1', (b / (B - 1)) * 340); cur.setAttribute('x2', (b / (B - 1)) * 340); cur.setAttribute('visibility', 'visible'); }
    $('#ro-rv').innerHTML = riverText(data.river, b);
    return;
  }
  const ridge = e.target.closest('[data-ridge]');
  if (ridge && data && data.pulse && !e.target.closest('[data-artist]')) {
    $$('.ridges .row.sel').forEach((x) => x.classList.remove('sel'));
    ridge.classList.add('sel');
    $('#ro-pulse').innerHTML = pulseText(data.pulse, +ridge.dataset.ridge);
    return;
  }
  const col = e.target.closest('[data-col]');
  if (!col || !data) return;
  col.parentElement.querySelectorAll('.sel').forEach((x) => x.classList.remove('sel'));
  col.classList.add('sel');
  const i = +col.dataset.i;
  if (col.dataset.col === 'na') $('#ro-na').innerHTML = newArtistsText(data.newArtists.buckets[i], data.newArtists.unit);
  else if (col.dataset.col === 'g') $('#ro-g').innerHTML = genreText(data.genres, i);
  else if (col.dataset.col === 'tl') $('#ro-tl').innerHTML = `${esc(periodLabel(data.timeline.starts[i], data.timeline.unit))}: <b>${plural(data.timeline.counts[i], 'play')}</b>`;
  else if (col.dataset.col === 'am') {
    const d = new Date(data.monthsFrom);
    d.setMonth(d.getMonth() + i);
    col.closest('.viz').querySelector('.readout').innerHTML = `${esc(d.toLocaleDateString([], { month: 'long', year: 'numeric' }))}: <b>${plural(data.months[i], 'play')}</b>`;
  } else if (col.dataset.col === 'ah') col.closest('.viz').querySelector('.readout').innerHTML = `${pad(i)}:00–${pad(i + 1)}:00: <b>${plural(data.hours[i], 'play')}</b>`;
  else if (col.dataset.col === 'aw') {
    const g = +col.dataset.g || 1;
    const from = data.weeksFrom + i * g * 7 * 864e5;
    const n = (data.weeks || []).slice(i * g, i * g + g).reduce((a, b) => a + b, 0);
    const day = (ms) => new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
    col.closest('.viz').querySelector('.readout').innerHTML = `${g === 1 ? `Week of ${esc(day(from))}` : `${esc(day(from))} – ${esc(day(from + (g * 7 - 1) * 864e5))}`}: <b>${plural(n, 'play')}</b>`;
  }
}

// ---------- the story: full-screen cards about a period ----------

const PERSONAS = {
  nightOwl: { title: 'Night Owl', icon: 'moon', why: (m) => `${pct(m.night)} of your plays happened between 22:00 and 04:00. The good stuff comes out after dark.` },
  earlyBird: { title: 'Early Bird', icon: 'sun', why: (m) => `${pct(m.morning)} of your plays came between 5 and 9 in the morning. Music before coffee.` },
  loyalist: { title: 'Loyalist', icon: 'heart', why: (m) => `${esc(m.topArtist)} alone made up ${pct(m.topShare)} of your listening. When you love something, you stay.` },
  explorer: { title: 'Explorer', icon: 'explore', why: (m) => `${pct(m.newShare)} of your plays were artists you had never heard before.` },
  onRepeat: { title: 'Repeat Offender', icon: 'repeat', why: (m) => (m.loop >= 5 ? `You played one song ${m.loop} times in a row. No regrets.` : `One song got ${m.obsession} plays in a single week.`) },
  weekender: { title: 'Weekend Warrior', icon: 'weekend', why: (m) => `${pct(m.weekend)} of your plays fell on Saturdays and Sundays.` },
  crateDigger: { title: 'Crate Digger', icon: 'album', why: (m) => `${pct(m.vinyl)} of everything you played came off your record shelf.` },
  genreHopper: { title: 'Genre Hopper', icon: 'shuffle', why: (m) => `${m.genres} different genres, each with a real share of your plays.` },
  allRounder: { title: 'All-Rounder', icon: 'star', why: () => 'No single habit stands out: a bit of everything, at any time of day.' },
};

let STORY = null;
let storySlides = [];
let storyIdx = 0;

function openStory(id) {
  STORY = null;
  storySlides = [{ cls: 'deep', wait: true, html: '<div class="t m in">Putting it together…</div>' }];
  $('#story').hidden = false;
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
  $('#storySlide').innerHTML = '';
  Tilt.refresh();
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
  const seed = x ? periodName(x.range) : 'Record Shelf';
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
  const label = cover ? `background-image:url('${esc(cover)}')` : '';
  return `<div class="single in" style="--h:${hue(seed)}"><div class="sv"><i class="lab" style="${label}">${cover ? '' : '<b>45</b>'}</i><i class="hole"></i></div></div>`;
}

function buildSlides(s) {
  const out = [];
  const name = periodName(s.range);
  const per = periodWord(s.range.kind);
  const sm = s.summary;
  if (!sm.scrobbles) return [{ cls: 'deep', dur: 5, html: `<div class="t m in">No plays in ${esc(name)}.</div>` }];
  const album = (s.albums || [])[0];
  const disc = (cover, hueKey) => `<div class="spinner-disc bg" style="--h:${hue(hueKey)}"><i style="${cover ? `background-image:url('${esc(cover)}')` : ''}"></i></div>`;
  // Intro: a record rising from the bottom of the screen
  out.push({ cls: 'deep intro', dur: 5, html: `${disc(album && album.cover, name)}<div class="k in">Record Shelf · ${s.range.kind === 'all' ? 'your whole history' : `your ${per}`}</div>
    <div class="t xl in" ${dly(0.15)} data-fit="3">${esc(name)}</div><p class="p in" ${dly(0.35)}>on record.</p>
    <p class="hint bg in" ${dly(0.9)}>Tap right for next, left for back, hold to pause</p>` });
  // Volume: one huge number and a ticker running across
  let vs = '';
  if (sm.prev && sm.prev.scrobbles > 0) {
    const p = Math.round(((sm.scrobbles - sm.prev.scrobbles) * 100) / sm.prev.scrobbles);
    vs = `<span class="badge in" ${dly(0.6)}>${icon(p >= 0 ? 'trend-up' : 'down-trend')}${Math.abs(p)}% ${p >= 0 ? 'more' : 'less'} than ${esc(lastWord(s.range.kind))}</span>`;
  }
  const tick = `≈ ${num(sm.hours)} hours of music · ${sm.days} of ${sm.spanDays} days · ${num(sm.scrobbles)} plays · `;
  out.push({ cls: 'bright', html: `<div class="k in">You played</div><div class="t huge in" ${dly(0.1)}><span data-count="${sm.scrobbles}">0</span></div>
    <div class="k in" ${dly(0.2)}>tracks</div>
    <p class="p in" ${dly(0.3)}>That's about <b>${num(sm.hours)} hours</b> of music, on ${sm.days} of ${sm.spanDays} days.</p>${vs}
    <div class="ticker bg"><div><span>${esc(tick.repeat(3))}</span><span>${esc(tick.repeat(3))}</span></div></div>` });
  // Personality: its icon inside a turning ring of words
  const ps = s.personality;
  if (ps && PERSONAS[ps.id]) {
    const P = PERSONAS[ps.id];
    const R = ps.runnerUp && PERSONAS[ps.runnerUp];
    const words = 'Your listening personality · '.toUpperCase().repeat(2);
    out.push({ cls: 'deep', html: `<div class="ring in"><svg viewBox="0 0 200 200"><defs><path id="ringPath" d="M100,100 m-82,0 a82,82 0 1,1 164,0 a82,82 0 1,1 -164,0"/></defs>
      <text><textPath href="#ringPath" textLength="512" lengthAdjust="spacing">${words}</textPath></text></svg><span class="core">${icon(P.icon)}</span></div>
      <div class="t m in" ${dly(0.2)} data-fit="2">${P.title}</div><p class="p in" ${dly(0.4)}>${P.why(ps.metrics)}</p>
      ${R ? `<span class="badge in" ${dly(0.7)}>${icon(R.icon)}with a touch of ${R.title}</span>` : ''}` });
  }
  // Top artist: the photo full-bleed in two colors, the name across the bottom
  const a0 = (s.artists || [])[0];
  if (a0) {
    const bg = a0.cover
      ? `<div class="duo bg"><i class="img" style="background-image:url('${esc(a0.cover)}')"></i><i class="lo"></i><i class="hi"></i><i class="fade"></i></div>`
      : `<div class="duo bg none"><b>${esc(String(a0.name).trim().charAt(0).toUpperCase())}</b><i class="fade"></i></div>`;
    out.push({ cls: 'deep photo', html: `${bg}<div class="k in">Your top artist</div>
      <div class="t xl in" ${dly(0.15)} data-fit="3">${esc(a0.name)}</div><p class="p mono in" ${dly(0.35)}>${plural(a0.plays, 'play')} · ${pct(a0.share)} of everything · on ${plural(a0.days, 'day')}</p>` });
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
      ${s.tracks.length > 1 ? `<p class="p mono small in" ${dly(0.7)}>Then: ${s.tracks.slice(1, 4).map((x) => esc(x.title)).join(' · ')}</p>` : ''}` });
  }
  // Album, as a card that moves with the phone
  if (album) {
    out.push({ cls: 'deep', html: `<div class="k in">${ofThe(s.range.kind, 'Album')}</div><div class="in" ${dly(0.1)}>${holoCover(album.cover, album.title, album.artist, { owned: album.owned, key: 'story-album' })}</div>
      <div class="t m in" ${dly(0.25)} data-fit="2">${esc(album.title)}</div><p class="p in" ${dly(0.4)}>${esc(album.artist)} · ${plural(album.plays, 'play')}${album.owned ? ' · on your shelf' : ''}</p>` });
  }
  // Golden hour, on a sundial
  const gh = s.goldenHour;
  const mood = gh >= 5 && gh < 10 ? 'Mornings are yours.' : gh >= 10 && gh < 17 ? 'Music through the day.' : gh >= 17 && gh < 22 ? 'Evenings are for records.' : 'The night shift.';
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
      <p class="p in" ${dly(0.5)}>${esc(dateLabel(st.longestFrom))} – ${esc(dateLabel(st.longestTo))}.${s.busiestDay ? ` Biggest day: <b>${esc(dayName(s.busiestDay.day))}</b>, ${plural(s.busiestDay.count, 'play')}.` : ''}</p>` });
  }
  // Summary: the back of the sleeve, as a poster
  const P = ps && PERSONAS[ps.id];
  const side = (list, letter, f) => list.map((x, i) => `<p><span>${letter}${i + 1}</span><b>${esc(f(x))}</b><em>${num(x.plays)}</em></p>`).join('');
  out.push({ cls: 'paper poster', dur: 14, html: `<div class="sheet in">
      <div class="ph"><span>Record Shelf</span><span>RS–${esc(String(s.range.id).toUpperCase())}</span></div>
      <div class="ptop"><div><div class="pt" data-fit="2">${esc(name)}</div><div class="ps">on record · ${num(sm.scrobbles)} plays · ≈${num(sm.hours)} h</div></div>${coverHtml(album && album.cover, (album && album.title) || name, '', 'stamp')}</div>
      <h6>Side A · Artists</h6>${side(s.artists || [], 'A', (x) => x.name)}
      <h6>Side B · Songs</h6>${side(s.tracks || [], 'B', (x) => x.title)}
      <div class="pnums"><div><small>Top genre</small><b>${gs.length ? esc(gs[0].name) : '–'}</b></div><div><small>Personality</small><b>${P ? P.title : '–'}</b></div><div><small>Golden hour</small><b>${pad(gh)}:00</b></div></div>
      <div class="pfoot"><span class="code"></span><small>Pressed from your Last.fm history</small></div>
    </div>` });
  return out;
}

// ---------- settings ----------

function statusLine(dot, title, sub) {
  return `<span class="dot ${dot}"></span><span class="grow">${title}${sub ? `<small>${sub}</small>` : ''}</span>`;
}

function renderServices() {
  const on = services();
  $('#svcChips').innerHTML = Object.entries(SERVICES).map(([k, s]) => `<button class="chip ${on.includes(k) ? 'on' : ''}" data-svc="${k}">${on.includes(k) ? icon('check', 'i small') : ''}${esc(s.name)}</button>`).join('');
}

function renderTidalSettings() {
  const t = ST.tidal || {};
  let st;
  if (t.busy) st = statusLine('busy', 'Talking to TIDAL…', '');
  else if (t.loggedIn) st = statusLine('ok', 'Connected and signed in', 'Exact links, and playlists straight into TIDAL');
  else if (t.hasApp) st = statusLine('ok', 'Exact links are on', t.builtIn ? 'Sign in to make playlists' : `Client ID ${esc(t.clientHint || '')} · sign in to make playlists`);
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

function renderSettings() {
  const l = ST.lastfm || {}, d = ST.discogs || {};
  $$('#ambSeg button').forEach((b) => b.classList.toggle('on', b.dataset.v === ambientMode()));
  renderServices();
  renderTidalSettings();
  const td = (STATS && STATS.tidy) || {};
  const more = td.count ? `${plural(td.count, 'track')} (${plural(td.plays, 'play')}) look like videos or apps.` : '';
  $('#tidyStatus').textContent = td.hiddenPlays ? `${plural(td.hiddenPlays, 'play')} hidden from your stats. ${more}`
    : more ? `${more} Hide them to keep your stats about music; nothing changes on Last.fm.`
      : 'Videos, app sounds and other non-music scrobbles can be hidden from your stats. Nothing changes on Last.fm.';
  const mode = showKeyForm || !l.hasKey ? 'setup' : l.connected ? 'connected' : l.waiting ? 'waiting' : l.needsAuth ? 'reconnect' : 'setup';
  const lfStatus = {
    connected: statusLine('ok', `Connected as <b>${esc(l.user)}</b>`, l.keyHint ? `API key ${esc(l.keyHint)}` : ''),
    waiting: statusLine('busy', 'Waiting for your approval', 'in the browser'),
    reconnect: statusLine('', 'Access expired', l.user ? `was ${esc(l.user)}` : ''),
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
  if (d.connected && d.syncing) dgStatus = statusLine('busy', `Syncing <b>${esc(d.user)}</b>`, d.pages ? `${plural(d.done, 'record')} · page ${d.page} of ${d.pages}` : 'starting…');
  else if (d.connected) dgStatus = statusLine('ok', `Connected as <b>${esc(d.user)}</b>`, `${plural(d.count || 0, 'record')}${d.public ? ' · public collection' : ''}${d.lastSync ? ` · synced ${ago(d.lastSync)}` : ''}`);
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
  $('#defLen').innerHTML = DEF_LENGTHS.map((s) => `<button class="chip ${s === def ? 'on' : ''}" data-sec="${s}">${s === def ? icon('check') : ''}${fmtDur(s)}</button>`).join('');
  $$('#themeSeg button').forEach((b) => b.classList.toggle('on', b.dataset.v === (ST.prefs.theme || 'system')));
  $$('#lookSeg button').forEach((b) => b.classList.toggle('on', b.dataset.v === lookMode()));
  try { $('#tiltSw').checked = localStorage.getItem('tilt') !== 'off'; } catch (_) { $('#tiltSw').checked = true; }
  $('#version').textContent = ST.version ? `Record Shelf ${ST.version}` : '';
  renderPhoneSettings();
}

// ---------- wiring ----------

function build() {
  $('#tabs').onclick = (e) => { const b = e.target.closest('button'); if (b) showTab(b.dataset.tab); };
  $('#settingsBtn').onclick = () => openSettings();
  $('#syncBtn').onclick = () => { send({ t: 'sync' }); toast('Syncing your collection…'); };
  document.addEventListener('click', (e) => {
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
    const pl = e.target.closest('[data-pl]');
    if (pl) { openPlaylist(pl.dataset.pl); return; }
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
    const atl = e.target.closest('[data-atlas]');
    if (atl) { openAtlas(atl.dataset.atlas || ''); return; }
    if (e.target.closest('[data-gpick]')) { openGenreSheet(); return; }
    if (e.target.closest('[data-gclear]')) { setStatsGenre(''); return; }
    if (e.target.closest('[data-dpick]')) { openDecadeSheet(); return; }
    if (e.target.closest('[data-dclear]')) { setStatsDecade(0); return; }
    const sd = e.target.closest('[data-setdecade]');
    if (sd) { setStatsDecade(+sd.dataset.setdecade === statsDecade ? 0 : +sd.dataset.setdecade); return; }
    const canvas = e.target.closest('canvas[data-record]');
    if (canvas) { onRecordTap(canvas, e); return; }
    const ar = e.target.closest('[data-artist]');
    if (ar) { openArtist(ar.dataset.artist); return; }
    const go = e.target.closest('[data-go]');
    if (go) { openSettings(go.dataset.go); return; }
    const url = e.target.closest('[data-url]');
    if (url) openUrl(url.dataset.url);
  });
  $('#scrim').onclick = closeSheet;

  let searchTimer = 0;
  $('#search').addEventListener('input', (e) => {
    shelf.q = e.target.value;
    $('#searchClear').hidden = !shelf.q;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(renderShelf, 120);
  });
  $('#searchClear').onclick = () => { $('#search').value = ''; shelf.q = ''; $('#searchClear').hidden = true; renderShelf(); };
  $('#sortChips').onclick = (e) => {
    const b = e.target.closest('[data-sort]');
    if (!b) return;
    if (shelf.sort === b.dataset.sort) shelf.rev = !shelf.rev;
    else { shelf.sort = b.dataset.sort; shelf.rev = false; }
    renderShelf();
    try { localStorage.setItem('shelfSort', JSON.stringify({ sort: shelf.sort, rev: shelf.rev })); } catch (_) { /* preview without storage */ }
  };
  $('#grid').onclick = (e) => { const b = e.target.closest('.rec'); if (b) openRecord(+b.dataset.id); };
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
  $('#pPrev').onclick = () => { if (STATS && STATS.range.prev) requestStats(STATS.range.prev); };
  $('#pNext').onclick = () => { if (STATS && STATS.range.next) requestStats(STATS.range.next); };
  const statsBody = $('#statsBody');
  statsBody.addEventListener('pointerdown', (e) => onVizPointer(e, STATS));
  statsBody.addEventListener('pointermove', (e) => { if (e.buttons) onVizPointer(e, STATS); });
  statsBody.onclick = (e) => {
    const sc = e.target.closest('[data-story]');
    if (sc) { openStory(sc.dataset.story); return; }
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
    if (rec) { openRecord(+rec.dataset.rec); return; }
    const tk = e.target.closest('[data-top]');
    if (tk) { topKind = tk.dataset.top; renderStats(); }
  };
  const artistBody = $('#artistBody');
  artistBody.addEventListener('pointerdown', (e) => onVizPointer(e, ARTIST));
  artistBody.onclick = (e) => { const rec = e.target.closest('[data-rec]'); if (rec) openRecord(+rec.dataset.rec); };
  $('#trackBody').addEventListener('pointerdown', (e) => onVizPointer(e, TRACK));
  $('#albumBody').addEventListener('pointerdown', (e) => onVizPointer(e, ALBUM));
  $('#albumBody').onclick = (e) => { const rec = e.target.closest('[data-rec]'); if (rec) openRecord(+rec.dataset.rec); };
  $('#genreBody').addEventListener('pointerdown', (e) => onVizPointer(e, GENRE));
  $('#genreBody').onclick = (e) => {
    const rec = e.target.closest('[data-rec]');
    if (rec) openRecord(+rec.dataset.rec);
    if (e.target.closest('[data-genreall]')) { GENRE.showAll = true; renderGenre(); }
  };
  watchBar(window, $('#appbar'), (away) => document.body.classList.toggle('bar-away', away));
  $$('.page').forEach((p) => { const bar = p.querySelector(':scope > .appbar'); if (bar) watchBar(p, bar); });
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
  $('#exploreBody').onclick = (e) => {
    if (e.target.closest('#exploreGoStats')) showTab('stats');
    if (e.target.closest('#openVs')) openVs();
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
  $('#recordDiscogs').onclick = () => {
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
  $('#tdForget').onclick = () => confirmSheet('Disconnect TIDAL?', 'Record Shelf forgets your TIDAL app and login. Playlists already in TIDAL stay there.', 'Disconnect', () => send({ t: 'tidalForget' }));
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
  $('#themeSeg').onclick = (e) => { const b = e.target.closest('button'); if (b) send({ t: 'setTheme', v: b.dataset.v }); };
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
      <p class="body">The backup replaces this app's history, hide list, activity, collection and settings. Record Shelf starts again afterwards; then connect Last.fm (and TIDAL) again.</p>
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
