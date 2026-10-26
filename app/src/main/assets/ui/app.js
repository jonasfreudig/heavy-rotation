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
const shelf = { q: '', sort: 'added', rev: false, decade: 0, genre: '' };

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
  return `<div class="holo ${owned ? 'foil' : 'plain'} ${withDisc ? 'with-disc' : ''} ${opts.frame ? 'framed' : ''}" data-holo data-key="${esc(opts.key || url || title)}">
    <div class="h3d">${disc}<div class="sleeve">${coverHtml(url, title, artist, 'big')}${foil}<i class="glare"></i></div></div></div>`;
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
  Tilt.refresh();
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
      <div id="recHolo">${reuse ? '' : holoCover(coverUrl, title, artist, { owned: true, key: holoKey })}</div>
      <h1>${esc(title)}</h1>
      <div class="artist"><button class="artist-link" data-artist="${esc(artist)}">${esc(artist)}</button></div>
      <div class="meta">${esc(meta)}</div>
      <div class="stats">${plays ? `<button class="chip link" data-url="${esc(lastfmUrl(artist, title))}">${plural(plays, 'play')} on Last.fm${icon('open')}</button>` : ''}${spins ? `<span class="chip static">${plural(spins, 'spin')} logged here</span>` : ''}${(ST.history || {}).count ? `<button class="chip link" ${albumAttrs(artist, title)} data-cover="${esc(coverUrl || '')}">${icon('chart')}Your stats</button>` : ''}</div>
      ${listenRow('albums', artist, title)}
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
      <div id="spinHolo">${reuse ? '' : holoCover(sp.cover, sp.album, sp.artist, { owned: true, key: sp.id })}</div>
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
  if (!STATS || STATS.empty) { if ((ST.history || {}).count > 0) send({ t: 'stats', range: statsRange, genre: statsGenre }); return; }
  $('#statsBody').classList.add('stale');
  send({ t: 'stats', range: statsRange, genre: statsGenre });
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
  return `<button class="story-card" data-story="${esc(s.range.id)}">
    <div class="spinner-disc" style="--h:${hue(name)}"><i style="${al.cover ? `background-image:url('${esc(al.cover)}')` : ''}"></i></div>
    <div class="k">${s.range.kind === 'all' ? 'Your whole history' : `Your ${periodWord(s.range.kind)}`}${s.genre ? ` in ${esc(genreLabel(s.genre))}` : ''}</div>
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
  const legend = g.names.map((name, k) => `<div><span class="sw" style="background:${color(k)}"></span><span class="nm">${esc(name)}</span><span class="pc">${Math.round((totals[k] / all) * 100)}%</span></div>`).join('');
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
/** Tints [el] by the picture at [url] (asking the app for its colors once), or by a hue from [seed] without a picture. */
function tint(el, url, seed) {
  if (!el) return;
  el.classList.add('tinted');
  el.dataset.tintUrl = url || '';
  const c = url ? TINTS[url] : null;
  el.setAttribute('style', tintStyle(c, url ? null : hue(seed || '')));
  if (url && !c) send({ t: 'palette', url });
}
window.onPalette = (p) => {
  TINTS[p.url] = p;
  $$('.tinted').forEach((el) => { if (el.dataset.tintUrl === p.url) el.setAttribute('style', tintStyle(p)); });
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
const genreLabel = (key) => ((STATS && STATS.genreOptions) || []).find((o) => o.key === key)?.label || key.replace(/\b\w/g, (c) => c.toUpperCase());
function renderGenreRow() {
  const row = $('#genreRow');
  if (!row) return;
  row.innerHTML = statsGenre
    ? `<button class="chip on" data-gclear>${icon('album', 'i small')}${esc(genreLabel(statsGenre))}${icon('close', 'i small')}</button><span class="hint inline">Only this genre's plays</span>`
    : `<button class="chip" data-gpick>${icon('album', 'i small')}All genres${icon('down', 'i small')}</button>`;
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

// ---- one song ----

let TRACK = null;
function openTrack(artist, title, extra = {}) {
  if (!artist || !title) return;
  openPage('trackPage');
  TRACK = { loading: true, artist, title, albumTitle: extra.album || '', cover: extra.cover || '', req: ++REQ };
  renderTrack();
  send({ t: 'track', artist, title, range: statsRange, req: TRACK.req });
}
window.onTrack = (t) => {
  TRACK = deliver('trackPage', t.req, TRACK, (s, shown) => {
    const next = { ...s, ...t, req: s.req, loading: false };
    if (shown) { TRACK = next; renderTrack(); }
    return next;
  });
};

