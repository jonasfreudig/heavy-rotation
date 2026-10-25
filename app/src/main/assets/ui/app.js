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

let ST = { lastfm: {}, discogs: {}, prefs: { theme: 'system', defaultTrackSec: 210 }, queue: {}, spin: null, version: '' };
let COLL = [];
let ACT = [];
let CMP = null; // last comparison of shadow mode with Last.fm
let STATS = null; // stats for the chosen range (see Stats.kt)
let REVIEW = null; // year in review
let statsRange = '12m';
let topKind = 'artists';
const REL = {};
let tab = 'shelf';
const pageStack = [];
let recordId = 0;
let showKeyForm = false;
const shelf = { q: '', sort: 'added', rev: false };

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

/** A big cover as a sleeve that tilts with the phone (see Tilt), optionally with its record sliding out. */
function holoCover(url, title, artist, opts = {}) {
  const lbl = url ? `background-image:url('${esc(url)}')` : '';
  const disc = opts.disc ? `<div class="disc"><div class="vinyl"><i class="lbl" style="--h:${hue((title || '') + (artist || ''))};${lbl}"></i></div></div>` : '';
  return `<div class="holo ${opts.disc ? 'with-disc' : ''}" data-holo data-key="${esc(opts.key || url || title)}">
    <div class="h3d">${disc}<div class="sleeve">${coverHtml(url, title, artist, 'big')}<i class="sheen"></i><i class="glare"></i></div></div></div>`;
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
    this.els = top ? $$(`#${top} [data-holo]`) : [];
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
    const m = Math.min(1, Math.hypot(this.x, this.y)).toFixed(3);
    for (const el of this.els) {
      el.style.setProperty('--tx', this.x.toFixed(3));
      el.style.setProperty('--ty', this.y.toFixed(3));
      el.style.setProperty('--m', m);
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
window.onActivity = (a) => { ACT = a || []; renderActivity(); };
window.onRelease = (r) => {
  const old = REL[r.id];
  if ((r.loading || r.error) && old && old.tracks) return;
  REL[r.id] = r;
  if (isOpen('recordPage') && recordId === r.id) renderRecord();
};
window.onToast = (m) => toast(m);
window.onCompare = (c) => { CMP = c; renderPhoneCard(); if (isOpen('comparePage')) renderCompare(); };
window.onStats = (s) => { STATS = s; $('#statsBody').classList.remove('stale'); renderStats(); };
window.onReview = (r) => { REVIEW = r; $('#reviewBody').classList.remove('stale'); renderReview(); };
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
  $('#appbarTitle').textContent = { shelf: 'Shelf', stats: 'Stats', activity: 'Activity' }[name];
  window.scrollTo(0, 0);
  renderState();
  if (name === 'stats') requestStats();
}
const isOpen = (id) => !$('#' + id).hidden;
function openPage(id) {
  const p = $('#' + id);
  if (p.hidden) { p.hidden = false; pageStack.push(id); }
  else { pageStack.splice(pageStack.indexOf(id), 1); pageStack.push(id); }
  p.style.zIndex = 10 + pageStack.length;
  p.scrollTop = 0;
  Tilt.refresh();
}
function closePage() {
  const id = pageStack.pop();
  if (id) $('#' + id).hidden = true;
  if (id === 'settingsPage') showKeyForm = false;
  Tilt.refresh();
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
  if (!sp) { spinCardSig = ''; slot.innerHTML = ''; return; }
  const sig = sp.id + '|' + sp.cover;
  if (sig !== spinCardSig) {
    spinCardSig = sig;
    slot.innerHTML = `<button class="spin-card" id="spinCard">${coverHtml(sp.cover, sp.album, sp.artist, 'small')}
      <div class="txt"><small id="scLabel"></small><b id="scTitle"></b><span>${esc(sp.artist)} — ${esc(sp.album)}</span></div>
      <span class="eq plain"><i></i><i></i><i></i></span><div class="bar"><i id="scBar"></i></div></button>`;
  }
  tickUi();
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
    const holo = $('#spinBody [data-holo]');
    if (holo) holo.classList.toggle('spinning', !!it);
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
  const list = q ? COLL.filter((r) => q.split(/\s+/).every((w) => r._s.includes(w))) : COLL.slice();
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
  $('#shelfCount').textContent = shelf.q.trim() ? `${list.length} of ${plural(COLL.length, 'record')}` : plural(COLL.length, 'record');
  $('#grid').innerHTML = list.map((r) => {
    let meta = '';
    if (shelf.sort === 'plays') meta = `<small class="meta">${r.plays ? plural(r.plays, 'play') : 'no plays yet'}</small>`;
    else if (shelf.sort === 'year' && r.year) meta = `<small class="meta">${r.year}</small>`;
    return `<button class="rec" data-id="${r.id}">${coverHtml(r.cover || r.thumb, r.title, r.artist, '', r.id === spinId)}<b>${esc(r.title)}</b><small>${esc(r.artist)}</small>${meta}</button>`;
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
  else if (COLL.length && !shownCount) h = `<p>No records match “${esc(shelf.q.trim())}”</p>`;
  e.innerHTML = h;
  e.hidden = !h;
}

// ---------- record page ----------

function openRecord(id) {
  recordId = id;
  openPage('recordPage');
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
      <div id="recHolo">${reuse ? '' : holoCover(coverUrl, title, artist, { disc: true, key: holoKey })}</div>
      <h1>${esc(title)}</h1>
      <div class="artist"><button class="artist-link" data-artist="${esc(artist)}">${esc(artist)}</button></div>
      <div class="meta">${esc(meta)}</div>
      <div class="stats">${plays ? `<button class="chip link" data-url="${esc(lastfmUrl(artist, title))}">${plural(plays, 'play')} on Last.fm${icon('open')}</button>` : ''}${spins ? `<span class="chip static">${plural(spins, 'spin')} logged here</span>` : ''}</div>
    </div>
    <div class="record-actions"><button class="btn filled big" id="scrobbleBtn" ${rel && rel.tracks.length ? '' : 'disabled'}>${icon('play')}Scrobble</button></div>
    <div id="recordSpinNote"></div>`;
  if (tags.length) h += `<div class="tags">${tags.map((t) => `<span class="chip static">${esc(t)}</span>`).join('')}</div>`;

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
  if (reuse) $('#recHolo').appendChild(keep);
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
  const keep = $('#spinBody [data-holo]');
  const reuse = keep && keep.dataset.key === sp.id;
  body.innerHTML = `<div class="spin-hero">
      <div id="spinHolo">${reuse ? '' : holoCover(sp.cover, sp.album, sp.artist, { disc: true, key: sp.id })}</div>
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
  return `<div class="item act">${coverHtml(e.cover, e.album || e.track, e.artist, 'tiny')}
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
  const bar = $('#pnBar'), text = $('#pnText');
  if (!n || !bar) return;
  const listened = n.listened + (n.playing ? Date.now() - n.at : 0);
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
const GENRE_COLORS = ['var(--g1)', 'var(--g2)', 'var(--g3)', 'var(--g4)', 'var(--g5)', 'var(--g6)'];
const num = (n) => Number(n || 0).toLocaleString();
function compact(n) {
  n = Number(n || 0);
  if (n >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
  if (n >= 1e4) return Math.round(n / 1e3) + 'K';
  if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K';
  return String(n);
}
const dateLabel = (ms) => new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
function periodLabel(ms, unit, short) {
  const d = new Date(ms);
  if (unit === 'year') return String(d.getFullYear());
  if (unit === 'quarter') return `Q${Math.floor(d.getMonth() / 3) + 1}${short ? '' : ' ' + d.getFullYear()}`;
  return d.toLocaleDateString([], short ? { month: 'short' } : { month: 'short', year: 'numeric' });
}

function requestStats() {
  if (!STATS || STATS.empty) { if ((ST.history || {}).count > 0) send({ t: 'stats', range: statsRange }); return; }
  $('#statsBody').classList.add('stale');
  send({ t: 'stats', range: statsRange });
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
    const pct = hs.total ? Math.round((hs.fetched / hs.total) * 100) : 0;
    h = `<div class="card"><div class="card-title">${icon('download')}Importing your history · ${pct}%</div>
      <p>${num(hs.fetched)} of ${hs.total ? num(hs.total) : '…'} scrobbles. Keep using your phone; it carries on in the background.</p>
      <div class="lin inline ${hs.total ? '' : 'indet'}" style="--p:${pct}%"><i></i></div>
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

const tile = (label, value, sub) => `<div class="tile"><span>${esc(label)}</span><b>${esc(value)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</div>`;

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
  const def = clock[peak] ? `Busiest: <b>${DOW[Math.floor(peak / 24)]}s, ${pad(peak % 24)}:00–${pad((peak % 24) + 1)}:00</b> (${num(clock[peak])} plays)` : 'No plays in this range';
  return `<div class="viz"><h2>${esc(title || 'When you listen')}</h2><span class="sub">Plays per weekday and hour</span>
    <div class="heat">${cells}</div><div class="readout" id="ro-${id}" data-def="${esc(def)}">${def}</div></div>`;
}

function streakViz(st) {
  const range = (a, b) => (a === b ? dateLabel(a) : `${dateLabel(a)} – ${dateLabel(b)}`);
  let h = `<div class="tiles">${tile('Current streak', plural(st.current, 'day'), st.current ? `since ${dateLabel(st.currentFrom)}` : 'no music today or yesterday')}
    ${tile('Longest streak', plural(st.longest, 'day'), st.longest ? range(st.longestFrom, st.longestTo) : '')}</div>`;
  if (st.artists && st.artists.length) {
    h += `<div class="viz"><h2>Artist streaks</h2><span class="sub">Most days in a row with the same artist</span><div class="rank">${st.artists.map((a, i) =>
      `<div class="r"><span class="n">${i + 1}</span><span class="t"><b>${esc(a.artist)}</b><small>${esc(range(a.from, a.to))}</small></span><span class="v">${plural(a.days, 'day')}</span></div>`).join('')}</div></div>`;
  }
  return h;
}

/** New artists per month (or year): columns in one hue; the first one of your history is grayed. */
function newArtistsViz(na) {
  const b = na.buckets || [];
  if (!b.length) return '';
  const max = Math.max(1, ...b.filter((x) => !x.historyStart).map((x) => x.count), b.length === 1 ? b[0].count : 0);
  const cols = b.map((x, i) => `<div class="c ${x.historyStart ? 'start' : ''}" data-col="na" data-i="${i}"><i style="height:${Math.min(100, (x.count / max) * 100)}%"></i></div>`).join('');
  const step = Math.max(1, Math.ceil(b.length / 4));
  const labels = b.map((x, i) => (i % step === 0 ? periodLabel(x.start, na.unit, true) : '')).filter((x, i) => i % step === 0);
  const last = b[b.length - 1];
  return `<div class="viz"><h2>New artists</h2><span class="sub">Artists you heard for the first time, per ${na.unit}</span>
    <div class="cols"><span class="ymax">${num(max)}</span>${cols}</div><div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div>
    <div class="readout" id="ro-na" data-def="${esc(newArtistsText(last, na.unit))}">${newArtistsText(last, na.unit)}</div></div>`;
}
function newArtistsText(x, unit) {
  if (!x) return '';
  const who = x.top && x.top.length ? ` — ${esc(x.top.join(', '))}` : '';
  return `${esc(periodLabel(x.start, unit))}: <b>${plural(x.count, 'new artist')}</b>${who}${x.historyStart ? ' (your history starts here, so everything is new)' : ''}`;
}

/** Genre shares over time: 100% stacked columns, fixed colors per genre, legend with the range's shares. */
function genreViz(g) {
  const periods = g.periods || [];
  if (!periods.length || !g.names || g.names.length < 2) return '';
  const n = g.names.length;
  const totals = new Array(n).fill(0);
  let all = 0;
  periods.forEach((p) => { p.shares.forEach((s, k) => { totals[k] += s * p.total; }); all += p.total; });
  const color = (k) => (k < n - 1 ? GENRE_COLORS[k] : 'var(--md-outline-variant)');
  const cols = periods.map((p, i) => {
    if (!p.total) return `<div class="c empty" data-col="g" data-i="${i}"></div>`;
    const segs = p.shares.map((s, k) => (s > 0 ? `<i style="flex:${s} 1 0;background:${color(k)}"></i>` : '')).join('');
    return `<div class="c" data-col="g" data-i="${i}">${segs}</div>`;
  }).join('');
  const step = Math.max(1, Math.ceil(periods.length / 4));
  const labels = periods.filter((x, i) => i % step === 0).map((p) => periodLabel(p.start, g.unit, true));
  const legend = g.names.map((name, k) => `<div><span class="sw" style="background:${color(k)}"></span><span class="nm">${esc(name)}</span><span class="pc">${all ? Math.round((totals[k] / all) * 100) : 0}%</span></div>`).join('');
  const cov = g.coverage || {};
  const pc = (x) => `${Math.round((x || 0) * 100)}%`;
  const last = periods.length - 1;
  return `<div class="viz"><h2>Genre drift</h2><span class="sub">Share of plays per ${g.unit}</span>
    <div class="stack">${cols}</div><div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div>
    <div class="readout" id="ro-g" data-def="${esc(genreText(g, last))}">${genreText(g, last)}</div>
    <div class="legend">${legend}</div>
    <p class="hint">Genres from Discogs styles for ${pc(cov.discogs)} of plays (records you own and their artists), Last.fm tags for ${pc(cov.lastfm)}, unknown for ${pc(cov.none)}.</p></div>`;
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
      `<button class="r" data-artist="${esc(o.artist)}"><span class="n">${i + 1}</span><span class="t"><b>${esc(o.track)}</b><small>${esc(o.artist)} · week of ${esc(dateLabel(o.week))}</small></span><span class="v">${num(o.count)}×</span></button>`).join('')}</div>`;
  }
  if (loops.length) {
    h += `<span class="sub" style="margin-top:${list.length ? 20 : 0}px">Back to back: the same song again and again, nothing in between</span><div class="rank">${loops.map((o, i) =>
      `<button class="r" data-artist="${esc(o.artist)}"><span class="n">${i + 1}</span><span class="t"><b>${esc(o.track)}</b><small>${esc(o.artist)} · ${esc(dateLabel(o.at))}</small></span><span class="v">${o.run}× in a row</span></button>`).join('')}</div>`;
  }
  return h + '</div>';
}

// ---- the record: every play as a dot on a disc ----

/** Record charts on screen: id -> {rec, spot (artist index or -1), label}. */
const RECS = {};

function recordViz(rec, id, title, sub) {
  if (!rec || !rec.days) return '';
  return `<div class="viz"><h2>${esc(title)}</h2><span class="sub">${esc(sub || 'Every play is a dot. Each groove is a day, from the label to today at the rim; noon is at the top, midnight at the bottom.')}</span>
    <div class="rec-wrap"><canvas data-record="${id}"></canvas></div>
    <div class="readout" id="ro-rec-${id}">${RECS[id] && RECS[id].spot >= 0 ? spotText(id) : 'Tap the record to read a play.'}</div></div>`;
}

function spotText(id) {
  const st = RECS[id];
  const name = st.rec.artists[st.spot];
  return `Showing <button class="artist-link" data-artist="${esc(name)}">${esc(name)}</button> (${plural(st.rec.plays[st.spot], 'play')}). Tap the label to show everyone.`;
}

function themeColor(name) { return getComputedStyle(document.documentElement).getPropertyValue('--md-' + name).trim(); }
function isDark() {
  const t = document.documentElement.dataset.theme;
  return t ? t === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
}

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
  const size = c.clientWidth || 300;
  c.width = Math.round(size * dpr);
  c.height = c.width;
  const ctx = c.getContext('2d');
  const g = recordGeometry(c, rec);
  // the vinyl, with faint grooves and a sheen
  ctx.fillStyle = '#121214';
  ctx.beginPath(); ctx.arc(g.cx, g.cy, g.R, 0, 2 * Math.PI); ctx.fill();
  ctx.strokeStyle = 'rgba(255,255,255,.035)';
  ctx.lineWidth = dpr;
  for (let r = g.r0; r < g.R; r += 3 * dpr) { ctx.beginPath(); ctx.arc(g.cx, g.cy, r, 0, 2 * Math.PI); ctx.stroke(); }
  // the plays
  const dark = isDark();
  const dot = themeColor(dark ? 'primary' : 'primary-container') || '#d0bcff';
  const hi = themeColor(dark ? 'tertiary' : 'tertiary-container') || '#efb8c8';
  const ds = Math.max(1.3 * dpr, Math.min(2.8 * dpr, g.step * 1.3));
  ctx.fillStyle = dot;
  ctx.globalAlpha = st.spot >= 0 ? 0.2 : 0.7;
  for (let i = 0; i < rec.d.length; i++) {
    if (rec.a[i] === st.spot) continue;
    const [x, y] = recordPos(g, rec, i);
    ctx.fillRect(x - ds / 2, y - ds / 2, ds, ds);
  }
  if (st.spot >= 0) {
    ctx.globalAlpha = 1;
    ctx.fillStyle = hi;
    const s2 = ds * 1.4;
    for (let i = 0; i < rec.d.length; i++) {
      if (rec.a[i] !== st.spot) continue;
      const [x, y] = recordPos(g, rec, i);
      ctx.fillRect(x - s2 / 2, y - s2 / 2, s2, s2);
    }
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
  const lab = st.spot >= 0 ? rec.artists[st.spot] : st.label;
  ctx.font = `500 ${Math.round(11 * dpr)}px Roboto, system-ui, sans-serif`;
  ctx.fillText(lab.length > 16 ? lab.slice(0, 15) + '…' : lab, g.cx, g.cy - 10 * dpr);
  ctx.font = `${Math.round(9 * dpr)}px Roboto, system-ui, sans-serif`;
  ctx.fillText(`${compact(st.spot >= 0 ? rec.plays[st.spot] : rec.d.length)} plays`, g.cx, g.cy + 9 * dpr);
  // the clock around the rim
  ctx.fillStyle = 'rgba(255,255,255,.5)';
  ctx.font = `${Math.round(9 * dpr)}px Roboto, system-ui, sans-serif`;
  const rt = g.R - (g.R - g.r1) / 2;
  [['12', 0, -1], ['18', 1, 0], ['0', 0, 1], ['6', -1, 0]].forEach(([t, x, y]) => ctx.fillText(t, g.cx + x * rt, g.cy + y * rt));
}

/** Tap on a record: the nearest play, its artist lit up across the disc. */
function onRecordTap(c, e) {
  const st = RECS[c.dataset.record];
  if (!st) return;
  const rec = st.rec;
  const box = c.getBoundingClientRect();
  const k = c.width / box.width;
  const px = (e.clientX - box.left) * k, py = (e.clientY - box.top) * k;
  const g = recordGeometry(c, rec);
  const ro = $('#ro-rec-' + c.dataset.record);
  if (Math.hypot(px - g.cx, py - g.cy) < g.r0) {
    st.spot = -1;
    drawRecord(c);
    if (ro) ro.textContent = 'Tap the record to read a play.';
    return;
  }
  let best = -1, bestD = 18 * k;
  for (let i = 0; i < rec.d.length; i++) {
    const [x, y] = recordPos(g, rec, i);
    const d = Math.hypot(x - px, y - py);
    if (d < bestD) { bestD = d; best = i; }
  }
  if (best < 0) return;
  st.spot = rec.a[best];
  drawRecord(c);
  const when = new Date(rec.start + rec.d[best] * 864e5);
  const name = rec.artists[rec.a[best]];
  if (ro) {
    ro.innerHTML = `${esc(when.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }))}, ${pad(Math.floor(rec.m[best] / 60))}:${pad(rec.m[best] % 60)} · <b>${esc(rec.tracks[rec.t[best]])}</b> by
      <button class="artist-link" data-artist="${esc(name)}">${esc(name)}</button> (${plural(rec.plays[rec.a[best]], 'play')}, lit up). Tap the label to show everyone.`;
  }
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
  const lab = (i) => new Date(p.start + i * p.binDays * 864e5).toLocaleDateString([], { month: 'short', year: '2-digit' });
  svg += `<text x="${labelW}" y="${H - 4}">${esc(lab(0))}</text><text x="${W}" y="${H - 4}" text-anchor="end">${esc(lab(p.bins - 1))}</text>`;
  return `<div class="viz"><h2>Phases</h2><span class="sub">Plays per ${p.binDays === 7 ? 'week' : `${p.binDays / 7} weeks`} of your ${rows.length} most played artists, ordered by when they peaked. Tap a name for the artist.</span>
    <div class="ridges"><svg viewBox="0 0 ${W} ${H}">${svg}</svg></div><div class="readout" id="ro-pulse">Tap a ridge for its peak.</div></div>`;
}

function pulseText(p, k) {
  const r = p.rows[k];
  const when = new Date(p.start + r.peak * p.binDays * 864e5);
  return `<b>${esc(r.artist)}</b>: peaked in the ${p.binDays === 7 ? 'week' : 'weeks'} of ${esc(dateLabel(+when))} with ${plural(r.counts[r.peak], 'play')}; ${plural(r.plays, 'play')} in this range`;
}

// ---- one artist ----

let ARTIST = null;
function openArtist(name) {
  if (!name) return;
  ARTIST = { name, loading: true };
  openPage('artistPage');
  $('#artistBody').innerHTML = `<div class="empty"><p>Looking up ${esc(name)}…</p></div>`;
  send({ t: 'artist', name, range: statsRange });
}
window.onArtist = (a) => { ARTIST = a; renderArtist(); };

function renderArtist() {
  const a = ARTIST;
  if (!a || a.loading || !isOpen('artistPage')) return;
  if (a.missing) {
    $('#artistBody').innerHTML = `<div class="hero"><h1>${esc(a.name)}</h1></div><div class="empty"><p>${(ST.history || {}).count > 0 ? 'No plays of this artist in your history yet.' : 'Import your Last.fm history on the Stats tab to see their story here.'}</p></div>`;
    return;
  }
  const mo = (ms) => new Date(ms).toLocaleDateString([], { month: 'short', year: 'numeric' });
  let h = `<div class="hero"><h1>${esc(a.name)}</h1>
    ${a.genres.length ? `<div class="tags">${a.genres.map((g) => `<span class="chip static">${esc(g)}</span>`).join('')}</div>` : ''}
    <div class="meta">First heard ${esc(dateLabel(a.first.ts))}: ${esc(a.first.track)}</div></div>
    <div class="tiles">
      ${tile('Plays', num(a.plays), `on ${plural(a.days, 'day')}`)}
      ${tile(a.range.label, a.range.rank ? `#${a.range.rank}` : '–', a.range.plays ? plural(a.range.plays, 'play') : 'no plays')}
      ${tile('Busiest month', mo(a.busiestMonth.start), plural(a.busiestMonth.count, 'play'))}
      ${tile('Longest streak', plural(a.streak, 'day'), a.streak > 1 ? `until ${dateLabel(a.streakEnd)}` : '')}
    </div>`;
  const mmax = Math.max(1, ...a.months);
  const step = Math.max(1, Math.ceil(a.months.length / 4));
  const mlabels = a.months.map((v, i) => { const d = new Date(a.monthsFrom); d.setMonth(d.getMonth() + i); return d; }).filter((d, i) => i % step === 0)
    .map((d) => d.toLocaleDateString([], { month: 'short', year: '2-digit' }));
  h += `<div class="viz"><h2>Plays per month</h2><span class="sub">Since you first played them</span>
    <div class="cols"><span class="ymax">${num(mmax)}</span>${a.months.map((v, i) => `<div class="c" data-col="am" data-i="${i}"><i style="height:${(v / mmax) * 100}%"></i></div>`).join('')}</div>
    <div class="xl">${mlabels.map((l) => `<span>${esc(l)}</span>`).join('')}</div><div class="readout" id="ro-am">Tap a month.</div></div>`;
  const hmax = Math.max(1, ...a.hours);
  let peak = 0;
  a.hours.forEach((v, i) => { if (v > a.hours[peak]) peak = i; });
  h += `<div class="viz"><h2>Time of day</h2><span class="sub">When you play them</span>
    <div class="cols">${a.hours.map((v, i) => `<div class="c" data-col="ah" data-i="${i}"><i style="height:${(v / hmax) * 100}%"></i></div>`).join('')}</div>
    <div class="xl"><span>00</span><span>06</span><span>12</span><span>18</span><span>23</span></div>
    <div class="readout" id="ro-ah">Mostly around <b>${pad(peak)}:00</b></div></div>`;
  if (STATS && STATS.record && STATS.record.days) {
    const idx = STATS.record.artists.findIndex((n) => norm(n) === norm(a.name));
    if (idx >= 0) {
      RECS.artist = { rec: STATS.record, spot: idx, label: STATS.range.label };
      h += recordViz(STATS.record, 'artist', `On your record`, `Their plays lit up among everything you played (${STATS.range.label.toLowerCase()}).`);
    }
  }
  if (a.tracks.length) {
    h += `<div class="viz"><h2>Top tracks</h2><div class="rank">${a.tracks.map((x, i) => `<div class="r"><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b></span><span class="v">${num(x.plays)}</span></div>`).join('')}</div></div>`;
  }
  if (a.albums.length) {
    h += `<div class="viz"><h2>Albums</h2><div class="rank">${a.albums.map((x) => `<div class="r with-cover">${coverHtml(x.cover, x.title, a.name, 'tiny')}<span class="t"><b>${esc(x.title)}</b><small>${x.owned ? 'on your shelf' : ''}</small></span><span class="v">${num(x.plays)}</span></div>`).join('')}</div></div>`;
  }
  if (a.owned.length) {
    h += `<div class="viz"><h2>On your shelf</h2><div class="covers">${a.owned.map((x) => `<button class="rec" data-rec="${x.id}">${coverHtml(x.cover, x.title, x.artist)}<b>${esc(x.title)}</b><small>${x.year || ''}</small></button>`).join('')}</div></div>`;
  }
  $('#artistBody').innerHTML = h;
  drawRecords();
}

function rarelyViz(r) {
  if (!r || !r.items || !r.items.length) return '';
  const sub = r.unplayed ? `${r.unplayed} of ${plural(r.owned, 'record')} you own had no plays in this range` : 'Your records, least played first';
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
    return `<button class="r" data-artist="${esc(topKind === 'artists' ? x.name : x.artist)}"><span class="n">${i + 1}</span><span class="t"><b>${esc(name)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</span><span class="v">${num(x.plays)}</span><div class="bar"><i style="width:${(x.plays / max) * 100}%"></i></div></button>`;
  }).join('');
  return `<div class="viz"><h2>Top 10</h2><div class="seg small" id="topSeg">${['artists', 'albums', 'tracks'].map((k) =>
    `<button data-top="${k}" class="${k === topKind ? 'on' : ''}">${icon('check', 'i check')}${k.charAt(0).toUpperCase() + k.slice(1)}</button>`).join('')}</div>
    <div class="rank">${rows || '<p class="hint">Nothing in this range.</p>'}</div></div>`;
}

function renderStats() {
  const s = STATS;
  const body = $('#statsBody');
  if (!s || s.empty) { body.innerHTML = ''; $('#rangeChips').innerHTML = ''; return; }
  statsRange = s.range.id;
  $('#rangeChips').innerHTML = s.ranges.map((r) => `<button class="chip ${r.id === s.range.id ? 'on' : ''}" data-range="${r.id}">${r.id === s.range.id ? icon('check') : ''}${esc(r.label)}</button>`).join('');
  const sm = s.summary;
  let h = `<div class="tiles">
      ${tile('Scrobbles', compact(sm.scrobbles), `${sm.perDay} a day`)}
      ${tile('Artists', compact(sm.artists), `${compact(sm.tracks)} different tracks`)}
      ${tile('Days with music', num(sm.days), `of ${num(sm.spanDays)}`)}
      ${tile('Albums', compact(sm.albums), '')}
    </div>`;
  if (s.range.id !== 'all') {
    h += `<button class="card tonal" data-review="${s.range.id}" style="display:block;width:100%;text-align:left"><div class="card-title">${icon('star')}${esc(s.range.id === '12m' ? 'Your last 12 months in music' : `Your ${s.range.label} in music`)}</div><p>Top artists, discoveries, your busiest day, records you played and more.</p></button>`;
  }
  if (!RECS.stats || RECS.stats.rec !== s.record) RECS.stats = { rec: s.record, spot: -1, label: s.range.label };
  h += recordViz(s.record, 'stats', 'Your record') + clockViz(s.clock, 'stats') + streakViz(s.streaks) + pulseViz(s.pulse)
    + newArtistsViz(s.newArtists) + genreViz(s.genres) + obsessionsViz(s.obsessions, s.loops) + rarelyViz(s.rarely)
    + wishlistViz(s.wishlist) + topViz(s.top);
  body.innerHTML = h;
  drawRecords();
}

/** Tap (or drag across) a heat cell or a column: its numbers show in the line under the chart. */
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
  else if (col.dataset.col === 'mo') $('#ro-mo').innerHTML = monthText(data, i);
  else if (col.dataset.col === 'am') {
    const d = new Date(data.monthsFrom);
    d.setMonth(d.getMonth() + i);
    $('#ro-am').innerHTML = `${esc(d.toLocaleDateString([], { month: 'long', year: 'numeric' }))}: <b>${plural(data.months[i], 'play')}</b>`;
  } else if (col.dataset.col === 'ah') $('#ro-ah').innerHTML = `${pad(i)}:00–${pad(i + 1)}:00: <b>${plural(data.hours[i], 'play')}</b>`;
}

// ---------- year in review ----------

function openReview(id) {
  openPage('reviewPage');
  $('#reviewBody').classList.add('stale');
  if (!REVIEW) $('#reviewBody').innerHTML = '<div class="empty"><p>Putting it together…</p></div>';
  send({ t: 'review', range: id });
}

function monthText(r, i) {
  const d = new Date(r.monthsFrom);
  d.setMonth(d.getMonth() + i);
  return `${esc(d.toLocaleDateString([], { month: 'long', year: 'numeric' }))}: <b>${plural(r.months[i], 'scrobble')}</b>`;
}

function renderReview() {
  const r = REVIEW;
  if (!r) return;
  $('#reviewChips').innerHTML = r.ranges.map((x) => `<button class="chip ${x.id === r.range.id ? 'on' : ''}" data-rv="${x.id}">${x.id === r.range.id ? icon('check') : ''}${esc(x.id === '12m' ? 'Last 12 months' : x.label)}</button>`).join('');
  const sm = r.summary;
  const label = r.range.id === '12m' ? 'Your last 12 months' : `Your ${r.range.label}`;
  if (!sm.scrobbles) {
    $('#reviewBody').innerHTML = `<div class="empty"><p>No scrobbles in ${esc(r.range.label)}.</p></div>`;
    return;
  }
  const rank = (list, f) => `<div class="rank">${list.map((x, i) => f(x, i)).join('')}</div>`;
  const ta = r.top.artists;
  let h = `<div class="rv-hero"><div class="yr">${esc(label)}</div><div class="big">${num(sm.scrobbles)}</div>
      <p>scrobbles · about ${sm.perDay} a day · music on ${num(sm.days)} of ${num(sm.spanDays)} days</p></div>`;
  if (ta.length) {
    h += `<div class="rv-card accent"><div class="kicker">Top artist</div><div class="head">${esc(ta[0].name)}</div><p>${plural(ta[0].plays, 'play')}</p></div>`;
    h += `<div class="rv-card"><div class="kicker">Top artists</div>${rank(ta, (x, i) => `<button class="r" data-artist="${esc(x.name)}"><span class="n">${i + 1}</span><span class="t"><b>${esc(x.name)}</b></span><span class="v">${num(x.plays)}</span></button>`)}</div>`;
  }
  if (r.top.albums.length) {
    h += `<div class="rv-card"><div class="kicker">Top albums</div>${rank(r.top.albums, (x) => `<div class="r with-cover">${coverHtml(x.cover, x.title, x.artist, 'tiny')}<span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)}${x.owned ? ' · on your shelf' : ''}</small></span><span class="v">${num(x.plays)}</span></div>`)}</div>`;
  }
  if (r.top.tracks.length) {
    h += `<div class="rv-card"><div class="kicker">Top tracks</div>${rank(r.top.tracks, (x, i) => `<div class="r"><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)}</small></span><span class="v">${num(x.plays)}</span></div>`)}</div>`;
  }
  h += `<div class="rv-card"><div class="kicker">New discoveries</div><div class="head">${plural(r.newArtists, 'new artist')}</div>
    <p>${r.historyStartsHere ? 'Your Last.fm history starts in this period, so every artist counts as new.' : 'Artists you had never scrobbled before.'}</p>
    ${r.topNew.length ? rank(r.topNew, (x, i) => `<div class="r"><span class="n">${i + 1}</span><span class="t"><b>${esc(x.name)}</b><small>first heard ${esc(dateLabel(x.first))}</small></span><span class="v">${num(x.plays)}</span></div>`) : ''}</div>`;
  if (r.busiestDay) {
    const max = Math.max(1, ...r.months);
    const cols = r.months.map((v, i) => `<div class="c" data-col="mo" data-i="${i}"><i style="height:${(v / max) * 100}%"></i></div>`).join('');
    const first = new Date(r.monthsFrom);
    const labels = r.months.map((v, i) => { const d = new Date(first); d.setMonth(d.getMonth() + i); return d.toLocaleDateString([], { month: 'narrow' }); });
    h += `<div class="rv-card"><div class="kicker">Busiest day</div><div class="head">${esc(new Date(r.busiestDay.day).toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' }))}</div>
      <p>${plural(r.busiestDay.count, 'scrobble')} that day</p>
      <div class="cols" style="margin-top:16px"><span class="ymax">${num(max)}</span>${cols}</div><div class="xl">${labels.map((l) => `<span>${esc(l)}</span>`).join('')}</div>
      <div class="readout" id="ro-mo">Scrobbles per month. Tap a month.</div></div>`;
  }
  if (r.streak && r.streak.longest > 1) {
    h += `<div class="rv-card"><div class="kicker">Longest streak</div><div class="head">${plural(r.streak.longest, 'day')} in a row</div><p>${esc(dateLabel(r.streak.longestFrom))} – ${esc(dateLabel(r.streak.longestTo))}</p></div>`;
  }
  if (r.obsession) {
    h += `<div class="rv-card"><div class="kicker">Obsession</div><div class="head">${esc(r.obsession.track)}</div><p>${esc(r.obsession.artist)} · ${r.obsession.count} plays in the week of ${esc(dateLabel(r.obsession.week))}</p></div>`;
  }
  const v = r.vinyl || {};
  h += `<div class="rv-card"><div class="kicker">On vinyl</div><div class="head">${plural(v.count || 0, 'scrobble')} from records</div>
    <p>${v.count ? `${Math.max(1, Math.round(v.share * 100))}% of your listening, scrobbled here from your shelf.` : 'Scrobble records here and they show up in your review.'}</p>
    ${v.top && v.top.length ? rank(v.top, (x) => `<div class="r with-cover">${coverHtml(x.cover, x.title, x.artist, 'tiny')}<span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)}</small></span><span class="v">${num(x.plays)}</span></div>`) : ''}</div>`;
  h += clockViz(r.clock, 'review', 'When you listened');
  if (r.first) h += `<div class="rv-card"><div class="kicker">First scrobble</div><div class="head">${esc(r.first.track)}</div><p>${esc(r.first.artist)} · ${esc(dateLabel(r.first.ts))}</p></div>`;
  $('#reviewBody').innerHTML = h;
}

// ---------- settings ----------

function statusLine(dot, title, sub) {
  return `<span class="dot ${dot}"></span><span class="grow">${title}${sub ? `<small>${sub}</small>` : ''}</span>`;
}

function renderSettings() {
  const l = ST.lastfm || {}, d = ST.discogs || {};
  const mode = showKeyForm || !l.hasKey ? 'setup' : l.connected ? 'connected' : l.waiting ? 'waiting' : l.needsAuth ? 'reconnect' : 'setup';
  const lfStatus = {
    connected: statusLine('ok', `Connected as <b>${esc(l.user)}</b>`, l.keyHint ? `API key ${esc(l.keyHint)}` : ''),
    waiting: statusLine('busy', 'Waiting for your approval', 'in the browser'),
    reconnect: statusLine('', 'Access expired', l.user ? `was ${esc(l.user)}` : ''),
    setup: statusLine(l.connected ? 'ok' : '', l.connected ? `Connected as <b>${esc(l.user)}</b>` : 'Not connected', l.connected ? 'Enter a new key to switch' : 'Scrobbles wait on this phone until you connect'),
  }[mode];
  $('#lfStatus').innerHTML = lfStatus;
  $('#lfSetup').hidden = mode !== 'setup';
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
  else if (d.connected) dgStatus = statusLine('ok', `Connected as <b>${esc(d.user)}</b>`, `${plural(d.count || 0, 'record')}${d.lastSync ? ` · synced ${ago(d.lastSync)}` : ''}`);
  else if (d.syncing) dgStatus = statusLine('busy', 'Checking the token…', '');
  else dgStatus = statusLine('', 'Not connected', 'Your collection shows up on the shelf once connected');
  $('#dgStatus').innerHTML = dgStatus;
  $('#dgSetup').hidden = !!d.connected;
  $('#dgConnected').hidden = !d.connected;
  $('#dgSave').disabled = !!d.syncing;
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
  $('#rangeChips').onclick = (e) => {
    const b = e.target.closest('[data-range]');
    if (!b || b.dataset.range === statsRange) return;
    statsRange = b.dataset.range;
    requestStats();
  };
  const statsBody = $('#statsBody');
  statsBody.addEventListener('pointerdown', (e) => onVizPointer(e, STATS));
  statsBody.addEventListener('pointermove', (e) => { if (e.buttons) onVizPointer(e, STATS); });
  statsBody.onclick = (e) => {
    const rv = e.target.closest('[data-review]');
    if (rv) { openReview(rv.dataset.review); return; }
    const rec = e.target.closest('[data-rec]');
    if (rec) { openRecord(+rec.dataset.rec); return; }
    const tk = e.target.closest('[data-top]');
    if (tk) { topKind = tk.dataset.top; renderStats(); }
  };
  const artistBody = $('#artistBody');
  artistBody.addEventListener('pointerdown', (e) => onVizPointer(e, ARTIST));
  artistBody.onclick = (e) => { const rec = e.target.closest('[data-rec]'); if (rec) openRecord(+rec.dataset.rec); };
  $('#artistLastfm').onclick = () => { if (ARTIST && ARTIST.name) openUrl(lastfmUrl(ARTIST.name)); };
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
  const reviewBody = $('#reviewBody');
  reviewBody.addEventListener('pointerdown', (e) => onVizPointer(e, REVIEW));
  reviewBody.addEventListener('pointermove', (e) => { if (e.buttons) onVizPointer(e, REVIEW); });
  $('#reviewChips').onclick = (e) => {
    const b = e.target.closest('[data-rv]');
    if (!b) return;
    $('#reviewBody').classList.add('stale');
    send({ t: 'review', range: b.dataset.rv });
  };
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

  try {
    const s = JSON.parse(localStorage.getItem('shelfSort') || 'null');
    if (s && SORTS.some((x) => x.id === s.sort)) { shelf.sort = s.sort; shelf.rev = !!s.rev; }
  } catch (_) { /* preview without storage */ }
  setInterval(tickUi, 1000);
  renderShelf();
}

document.addEventListener('DOMContentLoaded', () => {
  build();
  if (!Native) Mock.init();
  send({ t: 'hello' });
});