function renderTrack() {
  const t = TRACK;
  if (!t || !isOpen('trackPage')) return;
  const al = t.album && typeof t.album === 'object' ? t.album : null;
  const albumTitle = al ? al.title : t.albumTitle;
  const cover = (al && al.cover) || t.cover;
  let h = `<div class="hero page-hero"><i class="glow"></i>${holoCover(cover, albumTitle || t.title, t.artist, { owned: !!(al && al.owned), key: `track|${t.artist}|${t.title}|${cover || ''}` })}
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
    h += lifeCharts(t, 'it', cover, (albumTitle || t.title) + t.artist);
    if (t.next && t.next.length) {
      h += `<div class="viz"><h2>What comes next</h2><span class="sub">What you played right after it</span><div class="rank">${t.next.map((x, i) =>
        `<button class="r" ${trackAttrs(x.artist, x.title)}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b><small>${esc(x.artist)}</small></span><span class="v">${x.count}×</span></button>`).join('')}</div></div>`;
    }
  }
  $('#trackBody').innerHTML = h;
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
    if (shown) { ALBUM = next; renderAlbum(); }
    return next;
  });
};

function renderAlbum() {
  const a = ALBUM;
  if (!a || !isOpen('albumPage')) return;
  const info = a.info || {};
  const cover = info.cover || a.cover;
  let h = `<div class="hero page-hero"><i class="glow"></i>${holoCover(cover, a.title, a.artist, { owned: !!info.owned, key: `album|${a.artist}|${a.title}|${cover || ''}` })}
    <h1>${esc(a.title)}</h1>
    <div class="artist"><button class="artist-link" data-artist="${esc(a.artist)}">${esc(a.artist)}</button></div>
    ${listenRow('albums', a.artist, a.title)}
    ${a.dzInfo && a.dzInfo.date ? `<div class="meta">${esc(a.dzInfo.date.slice(0, 4))}${a.dzInfo.label ? `, ${esc(a.dzInfo.label)}` : ''}</div>` : ''}
    ${info.owned ? `<div class="stats"><button class="chip link" data-rec="${info.owned}">${icon('album')}On your shelf</button></div>` : ''}</div>`;
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
    const tl = a.trackList || [];
    const tracklist = tracklistHtml(a, cover);
    if (tracklist) h += tracklist;
    else if (tl.length) {
      const max = Math.max(1, ...tl.map((x) => x.plays));
      h += `<div class="viz"><h2>The songs you play</h2><span class="sub">${plural(tl.length, 'different song')} from this album</span><div class="rank">${tl.map((x, i) =>
        `<button class="r" ${trackAttrs(a.artist, x.title, { album: a.title, cover })}><span class="n">${i + 1}</span><span class="t"><b>${esc(x.title)}</b></span><span class="v">${num(x.plays)}</span><div class="bar"><i style="width:${(x.plays / max) * 100}%"></i></div></button>`).join('')}</div></div>`;
    }
    h += lifeCharts(a, 'it', cover, a.title + a.artist);
  }
  $('#albumBody').innerHTML = h;
  tint($('#albumBody'), cover, a.title + a.artist);
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
  let h = `<div class="dig-card">${icon('explore', 'i big')}<div><h2>The long tail</h2>
    <p>You've played <b>${num(tail.artists)}</b> artists${tail.few ? `, <b>${num(tail.few)}</b> of them fewer than ${tail.fewPlays} times` : ''}. Here's where to dig.</p>
    <button class="btn filled" data-pl="dig">${icon('shuffle')}Crate dig: a random playlist</button></div></div>`;
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
    if (shown) { ARTIST = next; renderArtist(); }
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
      ${holoCover(pic, a.name, a.name, { frame: true, key: `artist|${a.name}|${pic}` })}
      <div class="ah-text"><span class="k">Artist</span><h1 class="fit">${esc(a.name)}</h1>
        <div class="meta">${plural(a.plays, 'play')} since ${esc(mo(a.first.ts))}</div></div></div>
    ${a.genres.length ? `<div class="tags">${a.genres.map((g) => `<span class="chip static">${esc(g)}</span>`).join('')}</div>` : ''}
    ${listenRow('artists', a.name)}
    <p class="hint center">First heard ${esc(dateLabel(a.first.ts))}: ${esc(a.first.track)}</p>
    <div class="tiles">
      ${tile('Plays', num(a.plays), `on ${plural(a.days, 'day')}`, '', 'music')}
      ${tile(periodName(a.range), a.range.rank ? `#${a.range.rank}` : '–', a.range.plays ? plural(a.range.plays, 'play') : 'no plays', '', 'calendar')}
      ${tile('Busiest month', mo(a.busiestMonth.start), plural(a.busiestMonth.count, 'play'), '', 'chart')}
      ${tile('Longest streak', plural(a.streak, 'day'), a.streak > 1 ? `until ${dateLabel(a.streakEnd)}` : '', '', 'fire')}
    </div>`;
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
  if (a.owned.length) {
    h += `<div class="viz"><h2>On your shelf</h2><div class="covers">${a.owned.map((x) => `<button class="rec" data-rec="${x.id}">${coverHtml(x.cover, x.title, x.artist)}<b>${esc(x.title)}</b><small>${x.year || ''}</small></button>`).join('')}</div></div>`;
  }
  $('#artistBody').innerHTML = h;
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
    + streakViz(s.streaks, s) + pulseViz(s.pulse) + newArtistsViz(s.newArtists) + genreViz(s.genres) + obsessionsViz(s.obsessions, s.loops)
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
  storySlides = [{ cls: 'c5', wait: true, html: '<div class="t m in">Putting it together…</div>' }];
  $('#story').hidden = false;
  showSlide(0);
  send({ t: 'story', range: id });
}
window.onStory = (s) => {
  if ($('#story').hidden) return;
  STORY = s;
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
  $('#storySlide').innerHTML = `<div class="slide ${sl.cls}">${sl.html}</div>`;
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
function buildSlides(s) {
  const out = [];
  const name = periodName(s.range);
  const per = periodWord(s.range.kind);
  const sm = s.summary;
  if (!sm.scrobbles) return [{ cls: 'c4', dur: 5, html: `<div class="t m in">No plays in ${esc(name)}.</div>` }];
  const album = (s.albums || [])[0];
  const disc = (cover, hueKey) => `<div class="spinner-disc" style="--h:${hue(hueKey)}"><i style="${cover ? `background-image:url('${esc(cover)}')` : ''}"></i></div>`;
  // Intro
  out.push({ cls: 'c5', dur: 5, html: `${disc(album && album.cover, name)}<div class="k in">${s.range.kind === 'all' ? 'Your whole history' : `Your ${per}`}</div>
    <div class="t in" ${dly(0.15)}>${esc(name)}</div><p class="p in" ${dly(0.35)}>on record. Tap the right side to go on, the left to go back, hold to pause.</p>` });
  // Volume
  let vs = '';
  if (sm.prev && sm.prev.scrobbles > 0) {
    const p = Math.round(((sm.scrobbles - sm.prev.scrobbles) * 100) / sm.prev.scrobbles);
    vs = `<span class="badge in" ${dly(0.6)}>${icon(p >= 0 ? 'trend-up' : 'down-trend')}${Math.abs(p)}% ${p >= 0 ? 'more' : 'less'} than ${esc(lastWord(s.range.kind))}</span>`;
  }
  out.push({ cls: 'c2', html: `<div class="k in">You played</div><div class="t in" ${dly(0.1)}><span data-count="${sm.scrobbles}">0</span></div>
    <p class="p in" ${dly(0.3)}>tracks. That's about <b>${num(sm.hours)} hours</b> of music, on ${sm.days} of ${sm.spanDays} days.</p>${vs}` });
  // Personality
  const ps = s.personality;
  if (ps && PERSONAS[ps.id]) {
    const P = PERSONAS[ps.id];
    const R = ps.runnerUp && PERSONAS[ps.runnerUp];
    out.push({ cls: 'c3', html: `<svg class="i bigicon in"><use href="#i-${P.icon}"/></svg><div class="k in" ${dly(0.1)}>Your listening personality</div>
      <div class="t m in" ${dly(0.2)}>${P.title}</div><p class="p in" ${dly(0.4)}>${P.why(ps.metrics)}</p>
      ${R ? `<span class="badge in" ${dly(0.7)}>${icon(R.icon)}with a touch of ${R.title}</span>` : ''}` });
  }
  // Top artist and the top five
  const a0 = (s.artists || [])[0];
  if (a0) {
    out.push({ cls: 'c1', html: `${a0.cover ? `<div class="bgcover" style="background-image:url('${esc(a0.cover)}')"></div>` : ''}<div class="k in">Your top artist</div>
      <div class="t in" ${dly(0.15)}>${esc(a0.name)}</div><p class="p in" ${dly(0.35)}><b>${plural(a0.plays, 'play')}</b>, ${pct(a0.share)} of everything you played, on ${plural(a0.days, 'day')}.</p>` });
  }
  if ((s.artists || []).length >= 3) {
    out.push({ cls: 'c2', html: `<div class="k in">Your top artists</div><div class="list5">${s.artists.map((x, i) => `<div class="row in" ${dly(0.1 + i * 0.12)}><span class="n">${i + 1}</span>
      <span class="nm">${esc(x.name)}</span><span class="v">${num(x.plays)}</span><i class="bar5" data-w="${(x.plays / s.artists[0].plays) * 100}%"></i></div>`).join('')}</div>` });
  }
  // Song
  const t0 = (s.tracks || [])[0];
  if (t0) {
    const best = s.songBestDay && s.songBestDay.count > 1 ? `, ${s.songBestDay.count} of them on ${esc(dayName(s.songBestDay.day))} alone` : '';
    out.push({ cls: 'c3', html: `<div class="eqbig in"><i></i><i></i><i></i><i></i><i></i></div><div class="k in" ${dly(0.1)}>${ofThe(s.range.kind, 'Song')}</div>
      <div class="t m in" ${dly(0.2)}>${esc(t0.title)}</div><p class="p in" ${dly(0.4)}>by ${esc(t0.artist)}. <b>${plural(t0.plays, 'play')}</b>${best}.</p>
      ${s.tracks.length > 1 ? `<p class="p in" ${dly(0.7)} style="font-size:15px">Then: ${s.tracks.slice(1, 4).map((x) => esc(x.title)).join(' · ')}</p>` : ''}` });
  }
  // Album, as a card that moves with the phone
  if (album) {
    out.push({ cls: 'c1', html: `<div class="k in">${ofThe(s.range.kind, 'Album')}</div><div class="in" ${dly(0.1)}>${holoCover(album.cover, album.title, album.artist, { owned: album.owned, disc: false, key: 'story-album' })}</div>
      <div class="t m in" ${dly(0.25)}>${esc(album.title)}</div><p class="p in" ${dly(0.4)}>${esc(album.artist)} · ${plural(album.plays, 'play')}${album.owned ? ' · on your shelf' : ''}</p>` });
  }
  // Golden hour
  const gh = s.goldenHour;
  const hmax = Math.max(1, ...s.hours);
  const mood = gh >= 5 && gh < 10 ? 'Mornings are yours.' : gh >= 10 && gh < 17 ? 'Music through the day.' : gh >= 17 && gh < 22 ? 'Evenings are for records.' : 'The night shift.';
  out.push({ cls: 'c2', html: `<div class="k in">Your golden hour</div><div class="t in" ${dly(0.1)}>${pad(gh)}:00</div><p class="p in" ${dly(0.3)}>${mood} More plays start in this hour than in any other.</p>
    <div class="h24">${s.hours.map((v, i) => `<i class="${i === gh ? 'on' : ''}" style="height:${Math.max(2, (v / hmax) * 100)}%;--d:${0.3 + i * 0.025}s"></i>`).join('')}</div>
    <div class="h24x"><span>0</span><span>6</span><span>12</span><span>18</span><span>23</span></div>` });
  // Discovery
  const na = s.newArtists || {};
  if (na.count > 0) {
    out.push({ cls: 'c3', html: `<svg class="i bigicon in"><use href="#i-explore"/></svg><div class="k in" ${dly(0.1)}>New to you</div>
      <div class="t in" ${dly(0.15)}><span data-count="${na.count}">0</span></div><p class="p in" ${dly(0.35)}>artists you had never played before.${na.best ? ` The one that stuck: <b>${esc(na.best.name)}</b>, ${plural(na.best.plays, 'play')} since ${esc(dateLabel(na.best.first))}.` : ''}</p>` });
  }
  // On repeat
  if (s.loop || s.obsession) {
    const lp = s.loop;
    const ob = s.obsession;
    const x = lp || ob;
    const what = lp ? `<b>${lp.run} times in a row</b> on ${esc(dayName(lp.at))}` : `<b>${ob.count} plays</b> in the week of ${esc(dateLabel(ob.week))}`;
    out.push({ cls: 'c4', html: `<svg class="i bigicon in"><use href="#i-repeat"/></svg><div class="k in" ${dly(0.1)}>On repeat</div>
      <div class="t m in" ${dly(0.2)}>${esc(x.track)}</div><p class="p in" ${dly(0.4)}>by ${esc(x.artist)}: ${what}.</p>` });
  }
  // Genres
  const gs = s.genres || [];
  if (gs.length) {
    out.push({ cls: 'c1', html: `<div class="k in">Your sound</div><div class="t m in" ${dly(0.1)}>${esc(gs[0].name)}</div><p class="p in" ${dly(0.3)}>${pct(gs[0].share)} of everything with a known genre.</p>
      <div class="gbars">${gs.map((g, i) => `<div class="in" ${dly(0.4 + i * 0.1)}><span>${esc(g.name)}</span><span>${pct(g.share)}</span><i style="background:currentColor;opacity:${1 - i * 0.15}" data-w="${(g.share / gs[0].share) * 100}%"></i></div>`).join('')}</div>` });
  }
  // Vinyl
  const v = s.vinyl || {};
  if (v.count > 0) {
    const r0 = (v.top || [])[0];
    out.push({ cls: 'c4', html: `${r0 ? `<div class="in">${holoCover(r0.cover, r0.title, r0.artist, { owned: true, key: 'story-vinyl' })}</div>` : ''}<div class="k in" ${dly(0.15)}>From your shelf</div>
      <div class="t m in" ${dly(0.2)}><span data-count="${v.count}">0</span> tracks on vinyl</div><p class="p in" ${dly(0.4)}>${pct(v.share)} of your listening${r0 ? `. Most spun: <b>${esc(r0.title)}</b>` : ''}.</p>` });
  }
  // Streak and the biggest day
  const st = s.streak || {};
  if (st.longest >= 2) {
    out.push({ cls: 'c2', html: `<svg class="i bigicon in"><use href="#i-fire"/></svg><div class="k in" ${dly(0.1)}>Longest streak</div>
      <div class="t in" ${dly(0.15)}><span data-count="${st.longest}">0</span> days</div><p class="p in" ${dly(0.35)}>with music in a row, ${esc(dateLabel(st.longestFrom))} – ${esc(dateLabel(st.longestTo))}.</p>
      ${s.busiestDay ? `<p class="p in" ${dly(0.6)}>Biggest day: <b>${esc(dayName(s.busiestDay.day))}</b>, ${plural(s.busiestDay.count, 'play')}.</p>` : ''}` });
  }
  // Summary
  const P = ps && PERSONAS[ps.id];
  out.push({ cls: 'c5', dur: 14, html: `<div class="sumhead in">${coverHtml(album && album.cover, (album && album.title) || name, '', 'small')}<div><div class="k">${esc(name)}</div><div class="t m" style="margin:2px 0 0">on record</div></div></div>
    <div class="sum">
      <div class="in" ${dly(0.15)}><h4>Top artists</h4><ol>${(s.artists || []).map((x) => `<li>${esc(x.name)}</li>`).join('')}</ol></div>
      <div class="in" ${dly(0.25)}><h4>Top songs</h4><ol>${(s.tracks || []).map((x) => `<li>${esc(x.title)}</li>`).join('')}</ol></div>
      <div class="in" ${dly(0.35)}><h4>Plays</h4><div class="big">${num(sm.scrobbles)}</div></div>
      <div class="in" ${dly(0.45)}><h4>Hours</h4><div class="big">≈${num(sm.hours)}</div></div>
      <div class="in" ${dly(0.55)}><h4>Top genre</h4><div class="big" style="font-size:20px;line-height:24px">${gs.length ? esc(gs[0].name) : '–'}</div></div>
      <div class="in" ${dly(0.65)}><h4>Personality</h4><div class="big" style="font-size:20px;line-height:24px">${P ? P.title : '–'}</div></div>
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
  else if (t.hasApp) st = statusLine('ok', 'Exact links are on', `Client ID ${esc(t.clientHint || '')} · sign in to make playlists`);
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
    if (sg) { statsGenre = sg.dataset.statsgenre; showTab('stats'); return; }
    if (e.target.closest('[data-gpick]')) { openGenreSheet(); return; }
    if (e.target.closest('[data-gclear]')) { setStatsGenre(''); return; }
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
  $('#exploreBody').onclick = (e) => { if (e.target.closest('#exploreGoStats')) showTab('stats'); };
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
