'use strict';

/*
 * Stand-in for the Kotlin side when the page is opened in a normal browser (no window.ShelfNative).
 * Fakes a Discogs collection, tracklists, the spin schedule and Last.fm, so the whole UI can be
 * tried. URL options: ?mock=new (nothing connected yet), ?mock=spin (a record already playing).
 */
const Mock = (() => {
  const mode = new URLSearchParams(location.search).get('mock') || '';
  const DAY = 864e5;
  const clone = (o) => JSON.parse(JSON.stringify(o));
  const later = (ms, fn) => setTimeout(fn, ms);

  // artist, title, year, label, format, genres, styles, plays
  const RECORDS = [
    ['Fleetwood Mac', 'Rumours', 1977, 'Warner Bros. Records · BSK 3010', 'Vinyl, LP, Album', ['Rock'], ['Pop Rock', 'Soft Rock'], 214],
    ['Miles Davis', 'Kind of Blue', 1959, 'Columbia · CL 1355', 'Vinyl, LP, Album, Mono', ['Jazz'], ['Modal', 'Cool Jazz'], 58],
    ['Radiohead', 'In Rainbows', 2007, 'XL Recordings · XLLP324', 'Vinyl, LP, Album', ['Electronic', 'Rock'], ['Alternative Rock', 'Art Rock'], 341],
    ['Daft Punk', 'Random Access Memories', 2013, 'Columbia · 88883716861', '2×Vinyl, LP, Album', ['Electronic', 'Pop'], ['Disco', 'Synth-pop'], 127],
    ['Pink Floyd', 'The Dark Side of the Moon', 1973, 'Harvest · SHVL 804', 'Vinyl, LP, Album', ['Rock'], ['Prog Rock', 'Psychedelic Rock'], 96],
    ['Kendrick Lamar', 'To Pimp a Butterfly', 2015, 'Top Dawg Entertainment · B0023148-01', '2×Vinyl, LP, Album', ['Hip Hop', 'Jazz'], ['Conscious', 'Jazzy Hip-Hop'], 189],
    ['Bon Iver', 'For Emma, Forever Ago', 2007, 'Jagjaguwar · JAG115', 'Vinyl, LP, Album', ['Rock', 'Folk, World, & Country'], ['Indie Rock', 'Folk'], 77],
    ['Khruangbin', 'Con Todo El Mundo', 2018, 'Dead Oceans · DOC152', 'Vinyl, LP, Album', ['Rock', 'Funk / Soul'], ['Psychedelic Rock', 'Soul'], 402],
    ['Beach House', 'Bloom', 2012, 'Sub Pop · SP 975', '2×Vinyl, LP, Album', ['Rock', 'Pop'], ['Dream Pop', 'Indie Pop'], 233],
    ['Portishead', 'Dummy', 1994, 'Go! Beat · 828 553-1', 'Vinyl, LP, Album', ['Electronic', 'Hip Hop'], ['Trip Hop', 'Downtempo'], 61],
    ['Talking Heads', 'Remain in Light', 1980, 'Sire · SRK 6095', 'Vinyl, LP, Album', ['Electronic', 'Rock'], ['New Wave', 'Funk'], 44],
    ['Stevie Wonder', 'Songs in the Key of Life', 1976, 'Tamla · T13-340C2', '2×Vinyl, LP + 7"', ['Funk / Soul'], ['Soul', 'Funk'], 38],
    ['Fleet Foxes', 'Fleet Foxes', 2008, 'Sub Pop · SP 777', 'Vinyl, LP, Album', ['Rock', 'Folk, World, & Country'], ['Folk Rock', 'Indie Rock'], 0],
    ['The National', 'Boxer', 2007, 'Beggars Banquet · BBQLP 255', 'Vinyl, LP, Album', ['Rock'], ['Indie Rock'], 152],
    ['Massive Attack', 'Mezzanine', 1998, 'Circa · WBRLP 4', '2×Vinyl, LP, Album', ['Electronic'], ['Trip Hop'], 88],
    ['Joni Mitchell', 'Blue', 1971, 'Reprise Records · MS 2038', 'Vinyl, LP, Album', ['Rock', 'Folk, World, & Country'], ['Folk Rock'], 23],
    ['Tame Impala', 'Currents', 2015, 'Modular · MODVL207', '2×Vinyl, LP, Album', ['Rock', 'Pop'], ['Psychedelic Rock', 'Synth-pop'], 511],
    ['Nick Drake', 'Pink Moon', 1972, 'Island Records · ILPS 9184', 'Vinyl, LP, Album', ['Rock', 'Folk, World, & Country'], ['Folk'], 2],
    ['Air', 'Moon Safari', 1998, 'Source · 7243 8 44978 1 8', 'Vinyl, LP, Album', ['Electronic'], ['Downtempo', 'Ambient'], 140],
    ['Arctic Monkeys', 'AM', 2013, 'Domino · WIGLP317', 'Vinyl, LP, Album', ['Rock'], ['Indie Rock'], 296],
    ['Bonobo', 'Black Sands', 2010, 'Ninja Tune · ZEN140', '2×Vinyl, LP, Album', ['Electronic'], ['Downtempo', 'Broken Beat'], 64],
    ['Nina Simone', 'Wild Is the Wind', 1966, 'Philips · PHS 600-207', 'Vinyl, LP, Album', ['Jazz', 'Funk / Soul'], ['Soul-Jazz'], 0],
    ['Frank Ocean', 'Blonde', 2016, 'Boys Don\'t Cry', '2×Vinyl, LP, Album', ['Hip Hop', 'Funk / Soul'], ['RnB/Swing'], 377],
    ['Kraftwerk', 'Trans-Europa Express', 1977, 'Kling Klang · 1C 064-82 306', 'Vinyl, LP, Album', ['Electronic'], ['Electro', 'Krautrock'], 12],
    ['The Velvet Underground & Nico', 'The Velvet Underground & Nico', 1967, 'Verve Records · V6-5008', 'Vinyl, LP, Album', ['Rock'], ['Art Rock'], 31],
    ['Sade', 'Diamond Life', 1984, 'Epic · EPC 26044', 'Vinyl, LP, Album', ['Funk / Soul', 'Pop'], ['Soul', 'Smooth Jazz'], 19],
    ['Mac DeMarco', 'Salad Days', 2014, 'Captured Tracks · CT-197', 'Vinyl, LP, Album', ['Rock'], ['Indie Rock', 'Lo-Fi'], 103],
    ['Various Artists', 'Studio One Rockers', 2001, 'Soul Jazz Records · SJR LP52', '2×Vinyl, Compilation', ['Reggae'], ['Roots Reggae', 'Dub'], 7],
  ];

  const REAL = {
    'Rumours': [
      ['A1', 'Second Hand News', 163], ['A2', 'Dreams', 254], ['A3', 'Never Going Back Again', 122], ['A4', 'Don\'t Stop', 191],
      ['A5', 'Go Your Own Way', 218], ['A6', 'Songbird', 200], ['B1', 'The Chain', 268], ['B2', 'You Make Loving Fun', 211],
      ['B3', 'I Don\'t Want To Know', 191], ['B4', 'Oh Daddy', 234], ['B5', 'Gold Dust Woman', 291],
    ],
    'Kind of Blue': [['A1', 'So What', 562], ['A2', 'Freddie Freeloader', 586], ['A3', 'Blue in Green', 337], ['B1', 'All Blues', 693], ['B2', 'Flamenco Sketches', 566]],
  };
  const W1 = ['Morning', 'Glass', 'River', 'Static', 'Velvet', 'Northern', 'Paper', 'Echo', 'Silver', 'Quiet', 'Golden', 'Hollow', 'Neon', 'Distant', 'Slow'];
  const W2 = ['Lights', 'Hours', 'Song', 'Lines', 'Garden', 'Signal', 'Tide', 'Rooms', 'Weather', 'Motion', 'Dream', 'Coast', 'Fever', 'Letters', 'Bloom'];
  const ARTISTS = ['The Heptones', 'Jackie Mittoo', 'Alton Ellis', 'Freddie McGregor', 'Horace Andy', 'Sound Dimension', 'Marcia Griffiths'];

  function rng(seed) {
    let s = seed >>> 0 || 1;
    return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
  }
  const hashStr = (str) => { let h = 7; for (const c of str) h = (h * 31 + c.charCodeAt(0)) >>> 0; return h; };

  function tracklist(rec, id) {
    const [artist, title] = rec;
    if (REAL[title]) return REAL[title].map(([pos, t, dur]) => ({ pos, side: pos[0], title: t, artist, dur, src: 'discogs' }));
    const r = rng(hashStr(title));
    const sides = /2×/.test(rec[4]) ? ['A', 'B', 'C', 'D'] : ['A', 'B'];
    const out = [];
    const unknown = title === 'Pink Moon';
    for (const side of sides) {
      const n = 3 + Math.floor(r() * 3);
      for (let k = 1; k <= n; k++) {
        const known = !unknown && r() > 0.15;
        out.push({
          pos: side + k, side,
          title: `${W1[Math.floor(r() * W1.length)]} ${W2[Math.floor(r() * W2.length)]}`,
          artist: artist === 'Various Artists' ? ARTISTS[Math.floor(r() * ARTISTS.length)] : artist,
          dur: known ? 150 + Math.floor(r() * 220) : 0,
          src: known ? (r() > 0.3 ? 'discogs' : 'musicbrainz') : '',
        });
      }
    }
    return out;
  }

  const S = {
    lastfm: { hasKey: true, builtIn: true, keyHint: '', connected: true, user: 'vinylfan', waiting: false, busy: false, error: '', needsAuth: false },
    discogs: { connected: true, user: 'vinylfan', syncing: false, done: 0, page: 0, pages: 0, lastSync: Date.now() - 5 * 3600e3, count: 0, error: '' },
    prefs: { theme: 'system', defaultTrackSec: 210 },
    queue: { waiting: 0, oldest: 0, error: '' },
    spin: null,
    phone: {
      mode: 'shadow', access: true, connected: true, pano: true, now: null, since: Date.now() - 3 * DAY, comparing: false, compareError: '',
      apps: [
        { pkg: 'com.aspiro.tidal', label: 'TIDAL', on: true, last: 'Khruangbin — Maria También', seen: Date.now() },
        { pkg: 'com.google.android.youtube', label: 'YouTube', on: false, last: 'Tiny Desk Concert', seen: Date.now() - DAY },
      ],
    },
    history: { count: 86012, done: true, resumable: false, importing: false, syncing: false, fetched: 0, total: 0, error: '', lastSync: Date.now() - 20 * 60e3, tagsDone: 0, tagsTotal: 0 },
    tidal: { hasApp: true, builtIn: true, loggedIn: false, busy: false, error: '', clientHint: '', redirect: 'recordshelf://tidal' },
    version: '0.21 (browser preview)',
  };

  // A fake TIDAL session: plays these in a loop (lengths shortened so the preview moves).
  const PHONE_TRACKS = [
    ['Khruangbin', 'Maria También', 'Con Todo El Mundo', 190],
    ['Khruangbin', 'Como Me Quieres', 'Con Todo El Mundo', 160],
    ['Beach House', 'Myth', 'Bloom', 258],
  ];
  let phoneIdx = 0, phoneListened = 0, phoneScrobbled = false, phonePlaying = true;
  let coll = [];
  const releases = {};
  let act = [];
  let spin = null;
  let seq = 0;

  function makeCollection() {
    const now = Date.now();
    coll = RECORDS.map((rec, i) => ({
      id: 1000 + i, instance: 5000 + i, title: rec[1], artist: rec[0], year: rec[2], added: now - (i * 37 + 3) * DAY,
      thumb: '', cover: '', format: rec[4], label: rec[3], genres: rec[5], styles: rec[6], master: 0, plays: rec[7], spins: i % 5 === 0 ? 2 : 0,
    }));
    S.discogs.count = coll.length;
  }
  function release(id) {
    if (!releases[id]) {
      const i = id - 1000, rec = RECORDS[i], c = coll.find((x) => x.id === id) || {};
      if (!rec) return { id, tracks: [] }; // made-up pages can point at releases the mock doesn't have
      releases[id] = Object.assign({}, c, { tracks: tracklist(rec, id), uri: `https://www.discogs.com/release/${id}`, country: 'US' });
    }
    return releases[id];
  }
  function actEntry(it, ts, status, msg = '') {
    return { id: 'm' + seq++, artist: it.artist, track: it.title, album: it.album, ts: Math.floor(ts / 1000), src: 'vinyl', cover: '', status, msg, at: Date.now() };
  }

  const pushState = () => { S.spin = spin ? clone(spin) : null; onState(clone(S)); };
  const pushColl = () => onCollection(clone(coll));
  const pushAct = () => onActivity(clone(act));

  function nextSide(rel, items) {
    const sides = [...new Set(rel.tracks.map((t) => t.side).filter(Boolean))];
    const live = items.filter((x) => x.state !== 'skipped');
    const last = (live[live.length - 1] || items[items.length - 1] || {}).side;
    const i = sides.indexOf(last);
    return i >= 0 && i + 1 < sides.length ? sides[i + 1] : null;
  }
  const due = (it) => it.start + Math.min(it.dur * 500, 240000);

  function scrobbleDue(now, all) {
    let changed = false;
    for (const it of spin.items) {
      if (it.state === 'planned' && (all || now >= due(it))) {
        it.state = 'scrobbled';
        changed = true;
        const e = actEntry({ ...it, album: spin.album }, it.start, S.lastfm.connected ? 'waiting' : 'waiting');
        act.push(e);
        if (S.lastfm.connected) later(1200, () => { e.status = 'sent'; S.queue.waiting = act.filter((a) => a.status === 'waiting').length; pushAct(); pushState(); });
      }
    }
    S.queue.waiting = act.filter((a) => a.status === 'waiting').length;
    return changed;
  }

  function phoneTick() {
    const P = S.phone;
    if (P.mode === 'off' || !P.access) { if (P.now) { P.now = null; pushState(); } return; }
    let [artist, title, album, dur] = PHONE_TRACKS[phoneIdx];
    if (phonePlaying) phoneListened += 1000;
    if (phoneListened >= dur * 1000) {
      phoneIdx = (phoneIdx + 1) % PHONE_TRACKS.length;
      phoneListened = 0;
      phoneScrobbled = false;
      [artist, title, album, dur] = PHONE_TRACKS[phoneIdx];
    }
    const threshold = Math.min(dur * 500, 240000);
    let changed = !P.now || P.now.title !== title;
    if (!phoneScrobbled && phoneListened >= threshold) {
      phoneScrobbled = true;
      changed = true;
      act.push({ id: 'p' + seq++, artist, track: title, album, ts: Math.floor((Date.now() - phoneListened) / 1000), src: 'phone', app: 'TIDAL', cover: '', status: P.mode === 'shadow' ? 'shadow' : 'sent', msg: '', at: Date.now() });
      pushAct();
    }
    const media = { pos: phoneListened, posAt: Date.now(), speed: 1, playing: phonePlaying, dur: dur * 1000, canPlay: true, canNext: true, canPrev: true, canSeek: true, canQueue: true,
      active: phoneIdx, queueTitle: 'My Daily Discovery', queueSize: PHONE_TRACKS.length, queue: PHONE_TRACKS.map(([a, t], i) => ({ id: i, title: t, artist: a })) };
    changed = changed || !P.now || P.now.playing !== phonePlaying;
    P.now = { pkg: 'com.aspiro.tidal', app: 'TIDAL', artist, title, album, dur: dur * 1000, listened: phoneListened, at: Date.now(), playing: phonePlaying, threshold, scrobbled: phoneScrobbled, eligible: true, on: true, media };
    if (changed) pushState();
  }

  function fakeCompare() {
    const ours = act.filter((a) => a.src === 'phone' && a.status === 'shadow');
    const L = (a, dt = 0, title) => ({ artist: a.artist, title: title || a.track, ts: a.ts + dt, album: a.album });
    const shift = Math.max(0, ours.length - 3);
    return {
      matched: Math.max(0, ours.length - 3) + 140,
      renamed: ours.length > 2 ? [{ ours: L(ours[0]), theirs: L(ours[0], 2, ours[0].track + ' (2018 Remaster)') }] : [],
      onlyTheirs: [{ artist: 'Portishead', title: 'Roads', ts: Math.floor(Date.now() / 1000) - 2 * 86400 + 3600, album: 'Dummy' },
        { artist: 'Air', title: 'Talisman', ts: Math.floor(Date.now() / 1000) - 86400 - 7200, album: 'Moon Safari' }],
      onlyOurs: ours.length > 1 && shift >= 0 ? [L(ours[ours.length - 1])] : [],
      from: Math.floor(S.phone.since / 1000), to: Math.floor(Date.now() / 1000) - 600, at: Date.now(),
    };
  }

  // ---- stats: a made-up history since December 2023, and the figures of Stats.kt, simplified ----

  const MOCK_ARTISTS = ['IDLES', 'Fontaines D.C.', 'Khruangbin', 'Shame', 'Tame Impala', 'Beach House', 'The Strokes', 'Little Simz', 'Gorillaz',
    'Fleetwood Mac', 'Bonobo', 'Turnstile', 'Air', 'Portishead', 'Frank Ocean', 'Glass Animals'];
  const GENRES = ['Post-Punk', 'Indie Rock', 'Psychedelic Rock', 'Hip Hop', 'Electronic', 'Dream Pop', 'Other'];
  // The made-up genres as the app's families and styles.
  const MFAM = ['punk', 'indie', 'rock', 'hiphop', 'electronic', 'indie'];
  const MSUB = ['punk:post punk', 'indie:indie rock', 'rock:psychedelic rock', '', '', 'indie:dream pop'];
  const FAMNAME = { punk: 'Punk & Post-Punk', indie: 'Indie & Alternative', rock: 'Rock', hiphop: 'Hip Hop', electronic: 'Electronic' };
  const subName = (k) => GENRES[MSUB.indexOf(k)] || k;
  function mockResolve(key) {
    const k = String(key).toLowerCase().replace(/-/g, ' ');
    if (FAMNAME[k] || k.includes(':')) return k;
    const byName = Object.keys(FAMNAME).find((f) => FAMNAME[f].toLowerCase() === k);
    if (byName) return byName;
    const i = GENRES.findIndex((x) => x.toLowerCase().replace(/-/g, ' ') === k);
    return i >= 0 ? MSUB[i] || MFAM[i] : k;
  }
  function mockGenreOptions() {
    const L = history();
    const fam = {}, sub = {};
    for (let i = 0; i < L.ts.length; i++) {
      const g = genreOf(L.a[i]);
      if (g < 0 || g > 5) continue;
      fam[MFAM[g]] = (fam[MFAM[g]] || 0) + 1;
      if (MSUB[g]) sub[MSUB[g]] = (sub[MSUB[g]] || 0) + 1;
    }
    return Object.entries(fam).sort((a, b) => b[1] - a[1]).map(([f, n]) => ({ key: f, label: FAMNAME[f], plays: n,
      subs: Object.entries(sub).filter(([s]) => s.startsWith(f + ':')).sort((a, b) => b[1] - a[1]).map(([s, m]) => ({ key: s, label: subName(s), plays: m })) }));
  }
  const genreOf = (ai) => [0, 0, 2, 0, 2, 5, 1, 3, 4, 1, 4, 0, 4, 4, 3, 1][ai] ?? (ai % 7 === 6 ? -1 : ai % 6);
  let LOG = null;
  function history() {
    if (LOG) return LOG;
    const r = rng(42);
    const start = +new Date(2023, 11, 4);
    const end = Date.now();
    const artists = MOCK_ARTISTS.concat(Array.from({ length: 90 }, (_, i) => `Artist ${i + 1}`));
    const hourW = [0.3, 0.15, 0.06, 0.02, 0.02, 0.03, 0.25, 0.9, 1.1, 0.8, 0.6, 0.6, 0.7, 0.6, 0.6, 0.7, 0.9, 1.2, 1.4, 1.6, 1.8, 1.6, 1.2, 0.7];
    const hsum = hourW.reduce((x, y) => x + y, 0);
    const pickHour = () => { let x = r() * hsum; for (let h = 0; h < 24; h++) { x -= hourW[h]; if (x <= 0) return h; } return 20; };
    const rows = [];
    const titles = [];
    const tIdx = {};
    for (let day = start, k = 0; day < end; day += DAY, k++) {
      if (r() < 0.07) continue; // a day without music
      const n = Math.round(r() * r() * 42);
      const phase = Math.floor(k / 110); // every few months other favourites
      for (let j = 0; j < n; j++) {
        const fav = r() < 0.62;
        const ai = fav ? (phase * 3 + Math.floor(r() * 4)) % MOCK_ARTISTS.length : Math.floor(Math.pow(r(), 1.5) * artists.length);
        const tk = `${ai}:${Math.floor(r() * 7)}`;
        if (tIdx[tk] == null) { tIdx[tk] = titles.length; titles.push(`${W1[Math.floor(r() * W1.length)]} ${W2[Math.floor(r() * W2.length)]}`); }
        const ts = day + pickHour() * 3600e3 + Math.floor(r() * 3600e3);
        if (ts < end) rows.push([ts, ai, tIdx[tk]]);
      }
    }
    rows.sort((x, y) => x[0] - y[0]);
    const first = new Array(artists.length).fill(-1);
    rows.forEach(([ts, ai]) => { if (first[ai] < 0) first[ai] = ts; });
    LOG = { ts: rows.map((x) => x[0]), a: rows.map((x) => x[1]), t: rows.map((x) => x[2]), titles, artists, first, start: rows.length ? rows[0][0] : start };
    return LOG;
  }
  /** A made-up tracklist for the album the fake TIDAL plays. */
  function fakeNowAlbum(album) {
    const titles = album === 'Con Todo El Mundo' ? ['Como Me Quieres', 'Lady and Man', 'Maria También', 'August 10', 'Evan Finds the Third Room', 'Shades of Man', 'Friday Morning', 'Rules', 'Hymn', 'A Hymn (Reprise)']
      : album === 'Bloom' ? ['Myth', 'Wild', 'Lazuli', 'Other People', 'The Hours', 'Troublemaker', 'New Year', 'Wishes', 'On the Sea', 'Irene']
        : [];
    return titles.length ? { title: album, tracks: titles.map((t, i) => ({ title: t, sec: 150 + ((i * 47) % 140) })) } : { missing: true };
  }

  /** Artists and songs from the made-up history, albums from the shelf, as Stats.search answers. */
  function fakeSearch(q) {
    const words = norm(q.trim()).split(/\s+/).filter(Boolean);
    const hit = (s) => words.length && words.every((w) => norm(s).includes(w));
    const L = history();
    const ap = {}, tp = {}, ta = {};
    L.a.forEach((a, i) => { ap[a] = (ap[a] || 0) + 1; tp[L.t[i]] = (tp[L.t[i]] || 0) + 1; ta[L.t[i]] = a; });
    const artists = Object.keys(ap).filter((a) => hit(L.artists[a])).sort((x, y) => ap[y] - ap[x]).slice(0, 6)
      .map((a) => ({ name: L.artists[a], plays: ap[a], photo: '' }));
    const tracks = Object.keys(tp).filter((t) => hit(`${L.titles[t]} ${L.artists[ta[t]]}`)).sort((x, y) => tp[y] - tp[x]).slice(0, 6)
      .map((t) => ({ title: L.titles[t], artist: L.artists[ta[t]], plays: tp[t] }));
    const albums = COLL.filter((r) => hit(`${r.title} ${r.artist}`)).slice(0, 6)
      .map((r) => ({ title: r.title, artist: r.artist, plays: r.plays || 0, cover: r.cover || r.thumb || '', owned: true }));
    return { q, artists, albums, tracks };
  }
  const lowerBound = (arr, v) => { let lo = 0, hi = arr.length; while (lo < hi) { const mid = (lo + hi) >> 1; if (arr[mid] < v) lo = mid + 1; else hi = mid; } return lo; };
  const dayIdx = (ms) => Math.floor((ms - new Date(ms).getTimezoneOffset() * 60e3) / DAY);
  const dayMs = (d) => { const x = new Date(d * DAY); return +new Date(x.getUTCFullYear(), x.getUTCMonth(), x.getUTCDate()); };

  /** A period without its neighbours (see Stats.range). */
  function baseRange(id) {
    const now = new Date();
    const mk = (kind, rid, a, b) => ({ id: rid, kind, label: rid, from: a / 1000, to: Math.min(b, Date.now() + 1000) / 1000, running: b > Date.now() });
    if (id === 'all') return { id, kind: 'all', label: 'All time', from: 0, to: Date.now() / 1000 + 1 };
    if (/^y\d+$/.test(id)) { const y = +id.slice(1); return mk('year', id, +new Date(y, 0, 1), +new Date(y + 1, 0, 1)); }
    if (/^d\d+-\d+-\d+$/.test(id)) { const [y, m, d] = id.slice(1).split('-').map(Number); return mk('day', id, +new Date(y, m - 1, d), +new Date(y, m - 1, d + 1)); }
    if (/^m\d+-\d+$/.test(id)) { const [y, m] = id.slice(1).split('-').map(Number); return mk('month', id, +new Date(y, m - 1, 1), +new Date(y, m, 1)); }
    if (/^w\d+-\d+-\d+$/.test(id)) {
      const [y, m, d] = id.slice(1).split('-').map(Number);
      const mon = new Date(y, m - 1, d - ((new Date(y, m - 1, d).getDay() + 6) % 7));
      return mk('week', 'w' + isoDate(mon), +mon, +mon + 7 * DAY);
    }
    return baseRange(`m${now.getFullYear()}-${pad(now.getMonth() + 1)}`);
  }
  function mockRange(id) {
    const L = history();
    const r = baseRange(id);
    const step = (k) => {
      const d = new Date(r.from * 1000);
      if (r.kind === 'day') return 'd' + isoDate(new Date(d.getFullYear(), d.getMonth(), d.getDate() + k));
      if (r.kind === 'week') return 'w' + isoDate(new Date(d.getFullYear(), d.getMonth(), d.getDate() + 7 * k));
      if (r.kind === 'month') { const x = new Date(d.getFullYear(), d.getMonth() + k, 1); return `m${x.getFullYear()}-${pad(x.getMonth() + 1)}`; }
      if (r.kind === 'year') return 'y' + (d.getFullYear() + k);
      return null;
    };
    const ok = (nid) => { if (!nid) return null; const n = baseRange(nid); return n.from * 1000 > Date.now() || n.to * 1000 <= L.start ? null : nid; };
    r.prev = ok(step(-1));
    r.next = ok(step(1));
    r.historyStart = L.start / 1000;
    return r;
  }

  /** Bins (day, week, month, quarter, year) for a period, like Stats.binsFor. */
  function binsFor(r, maxBins) {
    const L = history();
    const from = new Date(Math.max(r.from * 1000, L.start));
    const to = new Date(Math.max(Math.min(r.to * 1000 - 1, Date.now()), from));
    const span = { week: 7, month: 31, year: 366 }[r.kind] || Math.round((to - from) / DAY) + 1;
    const unit = span <= maxBins ? 'day' : span / 7 + 1 <= maxBins ? 'week' : span / 30.44 + 1 <= maxBins ? 'month' : span / 91.3 + 1 <= maxBins ? 'quarter' : 'year';
    const base = unit === 'day' ? new Date(from.getFullYear(), from.getMonth(), from.getDate())
      : unit === 'week' ? new Date(from.getFullYear(), from.getMonth(), from.getDate() - ((from.getDay() + 6) % 7))
        : unit === 'month' ? new Date(from.getFullYear(), from.getMonth(), 1)
          : unit === 'quarter' ? new Date(from.getFullYear(), Math.floor(from.getMonth() / 3) * 3, 1) : new Date(from.getFullYear(), 0, 1);
    const months = (d) => (d.getFullYear() - base.getFullYear()) * 12 + d.getMonth() - base.getMonth();
    const index = (ms) => {
      const d = new Date(ms);
      if (unit === 'day') return Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()) - base) / DAY);
      if (unit === 'week') return Math.floor(Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()) - base) / DAY) / 7);
      if (unit === 'month') return months(d);
      if (unit === 'quarter') return Math.floor(months(d) / 3);
      return d.getFullYear() - base.getFullYear();
    };
    const start = (i) => +(unit === 'day' ? new Date(base.getFullYear(), base.getMonth(), base.getDate() + i)
      : unit === 'week' ? new Date(base.getFullYear(), base.getMonth(), base.getDate() + 7 * i)
        : unit === 'month' ? new Date(base.getFullYear(), base.getMonth() + i, 1)
          : unit === 'quarter' ? new Date(base.getFullYear(), base.getMonth() + 3 * i, 1) : new Date(base.getFullYear() + i, 0, 1));
    const count = Math.max(1, index(+to) + 1);
    return { unit, index, start, count, starts: Array.from({ length: count }, (_, i) => start(i)) };
  }

  function counts(i0, i1, key) { const L = history(); const m = {}; for (let i = i0; i < i1; i++) { const k = key(L, i); m[k] = (m[k] || 0) + 1; } return m; }
  const ranked = (m) => Object.keys(m).map(Number).sort((x, y) => m[y] - m[x] || x - y);

  function summaryOf(r) {
    const L = history();
    const i0 = lowerBound(L.ts, r.from * 1000), i1 = lowerBound(L.ts, r.to * 1000);
    const n = i1 - i0;
    const ar = new Set(), tr = new Set(), days = new Set();
    for (let i = i0; i < i1; i++) { ar.add(L.a[i]); tr.add(L.t[i]); days.add(dayIdx(L.ts[i])); }
    const first = Math.max(r.from * 1000, L.start);
    const spanDays = Math.max(1, dayIdx(Math.min(r.to * 1000 - 1, Date.now())) - dayIdx(first) + 1);
    let fresh = 0;
    ar.forEach((x) => { if (L.first[x] >= r.from * 1000 && L.first[x] < r.to * 1000) fresh++; });
    return { scrobbles: n, artists: ar.size, tracks: tr.size, albums: Math.round(ar.size * 1.6), days: days.size, spanDays, perDay: Math.round((n * 10) / spanDays) / 10,
      hours: Math.round((n * 3.5) / 60), newArtists: fresh, historyStartsHere: L.start >= r.from * 1000 && L.start < r.to * 1000, i0, i1 };
  }
  function previousOf(r) {
    if (!r.prev) return null;
    const p = mockRange(r.prev);
    if (r.to * 1000 > Date.now()) p.to = Math.min(p.to, p.from + (r.to - r.from));
    return p;
  }

  /** Days in a row with music and per artist within the period, like Stats.streaks; the current one runs back from today. */
  function streaksOf(r, i0, i1) {
    const L = history();
    let last = -1e9, run = 0, runStart = 0, best = 0, bestStart = 0, bestEnd = 0;
    const aLast = {}, aRun = {}, aBest = {}, aEnd = {};
    for (let i = i0; i < i1; i++) {
      const d = dayIdx(L.ts[i]), a = L.a[i];
      if (d !== last) { if (d === last + 1) run++; else { run = 1; runStart = d; } last = d; if (run > best) { best = run; bestStart = runStart; bestEnd = d; } }
      if (aLast[a] !== d) { aRun[a] = aLast[a] === d - 1 ? aRun[a] + 1 : 1; aLast[a] = d; if (!(aBest[a] >= aRun[a])) { aBest[a] = aRun[a]; aEnd[a] = d; } }
    }
    let current = 0, currentFrom = 0;
    if (r.to * 1000 > Date.now() && L.ts.length) {
      let expect = dayIdx(L.ts[L.ts.length - 1]);
      if (expect >= dayIdx(Date.now()) - 1) {
        for (let i = L.ts.length - 1; i >= 0; i--) { const d = dayIdx(L.ts[i]); if (d === expect) { current++; expect--; } else if (d < expect) break; }
        currentFrom = dayMs(expect + 1);
      }
    }
    const artists = Object.keys(aBest).filter((a) => aBest[a] >= 3).sort((x, y) => aBest[y] - aBest[x]).slice(0, 3)
      .map((a) => ({ artist: L.artists[a], days: aBest[a], from: dayMs(aEnd[a] - aBest[a] + 1), to: dayMs(aEnd[a]) }));
    return { current, currentFrom, longest: best, longestFrom: best ? dayMs(bestStart) : 0, longestTo: best ? dayMs(bestEnd) : 0, artists };
  }

  /** The day's and the week's own parts, from the made-up history (see Stats.dayDetails and weekDays). */
  function fakeStats(id) {
    const s = fakeStatsBase(id);
    if (s.range && s.range.kind === 'day') s.day = fakeDay(s.range);
    if (s.range && s.range.kind === 'week') s.week = fakeWeek(s.range);
    return s;
  }
  function fakeColors(i0, i1) {
    const L = history(), n = {};
    for (let i = i0; i < i1; i++) n[L.a[i]] = (n[L.a[i]] || 0) + 1;
    const c = {};
    Object.keys(n).sort((x, y) => n[y] - n[x]).slice(0, 5).forEach((a, k) => { c[a] = k + 1; });
    return c;
  }
  const fakeStrip = (start, i0, i1, c) => { const L = history(); const out = []; for (let i = i0; i < i1; i++) out.push([Math.round((L.ts[i] - start) / 36e3) / 100, c[L.a[i]] || 0]); return out; };
  function fakeDay(r) {
    const L = history();
    const from = r.from * 1000, to = r.to * 1000;
    const i0 = lowerBound(L.ts, from), i1 = lowerBound(L.ts, to);
    const c = fakeColors(i0, i1);
    const play = (i) => ({ title: L.titles[L.t[i]], artist: L.artists[L.a[i]], album: '', cover: '', ts: Math.floor(L.ts[i] / 1000) });
    const sessions = [];
    for (let a = i0; a < i1;) {
      let e = a;
      while (e + 1 < i1 && L.ts[e + 1] - L.ts[e] <= 30 * 60e3) e++;
      const n = {};
      for (let i = a; i <= e; i++) n[L.a[i]] = (n[L.a[i]] || 0) + 1;
      const top = Object.keys(n).sort((x, y) => n[y] - n[x])[0];
      sessions.push({ from: Math.floor(L.ts[a] / 1000), to: Math.floor(L.ts[e] / 1000) + 210, count: e - a + 1, color: c[top] || 0, artist: L.artists[top], artistShare: n[top] / (e - a + 1), album: '', albumArtist: '', vinyl: 0 });
      a = e + 1;
    }
    const tp = {}, last = {}, ap = {};
    for (let i = i0; i < i1; i++) { tp[L.t[i]] = (tp[L.t[i]] || 0) + 1; last[L.t[i]] = i; ap[L.a[i]] = (ap[L.a[i]] || 0) + 1; }
    const seen = new Set();
    for (let i = 0; i < i0; i++) seen.add(L.t[i]);
    const d = new Date(from);
    const onThisDay = [];
    for (let y = d.getFullYear() - 1; y >= new Date(L.start).getFullYear() && onThisDay.length < 3; y--) {
      const a = +new Date(y, d.getMonth(), d.getDate()), b = +new Date(y, d.getMonth(), d.getDate() + 1);
      const j0 = lowerBound(L.ts, a), j1 = lowerBound(L.ts, b);
      if (j1 <= j0) continue;
      const n = {};
      for (let i = j0; i < j1; i++) n[L.a[i]] = (n[L.a[i]] || 0) + 1;
      const top = Object.keys(n).sort((x, z) => n[z] - n[x])[0];
      onThisDay.push({ year: y, id: 'd' + isoDate(new Date(a)), plays: j1 - j0, artist: L.artists[top], album: '', albumArtist: '', albumPlays: 0, cover: '' });
    }
    const upto = Math.min(to, Date.now() + 1000) - from;
    let plays = 0, weeks = 0;
    for (let k = 1; k <= 8; k++) {
      const a = +new Date(d.getFullYear(), d.getMonth(), d.getDate() - 7 * k);
      if (a + DAY <= L.start) break;
      plays += lowerBound(L.ts, a + upto) - lowerBound(L.ts, a);
      weeks++;
    }
    return {
      artists: Object.keys(c).sort((x, y) => c[x] - c[y]).map((a) => L.artists[a]), plays: fakeStrip(from, i0, i1, c), sessions, vinyl: 0,
      first: i1 > i0 ? play(i0) : null, latest: i1 > i0 ? play(i1 - 1) : null,
      repeats: Object.keys(tp).filter((t) => tp[t] >= 2).sort((x, y) => tp[y] - tp[x]).slice(0, 5).map((t) => ({ ...play(last[t]), plays: tp[t] })),
      topArtists: Object.keys(ap).sort((x, y) => ap[y] - ap[x]).slice(0, 3).map((a) => ({ name: L.artists[a], plays: ap[a], photo: '' })),
      newArtists: Object.keys(ap).filter((a) => L.first[a] >= from && L.first[a] < to).map((a) => L.artists[a]),
      newSongs: Object.keys(tp).filter((t) => !seen.has(+t)).length,
      onThisDay, usual: weeks ? { plays: Math.round((plays * 10) / weeks) / 10, weeks } : null,
    };
  }
  function fakeWeek(r) {
    const L = history();
    const mon = new Date(r.from * 1000);
    const at = (k) => +new Date(mon.getFullYear(), mon.getMonth(), mon.getDate() + k);
    const c = fakeColors(lowerBound(L.ts, r.from * 1000), lowerBound(L.ts, r.to * 1000));
    const days = [], last = [], strips = [];
    for (let k = 0; k < 7; k++) {
      const j0 = lowerBound(L.ts, at(k)), j1 = lowerBound(L.ts, at(k + 1));
      days.push(at(k) > Date.now() ? null : j1 - j0);
      last.push(lowerBound(L.ts, at(k - 6)) - lowerBound(L.ts, at(k - 7)));
      strips.push(fakeStrip(at(k), j0, j1, c));
    }
    const today = Date.now() >= at(0) && Date.now() < at(7) ? Math.floor((Date.now() - at(0)) / DAY) : -1;
    return { days, last, strips, artists: Object.keys(c).sort((x, y) => c[x] - c[y]).map((a) => L.artists[a]), today };
  }
  function fakeStatsBase(id) {
    const L = history();
    const r = mockRange(id);
    const sm = summaryOf(r);
    const { i0, i1 } = sm;
    const prev = previousOf(r);
    if (prev) sm.prev = summaryOf(prev);
    const ac = counts(i0, i1, (L2, i) => L2.a[i]);
    const order = ranked(ac);
    const local = {};
    order.forEach((a, k) => { local[a] = k; });
    // record
    const d0 = dayIdx(Math.max(r.from * 1000, L.start));
    const tl = {};
    const rec = { start: dayMs(d0), days: Math.max(1, dayIdx(Math.min(r.to * 1000 - 1, Date.now())) - d0 + 1), d: [], m: [], a: [], t: [], tracks: [], artists: order.map((a) => L.artists[a]), plays: order.map((a) => ac[a]) };
    for (let i = i0; i < i1; i++) {
      const dt = new Date(L.ts[i]);
      rec.d.push(dayIdx(L.ts[i]) - d0); rec.m.push(dt.getHours() * 60 + dt.getMinutes()); rec.a.push(local[L.a[i]]);
      if (tl[L.t[i]] == null) { tl[L.t[i]] = rec.tracks.length; rec.tracks.push(L.titles[L.t[i]]); }
      rec.t.push(tl[L.t[i]]);
    }
    // timeline, river, pulse, new artists, genres
    const b60 = binsFor(r, 60), b40 = binsFor(r, 40), b16 = binsFor(r, 16);
    const tlc = new Array(b60.count).fill(0);
    const rs = order.slice(0, 8).map(() => new Array(b40.count).fill(0));
    const other = new Array(b40.count).fill(0);
    const pc = order.slice(0, 14).map(() => new Array(b60.count).fill(0));
    const gc = Array.from({ length: b16.count }, () => new Array(GENRES.length).fill(0));
    const clock = new Array(168).fill(0);
    for (let i = i0; i < i1; i++) {
      const ts = L.ts[i], k = local[L.a[i]];
      tlc[Math.min(b60.count - 1, b60.index(ts))]++;
      if (k < 8) rs[k][Math.min(b40.count - 1, b40.index(ts))]++; else other[Math.min(b40.count - 1, b40.index(ts))]++;
      if (k < 14) pc[k][Math.min(b60.count - 1, b60.index(ts))]++;
      const g = genreOf(L.a[i]);
      if (g >= 0) gc[Math.min(b16.count - 1, b16.index(ts))][Math.min(g, 6)]++;
      const dt = new Date(ts);
      clock[((dt.getDay() + 6) % 7) * 24 + dt.getHours()]++;
    }
    const pulseRows = pc.map((c, k) => { let p = 0; c.forEach((v, i) => { if (v > c[p]) p = i; }); return { artist: L.artists[order[k]], plays: ac[order[k]], peak: p, counts: c }; }).sort((x, y) => x.peak - y.peak);
    const naCounts = new Array(b40.count).fill(0);
    const naTop = Array.from({ length: b40.count }, () => []);
    order.forEach((a) => { const f = L.first[a]; if (f >= r.from * 1000 && f < r.to * 1000) { const k = Math.min(b40.count - 1, b40.index(f)); naCounts[k]++; if (naTop[k].length < 3) naTop[k].push(L.artists[a]); } });
    const startIdx = b40.index(L.start);
    // Habits: the 16 most played, a tick per bin they show up in (see Stats.habits).
    const b160 = binsFor(r, 160);
    const hc = order.slice(0, 16).map(() => new Array(b160.count).fill(0));
    const hm = order.slice(0, 16).map(() => ({}));
    const anyBin = new Array(b160.count).fill(false);
    for (let i = i0; i < i1; i++) {
      const k = local[L.a[i]], bi = Math.min(b160.count - 1, b160.index(L.ts[i]));
      anyBin[bi] = true;
      if (k < 16) { hc[k][bi]++; const d = new Date(L.ts[i]), mk = d.getFullYear() * 12 + d.getMonth(); hm[k][mk] = (hm[k][mk] || 0) + 1; }
    }
    const habits = {
      unit: b160.unit, starts: b160.starts, active: anyBin.filter(Boolean).length, empty: anyBin.map((v, i) => (v ? -1 : i)).filter((i) => i >= 0),
      rows: hc.map((c, k) => {
        const tot = c.reduce((x, y) => x + y, 0), [pm, pn] = Object.entries(hm[k]).sort((x, y) => y[1] - x[1])[0] || ['0', 0];
        return { name: L.artists[order[k]], ink: k < 8 ? k : -1, counts: c, present: c.filter((v) => v > 0).length, peakShare: tot ? pn / tot : 0, peakMonth: +new Date(Math.floor(pm / 12), pm % 12, 1), plays: tot };
      }).sort((x, y) => y.present - x.present),
    };
    // New artists, who they are (see Stats.newArtists).
    const freshAll = order.filter((a) => L.first[a] >= r.from * 1000 && L.first[a] < r.to * 1000 && L.first[a] >= L.start + 14 * DAY);
    const found = freshAll.slice(0, 60).map((a) => ({ name: L.artists[a], first: L.first[a], plays: Math.round(ac[a] * 1.4), here: ac[a], from: L.artists[(a * 7 + 3) % L.artists.length], photo: '' }));
    const tc = counts(i0, i1, (L2, i) => L2.t[i]);
    const topT = ranked(tc).slice(0, 10);
    const trackArtist = {};
    for (let i = i0; i < i1; i++) trackArtist[L.t[i]] = L.a[i];
    const topAlbums = order.slice(0, 10).map((a, i) => ({ title: RECORDS[a % RECORDS.length][1], artist: L.artists[a], plays: Math.round(ac[a] * 0.7), cover: '', owned: i % 3 !== 2 }));
    const out = {
      range: r, summary: sm, record: rec,
      timeline: { unit: b60.unit, starts: b60.starts, counts: tlc, avg: Math.round((tlc.reduce((x, y) => x + y, 0) / tlc.length) * 10) / 10 },
      river: { unit: b40.unit, starts: b40.starts, artists: order.slice(0, 8).map((a) => L.artists[a]), series: rs, other },
      pulse: { unit: b60.unit, starts: b60.starts, bins: b60.count, rows: pulseRows },
      newArtists: { unit: b40.unit, buckets: naCounts.map((c, k) => ({ start: b40.starts[k], count: c, historyStart: k === startIdx && sm.historyStartsHere, top: naTop[k] })),
        found, count: freshAll.length, kept: freshAll.filter((a) => ac[a] * 1.4 >= 10).length },
      habits,
      genres: { names: GENRES, keys: GENRES.map((x) => (x === 'Other' ? 'other' : x.toLowerCase().replace(/-/g, ' '))), unit: b16.unit, coverage: { discogs: 0.34, lastfm: 0.59, none: 0.07 },
        periods: gc.map((c, k) => { const tot = c.reduce((x, y) => x + y, 0); return { start: b16.starts[k], total: tot, shares: c.map((v) => (tot ? Math.round((v / tot) * 1000) / 1000 : 0)) }; }) },
      clock,
      streaks: streaksOf(r, i0, i1),
      obsessions: topT.slice(0, 3).filter((t) => tc[t] >= 7).map((t) => ({ track: L.titles[t], artist: L.artists[trackArtist[t]], week: r.from * 1000 + 3 * DAY, count: Math.min(tc[t], 7 + (t % 9)) })),
      loops: topT.slice(0, 1).map((t) => ({ track: L.titles[t], artist: L.artists[trackArtist[t]], run: 5, at: r.from * 1000 + 6 * DAY })),
      rarely: { items: coll.slice().sort((x, y) => x.plays - y.plays).slice(0, 8).map((c) => ({ id: c.id, title: c.title, artist: c.artist, cover: '', plays: 0, last: c.plays ? Date.now() - (60 + c.plays) * DAY : 0 })), owned: coll.length, unplayed: 5, neverPlayed: 2 },
      wishlist: [['Tame Impala', 'Lonerism', 212, 12], ['Khruangbin', 'Mordechai', 164, 10], ['Shame', 'Food for Worms', 131, 10], ['Little Simz', 'Sometimes I Might Be Introvert', 96, 14]]
        .map(([artist, album, plays, tracks]) => ({ artist, album, plays, tracks, last: Date.now() - 9 * DAY, key: '', image: '', search: 'https://www.discogs.com/search/?type=master&q=' + encodeURIComponent(artist + ' ' + album) })),
      top: { artists: order.slice(0, 10).map((a) => ({ name: L.artists[a], plays: ac[a] })), albums: topAlbums,
        tracks: topT.map((t) => ({ title: L.titles[t], artist: L.artists[trackArtist[t]], plays: tc[t] })) },
    };
    // insights and personality, from the numbers above
    const n = sm.scrobbles;
    const ins = [];
    if (sm.prev && sm.prev.scrobbles) ins.push({ kind: 'volume', count: n, prev: sm.prev.scrobbles, pct: Math.round(((n - sm.prev.scrobbles) * 100) / sm.prev.scrobbles) });
    if (order.length) ins.push({ kind: 'topArtist', artist: L.artists[order[0]], plays: ac[order[0]], share: ac[order[0]] / n });
    if (topT.length) ins.push({ kind: 'song', track: L.titles[topT[0]], artist: L.artists[trackArtist[topT[0]]], plays: tc[topT[0]] });
    const byHour = Array.from({ length: 24 }, (_, hr) => [0, 1, 2, 3, 4, 5, 6].reduce((x, d) => x + clock[d * 24 + hr], 0));
    const byDow = Array.from({ length: 7 }, (_, d) => clock.slice(d * 24, d * 24 + 24).reduce((x, y) => x + y, 0));
    const bestDow = byDow.indexOf(Math.max(...byDow));
    const golden = byHour.indexOf(Math.max(...byHour));
    const bestHour = byHour[(golden + 23) % 24] > byHour[(golden + 1) % 24] ? (golden + 23) % 24 : golden;
    if (n >= 20) ins.push({ kind: 'peak', dow: bestDow, hour: bestHour, dowShare: byDow[bestDow] / n });
    const night = [22, 23, 0, 1, 2, 3].reduce((x, hr) => x + byHour[hr], 0) / Math.max(1, n);
    if (n >= 20 && night >= 0.2) ins.push({ kind: 'night', share: night });
    if (!sm.historyStartsHere && sm.newArtists) { const best = order.find((a) => L.first[a] >= r.from * 1000); if (best != null) ins.push({ kind: 'discovery', count: sm.newArtists, best: L.artists[best], plays: ac[best] }); }
    if (r.kind === 'month' && order[5] != null) ins.push({ kind: 'comeback', artist: L.artists[order[5]], plays: ac[order[5]], lastBefore: r.from * 1000 - 160 * DAY });
    if (r.kind !== 'week' && out.streaks.longest >= 4) ins.push({ kind: 'streak', days: out.streaks.longest, to: out.streaks.longestTo });
    ins.push({ kind: 'vinyl', count: Math.round(n * 0.03), share: 0.03 });
    out.insights = ins;
    const topShare = order.length ? ac[order[0]] / n : 0;
    const scores = { nightOwl: night / 0.3, loyalist: topShare / 0.15, explorer: (sm.newArtists / Math.max(1, sm.artists)) / 0.4, weekender: ((byDow[5] + byDow[6]) / Math.max(1, n)) / 0.42 };
    const bestP = Object.keys(scores).sort((x, y) => scores[y] - scores[x]);
    out.personality = n < 30 ? { id: 'few' } : { id: scores[bestP[0]] >= 1 ? bestP[0] : 'allRounder', runnerUp: scores[bestP[1]] >= 1 ? bestP[1] : null,
      metrics: { night, morning: 0.08, weekend: (byDow[5] + byDow[6]) / Math.max(1, n), topShare, topArtist: order.length ? L.artists[order[0]] : '', newShare: 0.12, loop: 5, obsession: 12, vinyl: 0.03, genres: 7 } };
    out.storyExtra = { byHour, topT, tc, trackArtist, order, ac };
    out.tidy = tidySummary();
    const gTotals = counts(0, L.ts.length, (L2, i) => genreOf(L2.a[i]));
    out.genreOptions = mockGenreOptions();
    out.genre = statsGenre;
    // Decades from the made-up records' years; a few plays without a known year.
    const decOf = (a) => Math.floor(RECORDS[a % RECORDS.length][2] / 10) * 10;
    const decs = (j0, j1) => {
      const c = {};
      let known = 0;
      for (let i = j0; i < j1; i++) { if (i % 11 === 0) continue; const d = decOf(L.a[i]); c[d] = (c[d] || 0) + 1; known++; }
      return { known, total: j1 - j0, decades: Object.keys(c).map(Number).sort((a, b) => a - b).map((d) => ({ decade: d, plays: c[d] })) };
    };
    out.decades = decs(i0, i1);
    out.decadeOptions = decs(0, L.ts.length);
    out.decade = statsDecade;
    return out;
  }

  // Everyone on Last.fm, made up from the name: 2K to 6M listeners, 8 to 60 plays each.
  function globalOf(name, mine) {
    const h = hashStr(String(name));
    const listeners = Math.round(2000 * Math.pow(3000, (h % 1000) / 1000));
    const plays = listeners * (8 + (h % 53));
    const avg = plays / listeners;
    return { mine, listeners, plays, avg, ratio: mine / avg, share: mine / plays };
  }
  function fakeVs() {
    const L = history();
    const ac = counts(0, L.ts.length, (L2, i) => L2.a[i]);
    const known = ranked(ac).map((a) => ({ a, name: L.artists[a], photo: '', ...globalOf(L.artists[a], ac[a]) }));
    const mins = [1e6, 25e4, 5e4, 1e4, 0];
    const tiers = mins.map((min) => ({ min, plays: 0, artists: 0, top: '' }));
    known.forEach((x) => { const t = tiers[mins.findIndex((m) => x.listeners >= m)]; t.plays += x.mine; t.artists++; if (!t.top) t.top = x.name; });
    const knownPlays = known.reduce((s, x) => s + x.mine, 0);
    let acc = 0;
    const median = known.slice().sort((p, q) => p.listeners - q.listeners).find((x) => (acc += x.mine) * 2 >= knownPlays).listeners;
    const fans = known.filter((x) => x.mine >= 10);
    const tc = counts(0, L.ts.length, (L2, i) => L2.t[i]);
    const ta = {};
    L.t.forEach((t, i) => { ta[t] = L.a[i]; });
    const songs = ranked(tc).slice(0, 60).map((t) => ({ title: L.titles[t], artist: L.artists[ta[t]], ...globalOf(L.titles[t] + L.artists[ta[t]], tc[t]) }));
    const taste = known.slice(0, 6).map((x, k) => {
      const mine = topTrackOf(x.a);
      const theirs = mine[2] || mine[0];
      return { artist: x.name, photo: '', mine: { title: mine[0].title, plays: mine[0].plays, rank: k === 1 ? 1 : 2 + k * 3 }, theirs: { title: theirs.title, plays: 9e6 / (k + 1), mine: theirs.plays }, heard: 7 - k, of: 10 };
    });
    return { plays: L.ts.length, knownPlays, knownArtists: known.length, artists: known.length, tiers, median, lastfm: true,
      fans: fans.slice().sort((p, q) => q.ratio - p.ratio).slice(0, 10), shares: fans.slice().sort((p, q) => q.share - p.share).slice(0, 5),
      gems: known.filter((x) => x.mine >= 20).sort((p, q) => p.listeners - q.listeners).slice(0, 8), songs: songs.sort((p, q) => q.ratio - p.ratio).slice(0, 10), taste };
  }
  let vsLooked = false;
  // The atlas from the made-up log, worked out like Atlas.kt does (links, spring layout, constellations, shapes).
  let atlasCache = null;
  function fakeAtlas() {
    if (atlasCache) return atlasCache;
    const L = history();
    const GAP = 30 * 60e3;
    const ac = counts(0, L.ts.length, (L2, i) => L2.a[i]);
    const trans = new Map();
    for (let i = 1; i < L.ts.length; i++) if (L.a[i] !== L.a[i - 1] && L.ts[i] - L.ts[i - 1] <= GAP) { const k = L.a[i - 1] + ':' + L.a[i]; trans.set(k, (trans.get(k) || 0) + 1); }
    const start = new Date(L.start);
    const mk = (ts) => { const d = new Date(ts); return (d.getFullYear() - start.getFullYear()) * 12 + d.getMonth() - start.getMonth(); };
    const nm = mk(Date.now()) + 1;
    const stars = ranked(ac).filter((a) => ac[a] >= 5).slice(0, 150);
    const idx = new Map(stars.map((a, i) => [a, i]));
    const monthly = stars.map(() => new Array(nm).fill(0));
    const years = stars.map(() => ({}));
    L.ts.forEach((ts, i) => { const s = idx.get(L.a[i]); if (s == null) return; monthly[s][mk(ts)]++; const y = new Date(ts).getFullYear(); years[s][y] = (years[s][y] || 0) + 1; });
    const n = stars.length, plays = stars.map((a) => ac[a]);
    const cos = (a, b) => { let d = 0, x = 0, y = 0; for (let i = 0; i < a.length; i++) { d += a[i] * b[i]; x += a[i] * a[i]; y += b[i] * b[i]; } return x && y ? d / Math.sqrt(x * y) : 0; };
    const tog = (i, j) => (trans.get(stars[i] + ':' + stars[j]) || 0) + (trans.get(stars[j] + ':' + stars[i]) || 0);
    const strength = (i, j) => tog(i, j) / Math.sqrt(plays[i] * plays[j]);
    const keep = new Set();
    for (let i = 0; i < n; i++) {
      const others = [...Array(n).keys()].filter((j) => j !== i);
      const partners = others.filter((j) => tog(i, j) >= 2).sort((p, q) => strength(i, q) - strength(i, p)).slice(0, 3);
      partners.forEach((j) => keep.add(Math.min(i, j) + ':' + Math.max(i, j)));
      if (partners.length >= 2) continue;
      let best = -1, bc = 0;
      others.forEach((j) => { const c = cos(monthly[i], monthly[j]); if (c > bc) { bc = c; best = j; } });
      if (best >= 0 && bc >= 0.7) keep.add(Math.min(i, best) + ':' + Math.max(i, best));
    }
    const pairs = [...keep].map((k) => k.split(':').map(Number));
    const top = Math.max(1e-9, ...pairs.map(([i, j]) => strength(i, j)));
    const links = pairs.map(([i, j]) => { const c = cos(monthly[i], monthly[j]); return [i, j, tog(i, j), Math.round(c * 1000) / 1000, Math.max(0.05, 0.7 * strength(i, j) / top + 0.3 * c)]; });
    // spring layout from a sunflower spiral
    const x = [], y = [], g = Math.PI * (3 - Math.sqrt(5)), k = Math.sqrt(4 / n);
    for (let i = 0; i < n; i++) { const r = Math.sqrt((i + 0.5) / n); x[i] = r * Math.cos(i * g); y[i] = r * Math.sin(i * g); }
    for (let step = 0; step < 300; step++) {
      const temp = 0.08 * (1 - step / 300) + 0.002, dx = new Array(n).fill(0), dy = new Array(n).fill(0);
      for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) { const ex = x[i] - x[j], ey = y[i] - y[j], f = (k * k) / Math.max(ex * ex + ey * ey, 1e-6); dx[i] += ex * f; dy[i] += ey * f; dx[j] -= ex * f; dy[j] -= ey * f; }
      for (const [a, b, , , w] of links) { const ex = x[a] - x[b], ey = y[a] - y[b], f = (Math.hypot(ex, ey) / k) * (0.3 + 1.7 * w); dx[a] -= ex * f; dy[a] -= ey * f; dx[b] += ex * f; dy[b] += ey * f; }
      for (let i = 0; i < n; i++) { dx[i] -= (x[i] * 0.6) / k; dy[i] -= (y[i] * 0.6) / k; const len = Math.hypot(dx[i], dy[i]); if (len > 0) { const m = Math.min(len, temp) / len; x[i] += dx[i] * m; y[i] += dy[i] * m; } }
    }
    const cx = x.reduce((s, v) => s + v, 0) / n, cy = y.reduce((s, v) => s + v, 0) / n;
    const rr = Math.max(1e-9, ...x.map((v, i) => Math.max(Math.abs(v - cx), Math.abs(y[i] - cy))));
    // constellations: label propagation
    const adj = stars.map(() => []);
    links.forEach(([a, b, , , w]) => { adj[a].push([b, w]); adj[b].push([a, w]); });
    const label = stars.map((_, i) => i);
    for (let round = 0; round < 40; round++) {
      let changed = false;
      for (let u = 0; u < n; u++) {
        if (!adj[u].length) continue;
        const sc = new Map();
        adj[u].forEach(([v, w]) => sc.set(label[v], (sc.get(label[v]) || 0) + w));
        const best = [...sc.entries()].sort((p, q) => q[1] - p[1] || p[0] - q[0])[0][0];
        if (best !== label[u]) { label[u] = best; changed = true; }
      }
      if (!changed) break;
    }
    const groups = [...new Set(label)].map((l) => label.map((v, i) => [v, i]).filter(([v]) => v === l).map(([, i]) => i)).filter((gr) => gr.length >= 3)
      .sort((p, q) => q.reduce((s, i) => s + plays[i], 0) - p.reduce((s, i) => s + plays[i], 0));
    const cl = new Array(n).fill(-1);
    groups.forEach((gr, c) => gr.forEach((i) => { cl[i] = c; }));
    // first plays: what came right before
    const first = {}, from = {};
    L.ts.forEach((ts, i) => { if (first[L.a[i]] == null) first[L.a[i]] = i; });
    Object.entries(first).forEach(([a, f]) => { from[a] = L.ts[f] < L.start + 14 * DAY ? -2 : f > 0 && L.ts[f] - L.ts[f - 1] <= GAP && L.a[f - 1] !== +a ? L.a[f - 1] : -1; });
    const originOf = (a) => ({ how: from[a] >= 0 ? 'after' : from[a] === -2 ? 'start' : 'blue', at: L.ts[first[a]], ...(from[a] >= 0 ? { from: L.artists[from[a]] } : {}) });
    const genreName = (a) => GENRES[genreOf(a)] || '';
    const near = (a, dir) => { let best = -1, bn = 0; trans.forEach((c, kk) => { const [p, q] = kk.split(':').map(Number); if (c >= 2 && (dir ? p === a : q === a) && c > bn) { bn = c; best = dir ? q : p; } }); return best >= 0 ? { name: L.artists[best], n: bn } : null; };
    const nodes = stars.map((a, i) => ({ name: L.artists[a], plays: plays[i], x: Math.round(((x[i] - cx) / rr) * 1000) / 1000, y: Math.round(((y[i] - cy) / rr) * 1000) / 1000, c: cl[i], genre: genreName(a), photo: '', origin: originOf(a), years: years[i], before: near(a, 0), after: near(a, 1) }));
    const clusters = groups.map((gr) => { const byG = {}; gr.forEach((i) => { const gn = genreName(stars[i]); if (gn) byG[gn] = (byG[gn] || 0) + plays[i]; }); return { name: L.artists[stars[gr[0]]], genre: Object.entries(byG).sort((p, q) => q[1] - p[1])[0]?.[0] || '', plays: gr.reduce((s, i) => s + plays[i], 0), size: gr.length, top: gr.slice(0, 5).map((i) => L.artists[stars[i]]) }; });
    const pull = stars.map(() => new Map());
    links.forEach(([a, b, , , w]) => { if (cl[b] >= 0) pull[a].set(cl[b], (pull[a].get(cl[b]) || 0) + w); if (cl[a] >= 0) pull[b].set(cl[a], (pull[b].get(cl[a]) || 0) + w); });
    const bridges = stars.map((_, i) => i).filter((i) => cl[i] >= 0).map((i) => { const own = pull[i].get(cl[i]) || 0; const other = [...pull[i].entries()].filter(([c]) => c !== cl[i]).sort((p, q) => q[1] - p[1])[0]; return other ? [i, other[0], Math.min(own, other[1])] : null; })
      .filter((b) => b && b[2] > 0).sort((p, q) => q[2] - p[2]).slice(0, 4).map(([i, to]) => ({ name: L.artists[stars[i]], from: cl[i], to }));
    // family tree
    const kept = (a) => (ac[a] || 0) >= 5;
    const inTree = new Set(Object.keys(first).map(Number).filter(kept));
    [...inTree].forEach((a) => { let p = from[a]; while (p >= 0 && !inTree.has(p)) { inTree.add(p); p = from[p]; } });
    const desc = {};
    [...inTree].sort((p, q) => first[q] - first[p]).forEach((a) => { const p = from[a]; if (p >= 0) desc[p] = (desc[p] || 0) + (desc[a] || 0) + (kept(a) ? 1 : 0); });
    const ids = [...inTree];
    const keptIds = Object.keys(first).map(Number).filter(kept);
    const tree = { nodes: ids.map((a) => ({ id: a, name: L.artists[a], plays: ac[a] || 0, at: L.ts[first[a]], p: from[a], d: desc[a] || 0, kept: kept(a) })),
      gateways: ids.filter((a) => (desc[a] || 0) >= 2).sort((p, q) => desc[q] - desc[p]).slice(0, 8).map((a) => ({ id: a, name: L.artists[a], d: desc[a], photo: '' })),
      after: keptIds.filter((a) => from[a] >= 0).length, blue: keptIds.filter((a) => from[a] === -1).length, start: keptIds.filter((a) => from[a] === -2).length, kept: keptIds.length };
    // landscape
    const shape = (c) => {
      const total = c.reduce((s, v) => s + v, 0); if (!total) return { kind: 'phase', months: 0 };
      const peak = Math.max(...c), floor = Math.max(2, Math.ceil(peak / 10)), act = c.map((v, i) => [v, i]).filter(([v]) => v >= floor).map(([, i]) => i);
      let gap = 0, before = 0, k0 = 0;
      for (let q = 1; q < act.length; q++) { while (k0 <= act[q - 1]) before += c[k0++]; const gg = act[q] - act[q - 1] - 1; if (gg > gap && before >= total * 0.1 && total - before >= total * 0.1) gap = gg; }
      const top4 = [...c].sort((p, q) => q - p).slice(0, 4).reduce((s, v) => s + v, 0);
      if (gap >= 6) return { kind: 'comeback', months: gap };
      if (act.length <= 4 && top4 >= total * 0.75) return { kind: 'obsession', months: act.length };
      if (act.length >= 12) return { kind: 'companion', months: act[act.length - 1] - act[0] + 1 };
      return { kind: 'phase', months: act.length };
    };
    const monthStart = (m) => +new Date(start.getFullYear(), start.getMonth() + m, 1);
    const series = ranked(ac).slice(0, 14).map((a) => {
      const c = new Array(nm).fill(0);
      L.ts.forEach((ts, i) => { if (L.a[i] === a) c[mk(ts)]++; });
      const pk = c.indexOf(Math.max(...c));
      return { name: L.artists[a], plays: ac[a], counts: c, peak: c[pk], peakAt: monthStart(pk), ...shape(c), genre: genreName(a), photo: '', from: monthStart(c.findIndex((v) => v > 0)), _pk: pk };
    }).sort((p, q) => p._pk - q._pk);
    atlasCache = { plays: L.ts.length, universe: { nodes, clusters, links: links.map((l) => [l[0], l[1], l[2], l[3], Math.round(l[4] * 1000) / 1000]), bridges, plays: plays.reduce((s, v) => s + v, 0) }, tree, landscape: { monthsFrom: monthStart(0), months: nm, series } };
    return atlasCache;
  }
  function fakeStory(id) {
    const s = fakeStats(id);
    const L = history();
    const n = s.summary.scrobbles;
    const x = s.storyExtra;
    const gTot = s.genres.periods.reduce((acc, p) => { p.shares.forEach((v, k) => { acc[k] = (acc[k] || 0) + v * p.total; }); return acc; }, []);
    const gAll = gTot.slice(0, 6).reduce((a, b) => a + b, 0) || 1;
    const genres = gTot.slice(0, 6).map((v, k) => ({ name: GENRES[k], share: v / gAll })).sort((p, q) => q.share - p.share).slice(0, 5);
    return {
      range: s.range, summary: s.summary, personality: s.personality,
      artists: x.order.slice(0, 5).map((a) => ({ name: L.artists[a], plays: x.ac[a], share: x.ac[a] / n, days: Math.min(s.summary.days, Math.round(x.ac[a] / 3)), cover: '' })),
      tracks: x.topT.slice(0, 5).map((t) => ({ title: L.titles[t], artist: L.artists[x.trackArtist[t]], plays: x.tc[t] })),
      songBestDay: x.topT.length ? { day: s.range.from * 1000 + 4 * DAY, count: Math.min(x.tc[x.topT[0]], 7) } : null,
      albums: s.top.albums.slice(0, 5), hours: x.byHour, goldenHour: x.byHour.indexOf(Math.max(...x.byHour)),
      busiestDay: { day: s.range.from * 1000 + 9 * DAY, count: 61 },
      newArtists: { count: s.summary.historyStartsHere ? 0 : s.summary.newArtists, best: x.order.length ? { name: L.artists[x.order[Math.min(6, x.order.length - 1)]], plays: 44, first: s.range.from * 1000 + 2 * DAY } : null },
      loop: s.loops[0] || null, obsession: s.obsessions[0] || null, genres,
      vinyl: { count: Math.round(n * 0.03), share: 0.03, top: [{ title: 'Rumours', artist: 'Fleetwood Mac', plays: 44, cover: '' }] },
      streak: s.streaks, insights: s.insights,
      first: n ? { track: L.titles[L.t[s.summary.i0]], artist: L.artists[L.a[s.summary.i0]], ts: L.ts[s.summary.i0] } : null,
      vs: x.order.length ? { name: L.artists[x.order[0]], photo: '', ...globalOf(L.artists[x.order[0]], counts(0, L.ts.length, (L2, i) => L2.a[i])[x.order[0]]) } : null,
    };
  }

  // ---- tidy up: made-up junk scrobbles to hide ----
  const TIDY_STATE = {
    suggestions: [
      { artist: 'Someone', title: 'Dubz', plays: 214, last: Date.now() - 40 * DAY, reasons: ['clip'] },
      { artist: 'HBO Max', title: 'HBO Max', plays: 96, last: Date.now() - 12 * DAY, reasons: ['app', 'sameName'] },
      { artist: 'Gaming Channel', title: 'Loop Hero Launch Trailer', plays: 41, last: Date.now() - 300 * DAY, reasons: ['video'] },
      { artist: 'Caulse', title: 'CAULSE | V', plays: 22, last: Date.now() - 200 * DAY, reasons: ['videoTitle'] },
      { artist: 'Podcast Corner', title: 'Folge 12: Vinyl', plays: 3, last: Date.now() - 90 * DAY, reasons: ['video'] },
    ],
    noAlbum: [
      { artist: 'Ulepuschkinrose', title: 'Lied vom Meer', plays: 38, last: Date.now() - 5 * DAY, reasons: [] },
      { artist: 'Fontaines D.C.', title: 'Starburster (Live at Glastonbury)', plays: 12, last: Date.now() - 30 * DAY, reasons: [] },
      { artist: 'IDLES', title: 'Grace (Radio Edit)', plays: 7, last: Date.now() - 60 * DAY, reasons: [] },
    ],
    hidden: { artists: [], tracks: [], kept: [] },
    hiddenPlays: 0,
  };
  const tidyOf = () => ({ ...TIDY_STATE, user: S.lastfm.user || '' });
  function tidySummary() {
    const sg = TIDY_STATE.suggestions;
    return { count: sg.length, plays: sg.reduce((n, x) => n + x.plays, 0), examples: sg.slice(0, 3).map((x) => x.title), hiddenPlays: TIDY_STATE.hiddenPlays };
  }
  function tidyApply(t, items) {
    const T = TIDY_STATE;
    const same = (x, it) => x.artist === it.artist && x.title === it.title;
    const take = (list, it) => { const i = list.findIndex((x) => same(x, it)); return i < 0 ? null : list.splice(i, 1)[0]; };
    items.forEach((it) => {
      if (t === 'keep') { take(T.suggestions, it); T.hidden.kept.push({ artist: it.artist, title: it.title }); return; }
      if (it.kind === 'artist') {
        if (t === 'hide' && !T.hidden.artists.includes(it.artist)) { T.hidden.artists.push(it.artist); T.hiddenPlays += 50; }
        if (t === 'unhide') { T.hidden.artists = T.hidden.artists.filter((a) => a !== it.artist); T.hiddenPlays = Math.max(0, T.hiddenPlays - 50); }
        return;
      }
      if (t === 'hide') {
        const x = take(T.suggestions, it) || take(T.noAlbum, it) || { artist: it.artist, title: it.title, plays: 5, last: Date.now(), reasons: [] };
        T.hidden.tracks.push(x);
        T.hiddenPlays += x.plays;
      } else {
        const x = take(T.hidden.tracks, it);
        if (x) { T.hiddenPlays -= x.plays; (x.reasons.length ? T.suggestions : T.noAlbum).push(x); }
      }
    });
    T.suggestions.sort((a, b) => b.plays - a.plays);
    T.noAlbum.sort((a, b) => b.plays - a.plays);
  }

  // ---- song, album, explore and playlists, from the made-up log ----
  const weekStartOf = (ms) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - ((d.getDay() + 6) % 7)); return +d; };
  /** Plays per week (Monday-based) of the matching scrobbles, from the first week until now. */
  function weeksOf(match) {
    const L = history();
    let w0 = 0;
    const counts = {};
    for (let i = 0; i < L.ts.length; i++) {
      if (!match(i)) continue;
      const w = weekStartOf(L.ts[i]);
      if (!w0) w0 = w;
      const k = Math.round((w - w0) / (7 * DAY));
      counts[k] = (counts[k] || 0) + 1;
    }
    if (!w0) return {};
    const n = Math.round((weekStartOf(Date.now()) - w0) / (7 * DAY)) + 1;
    return { weeksFrom: w0, weeks: Array.from({ length: n }, (_, k) => counts[k] || 0) };
  }
  function hslHex(h, s, l) {
    const f = (n) => { const k = (n + h / 30) % 12; const a = s * Math.min(l, 1 - l); return Math.round(255 * (l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1)))); };
    return '#' + [f(0), f(8), f(4)].map((v) => v.toString(16).padStart(2, '0')).join('');
  }
  function lifeOf(match) {
    const L = history();
    let plays = 0, first = -1, last = -1, days = 0, lastDay = -1, dc = 0, bestDay = 0, bestCount = 0;
    const months = {};
    const hours = new Array(24).fill(0);
    for (let i = 0; i < L.ts.length; i++) {
      if (!match(i)) continue;
      plays++;
      if (first < 0) first = i;
      last = i;
      const d = new Date(L.ts[i]);
      const mk = d.getFullYear() * 12 + d.getMonth();
      months[mk] = (months[mk] || 0) + 1;
      hours[d.getHours()]++;
      const di = dayIdx(L.ts[i]);
      if (di !== lastDay) { days++; lastDay = di; dc = 0; }
      if (++dc > bestCount) { bestCount = dc; bestDay = dayMs(di); }
    }
    if (!plays) return null;
    const m0 = Math.min(...Object.keys(months).map(Number));
    const now = new Date();
    const arr = [];
    for (let m = m0; m <= now.getFullYear() * 12 + now.getMonth(); m++) arr.push(months[m] || 0);
    return { ...weeksOf(match), plays, days, first: L.ts[first], last: L.ts[last], monthsFrom: +new Date(Math.floor(m0 / 12), m0 % 12, 1), months: arr, hours, bestDay: { day: bestDay, count: bestCount } };
  }
  const rankIn = (c, k) => (c[k] ? Object.values(c).filter((v) => v > c[k]).length + 1 : 0);
  const albumOf = (a) => RECORDS[a % RECORDS.length][1];
  const findArtist = (name) => history().artists.findIndex((n) => n.toLowerCase() === String(name).toLowerCase());
  function rangeCounts(key) {
    const L = history();
    const r = mockRange(statsRange);
    return { r, c: counts(lowerBound(L.ts, r.from * 1000), lowerBound(L.ts, r.to * 1000), key) };
  }
  function fakeTrack(artist, title) {
    const L = history();
    const a = findArtist(artist);
    const t = L.titles.findIndex((n) => n.toLowerCase() === String(title).toLowerCase());
    const life = a >= 0 && t >= 0 ? lifeOf((i) => L.t[i] === t) : null;
    if (!life) return { missing: true, artist, title };
    const all = counts(0, L.ts.length, (L2, i) => L2.t[i]);
    const { r, c } = rangeCounts((L2, i) => L2.t[i]);
    const next = {};
    for (let i = 0; i + 1 < L.ts.length; i++) {
      if (L.t[i] === t && L.t[i + 1] !== t && L.ts[i + 1] - L.ts[i] < 20 * 60e3) next[L.t[i + 1]] = { a: L.a[i + 1], n: ((next[L.t[i + 1]] || {}).n || 0) + 1 };
    }
    const ap = counts(0, L.ts.length, (L2, i) => L2.a[i]);
    // Where it sits in sessions (half an hour apart) and its runs back to back, as the app works them out.
    const sits = { open: 0, middle: 0, close: 0, alone: 0 }, all4 = [0, 0, 0, 0], runs = [];
    let run = 0, runAt = 0;
    for (let i = 0; i < L.ts.length; i++) {
      const starts = i === 0 || L.ts[i] - L.ts[i - 1] > 30 * 60e3, ends = i === L.ts.length - 1 || L.ts[i + 1] - L.ts[i] > 30 * 60e3;
      const at = starts && ends ? 3 : starts ? 0 : ends ? 2 : 1;
      all4[at]++;
      if (L.t[i] !== t) continue;
      sits[['open', 'middle', 'close', 'alone'][at]]++;
      if (i > 0 && L.t[i - 1] === t && L.ts[i] - L.ts[i - 1] < 20 * 60e3) run++;
      else { if (run >= 2) runs.push({ n: run, at: runAt }); run = 1; runAt = L.ts[i]; }
    }
    if (run >= 2) runs.push({ n: run, at: runAt });
    const inS = Math.max(1, all4[0] + all4[1] + all4[2]);
    Object.assign(sits, { baseOpen: all4[0] / inS, baseClose: all4[2] / inS });
    return { ...life, title: L.titles[t], artist: L.artists[a], rank: rankIn(all, t), tracks: Object.keys(all).length, shareOfArtist: life.plays / ap[a], sits, runs,
      range: { ...r, plays: c[t] || 0, rank: rankIn(c, t) },
      next: Object.entries(next).sort((x, y) => y[1].n - x[1].n).slice(0, 5).map(([k, v]) => ({ title: L.titles[k], artist: L.artists[v.a], count: v.n })),
      album: { title: albumOf(a), artist: L.artists[a], cover: '', key: '', owned: a % 3 === 0 ? 1 : 0 },
      global: globalOf(L.titles[t] + L.artists[a], life.plays), globalRank: 2 };
  }
  /** A page's river, as the app works it out: plays per month of its [partOf] parts since its first play. */
  function riverOf(match, partOf, name) {
    const L = history();
    let first = -1;
    const tot = {};
    for (let i = 0; i < L.ts.length; i++) if (match(i)) { if (first < 0) first = i; const q = partOf(i); if (q >= 0) tot[q] = (tot[q] || 0) + 1; }
    if (first < 0) return null;
    const order = Object.keys(tot).map(Number).sort((x, y) => tot[y] - tot[x]).slice(0, 8);
    const d0 = new Date(L.ts[first]), now = new Date();
    const m0 = d0.getFullYear() * 12 + d0.getMonth(), B = now.getFullYear() * 12 + now.getMonth() - m0 + 1;
    if (order.length < 2 || B < 4) return null;
    const series = order.map(() => new Array(B).fill(0)), other = new Array(B).fill(0);
    for (let i = first; i < L.ts.length; i++) {
      if (!match(i)) continue;
      const d = new Date(L.ts[i]), k = d.getFullYear() * 12 + d.getMonth() - m0, s = order.indexOf(partOf(i));
      if (s >= 0) series[s][k]++; else other[k]++;
    }
    return { unit: 'month', starts: Array.from({ length: B }, (_, k) => +new Date(Math.floor((m0 + k) / 12), (m0 + k) % 12, 1)), artists: order.map(name), series, other };
  }
  const looseTitle = (t) => String(t || '').toLowerCase().replace(/\s*[([][^)\]]*[)\]]/g, '').trim();
  function fakeAlbum(artist, title) {
    const L = history();
    const a = findArtist(artist);
    // Like the app: only the album the made-up log plays has a history.
    const life = a >= 0 && looseTitle(title) === looseTitle(albumOf(a)) ? lifeOf((i) => L.a[i] === a) : null;
    if (!life) return { missing: true, artist, title };
    const all = counts(0, L.ts.length, (L2, i) => L2.a[i]);
    const { r, c } = rangeCounts((L2, i) => L2.a[i]);
    const tc = counts(0, L.ts.length, (L2, i) => (L2.a[i] === a ? L2.t[i] : -1));
    delete tc[-1];
    return { ...life, title, artist: L.artists[a], rank: rankIn(all, a), albums: Object.keys(all).length,
      info: { title, artist: L.artists[a], cover: '', key: '', owned: a % 3 === 0 ? 1 : 0 },
      range: { ...r, plays: c[a] || 0, rank: rankIn(c, a) },
      trackList: Object.entries(tc).sort((x, y) => y[1] - x[1]).map(([k, v]) => ({ title: L.titles[k], plays: v })),
      global: globalOf(title + L.artists[a], life.plays), river: riverOf((i) => L.a[i] === a, (i) => L.t[i], (k) => L.titles[k]) };
  }
  function artistLives() {
    const L = history();
    const n = L.artists.length;
    const x = Array.from({ length: n }, () => ({ plays: 0, first: 0, last: 0, months: new Set(), days: new Set(), peak: {} }));
    for (let i = 0; i < L.ts.length; i++) {
      const o = x[L.a[i]];
      if (!o.plays++) o.first = L.ts[i];
      o.last = L.ts[i];
      const d = new Date(L.ts[i]);
      const mk = +new Date(d.getFullYear(), d.getMonth(), 1);
      o.months.add(mk);
      o.days.add(dayIdx(L.ts[i]));
      o.peak[mk] = (o.peak[mk] || 0) + 1;
    }
    return x.map((o, a) => {
      const [pm, pc] = Object.entries(o.peak).sort((p, q) => q[1] - p[1])[0] || [0, 0];
      return { a, name: L.artists[a], plays: o.plays, first: o.first, last: o.last, months: o.months.size, days: o.days.size, cover: '', peak: { start: +pm, count: pc } };
    }).filter((o) => o.plays);
  }
  function topTrackOf(a) {
    const L = history();
    const tc = counts(0, L.ts.length, (L2, i) => (L2.a[i] === a ? L2.t[i] : -1));
    delete tc[-1];
    return Object.entries(tc).sort((x, y) => y[1] - x[1]).map(([k, v]) => ({ title: L.titles[k], artist: L.artists[a], album: albumOf(a), plays: v }));
  }
  function fakeExplore() {
    const L = history();
    const lives = artistLives().sort((x, y) => y.plays - x.plays);
    const withTrack = (o) => ({ ...o, track: (topTrackOf(o.a)[0] || {}).title || '' });
    // The made-up log has few quiet artists, so the oldest last plays stand in for lost ones.
    const lost = lives.slice().sort((x, y) => x.last - y.last).slice(0, 6).sort((x, y) => y.plays - x.plays).map(withTrack);
    const regulars = lives.slice(8).filter((o) => o.months >= 4).sort((x, y) => y.months - x.months).slice(0, 8).map(withTrack);
    const oneDay = lives.filter((o) => o.days <= 2).slice(0, 6).map(withTrack);
    const deepCuts = lives.slice(0, 5).flatMap((o) => topTrackOf(o.a).slice(-2));
    const today = new Date();
    const otd = [today.getFullYear() - 1, today.getFullYear() - 2].map((y) => {
      const from = +new Date(y, today.getMonth(), today.getDate());
      const i0 = lowerBound(L.ts, from), i1 = lowerBound(L.ts, from + DAY);
      if (i1 <= i0) return null;
      const tc = counts(i0, i1, (L2, i) => L2.t[i]);
      const ac = counts(i0, i1, (L2, i) => L2.a[i]);
      const ta = {};
      for (let i = i0; i < i1; i++) ta[L.t[i]] = L.a[i];
      return { year: y, plays: i1 - i0, artists: ranked(ac).slice(0, 3).map((a) => L.artists[a]),
        tracks: ranked(tc).slice(0, 4).map((t) => ({ title: L.titles[t], artist: L.artists[ta[t]], album: albumOf(ta[t]), plays: tc[t] })) };
    }).filter(Boolean);
    const gopts = mockGenreOptions();
    return { genreMap: { all: L.ts.length, known: gopts.reduce((n, o) => n + o.plays, 0), families: gopts }, tail: { artists: lives.length, few: lives.filter((o) => o.plays < 5).length, fewPlays: 5 },
      lostArtists: lost, lostAlbums: lost.slice(0, 5).map((o) => ({ title: albumOf(o.a), artist: o.name, cover: '', key: '', owned: 0, plays: Math.round(o.plays * 0.6), last: o.last })),
      regulars, oneDay, deepCuts, onThisDay: otd };
  }
  function fakePlaylist(kind) {
    const L = history();
    const x = fakeExplore();
    const of = (list) => list.flatMap((o) => topTrackOf(o.a).slice(0, 2));
    const names = { lost: ['Lost favorites', 'Artists you loved who have gone quiet', () => of(x.lostArtists)],
      lostAlbums: ['Albums to rediscover', 'The songs you played most from albums gone quiet', () => of(x.lostArtists)],
      regulars: ['Quiet regulars', 'Always around, never on top', () => of(x.regulars)],
      oneDay: ['One-day wonders', 'Played all day once, never again', () => of(x.oneDay)],
      deepCuts: ['Deep cuts', 'Songs by your favorite artists you hardly know', () => x.deepCuts],
      onThisDay: ['On this day', 'What you played on this day in earlier years', () => x.onThisDay.flatMap((y) => y.tracks)],
      dig: ['Crate dig', 'Songs you played a few times, then forgot', () => artistLives().flatMap((o) => topTrackOf(o.a).slice(-1)).sort(() => Math.random() - 0.5)] };
    if (kind === 'top' || !names[kind]) {
      const r = mockRange(statsRange);
      const tc = counts(lowerBound(L.ts, r.from * 1000), lowerBound(L.ts, r.to * 1000), (L2, i) => L2.t[i]);
      const ta = {};
      L.t.forEach((t, i) => { ta[t] = L.a[i]; });
      return { kind: 'top', range: r, tracks: ranked(tc).slice(0, 40).map((t) => ({ title: L.titles[t], artist: L.artists[ta[t]], album: albumOf(ta[t]), plays: tc[t] })) };
    }
    const [name, note, fn] = names[kind];
    return { kind, name, note, tracks: fn().slice(0, 40) };
  }

  // Made-up discographies: the artist's album from the made-up log, more albums, an EP, singles.
  const MOCK_TITLES = ['Night Drive', 'Glass Houses', 'Paper Moons', 'Static Bloom', 'Low Tide', 'Neon Psalms', 'Field Notes', 'Afterglow'];
  function fakeDiscography(name) {
    const L = history();
    const ai = findArtist(name);
    if (ai < 0) return { name, none: true };
    const r = rng(hashStr(name) + 11);
    const main = albumOf(ai);
    const owned = (t) => (coll.find((c) => c.artist === name && c.title === t) || {}).id || 0;
    const plays = counts(0, L.ts.length, (L2, i) => L2.a[i])[ai] || 0;
    const day = (y, m, d) => `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    const items = [
      { title: MOCK_TITLES[ai % 8], type: 'album', date: day(2026, 10, 3), plays: 0 },
      { title: main, type: 'album', date: day(2022, 4, 22), plays: Math.round(plays * 0.6) },
      { title: MOCK_TITLES[(ai + 3) % 8], type: 'album', date: day(2019, 4, 12), plays: Math.round(plays * 0.15) },
      { title: MOCK_TITLES[(ai + 5) % 8], type: 'album', date: day(2016, 9, 2), plays: 0 },
      { title: 'Live at the Lighthouse', type: 'ep', date: day(2021, 11, 26), plays: 0 },
      ...[0, 1, 2, 3, 4].map((k) => ({ title: L.titles[(ai * 7 + k) % L.titles.length], type: 'single', date: day(2026 - k, 1 + (k * 3) % 12, 10), plays: k < 2 ? 4 + Math.floor(r() * 20) : 0 })),
    ];
    return { name, artist: { id: ai + 1, name, link: 'https://www.deezer.com/artist/0', fans: 1000 + ai * 777 },
      items: items.map((x, k) => ({ id: (ai + 1) * 100 + k, cover: '', link: '', editions: k === 1 ? 2 : 1, year: +x.date.slice(0, 4), owned: owned(x.title), ...x })) };
  }
  function fakeDzAlbum(id) {
    const L = history();
    const ai = Math.floor(id / 100) - 1;
    const tracks = Array.from({ length: 10 }, (_, k) => ({ title: L.titles[(Math.max(0, ai) * 7 + k) % L.titles.length], sec: 150 + ((id * 7 + k * 37) % 180) }));
    return { dz: id, title: '', date: '2022-04-22', label: 'Mock Records', genres: ['Alternative'], tracks };
  }

  function fakeArtist(name) {
    const L = history();
    const ai = L.artists.indexOf(name);
    const r = rng(hashStr(name) + 3);
    const months = [];
    const hours = new Array(24).fill(0);
    const first = new Date(L.start);
    for (let i = 0; i < L.ts.length; i++) {
      if (L.a[i] !== ai) continue;
      const d = new Date(L.ts[i]);
      const mk = (d.getFullYear() - first.getFullYear()) * 12 + d.getMonth() - first.getMonth();
      months[mk] = (months[mk] || 0) + 1;
      hours[d.getHours()]++;
    }
    const nowD = new Date();
    const len = (nowD.getFullYear() - first.getFullYear()) * 12 + nowD.getMonth() - first.getMonth() + 1;
    const mArr = Array.from({ length: len }, (_, i) => months[i] || 0);
    const plays = mArr.reduce((x, y) => x + y, 0);
    const owned = coll.filter((c) => c.artist === name).map((c) => ({ id: c.id, title: c.title, artist: c.artist, cover: '', year: c.year }));
    const bm = mArr.indexOf(Math.max(...mArr));
    return {
      ...weeksOf((i) => L.a[i] === ai), photo: '', cover: '',
      name, plays, days: Math.round(plays / 3),
      first: { ts: L.first[ai] > 0 ? L.first[ai] : Date.now(), track: L.titles[ai] || 'Track' }, last: { ts: Date.now() - 2 * DAY, track: 'Pelota' },
      busiestMonth: { start: +new Date(first.getFullYear(), first.getMonth() + bm, 1), count: mArr[bm] || 0 },
      monthsFrom: +new Date(first.getFullYear(), first.getMonth(), 1), months: mArr, hours, streak: 6 + Math.floor(r() * 6), streakEnd: Date.now() - 52 * DAY,
      range: Object.assign({}, mockRange(statsRange), { plays: Math.round(plays / 8), rank: 2 }),
      tracks: [0, 1, 2, 3, 4].map((i) => ({ title: L.titles[(ai * 7 + i) % L.titles.length], plays: 60 - i * 9 })),
      albums: [{ title: RECORDS[Math.max(0, ai) % RECORDS.length][1], plays: Math.round(plays * 0.6), cover: '', owned: owned.length > 0 }],
      owned, genres: [GENRES[Math.max(0, genreOf(ai))] || 'Indie Rock'],
      global: globalOf(name, plays),
      ...(() => {
        const t = fakeAtlas().tree;
        const byName = new Map(t.nodes.map((nd) => [nd.name, nd]));
        const me = byName.get(name);
        if (!me) return {};
        const byId = new Map(t.nodes.map((nd) => [nd.id, nd]));
        const how = (nd) => (nd.p >= 0 ? 'after' : nd.p === -2 ? 'start' : 'blue');
        const chain = [];
        for (let p = byId.get(me.p); p && chain.length < 6; p = byId.get(p.p)) chain.push({ name: p.name, plays: p.plays, how: how(p), at: p.at, ...(p.p >= 0 && byId.get(p.p) ? { from: byId.get(p.p).name } : {}) });
        return { origin: { how: how(me), at: me.at, ...(me.p >= 0 && byId.get(me.p) ? { from: byId.get(me.p).name } : {}), chain },
          ledTo: t.nodes.filter((nd) => nd.p === me.id && nd.kept).sort((p, q) => q.plays - p.plays).slice(0, 8).map((nd) => ({ name: nd.name, plays: nd.plays, at: nd.at })) };
      })(),
      globalTop: (() => {
        const mine = topTrackOf(ai);
        return [mine[2], mine[0], { title: 'The Radio Edit', plays: 0 }, mine[1], ...mine.slice(3, 9)].filter(Boolean)
          .map((x, i) => ({ rank: i + 1, title: x.title, plays: Math.round(9e6 / (i + 1)), mine: x.plays || 0 }));
      })(),
    };
  }

  // One genre's page: the plays of the artists the made-up log files under it.
  function fakeGenre(asked) {
    const L = history();
    const key = mockResolve(asked);
    const isSub = key.includes(':'), fam = key.split(':')[0];
    const inKey = (gi) => gi >= 0 && gi < 6 && (isSub ? MSUB[gi] === key : MFAM[gi] === key);
    const match = (i) => inKey(genreOf(L.a[i]));
    let plays = 0, first = 0, last = 0, bestDay = 0, bestCount = 0, run = 0, runDay = -1;
    const days = new Set(), months = {}, hours = new Array(24).fill(0);
    const ac = {}, tc = {}, ta = {};
    const start = new Date(L.start);
    for (let i = 0; i < L.ts.length; i++) {
      if (!match(i)) continue;
      if (!plays++) first = L.ts[i];
      last = L.ts[i];
      const d = new Date(L.ts[i]);
      const di = dayIdx(L.ts[i]);
      days.add(di);
      run = di === runDay ? run + 1 : 1;
      runDay = di;
      if (run > bestCount) { bestCount = run; bestDay = +new Date(d.getFullYear(), d.getMonth(), d.getDate()); }
      const mk = (d.getFullYear() - start.getFullYear()) * 12 + d.getMonth() - start.getMonth();
      months[mk] = (months[mk] || 0) + 1;
      hours[d.getHours()]++;
      ac[L.a[i]] = (ac[L.a[i]] || 0) + 1;
      tc[L.t[i]] = (tc[L.t[i]] || 0) + 1;
      ta[L.t[i]] = L.a[i];
    }
    if (!plays) return { key, missing: true };
    const nowD = new Date();
    const len = (nowD.getFullYear() - start.getFullYear()) * 12 + nowD.getMonth() - start.getMonth() + 1;
    const mArr = Array.from({ length: len }, (_, i) => months[i] || 0);
    const bm = mArr.indexOf(Math.max(...mArr));
    const order = ranked(ac);
    const decs = {};
    order.forEach((a) => { const dd = Math.floor(RECORDS[a % RECORDS.length][2] / 10) * 10; decs[dd] = (decs[dd] || 0) + ac[a]; });
    const x = fakeExplore();
    const inG = (o) => inKey(genreOf(o.a));
    const opts = mockGenreOptions(), fo = opts.find((o) => o.key === fam) || { plays, subs: [] };
    return {
      key, kind: isSub ? 'sub' : 'family', label: isSub ? subName(key) : FAMNAME[key], plays, days: days.size, first, last,
      family: { key: fam, label: FAMNAME[fam], plays: fo.plays, rank: opts.indexOf(fo) + 1 }, inside: fo.subs,
      general: fo.plays - fo.subs.reduce((n, s) => n + s.plays, 0), ofFamily: plays / Math.max(1, fo.plays),
      shelf: coll.filter((r, i) => i % 4 === (isSub ? 1 : 0)).slice(0, 5).map((r) => r.id),
      share: plays / L.ts.length, rank: isSub ? 1 : opts.indexOf(fo) + 1,
      artists: order.length, all: order.map((a) => ({ name: L.artists[a], plays: ac[a] })), ...weeksOf(match),
      monthsFrom: +new Date(start.getFullYear(), start.getMonth(), 1), months: mArr, hours,
      busiestMonth: { start: +new Date(start.getFullYear(), start.getMonth() + bm, 1), count: mArr[bm] || 0 },
      bestDay: { day: bestDay, count: bestCount },
      top: {
        artists: order.slice(0, 10).map((a) => ({ name: L.artists[a], plays: ac[a], photo: '' })),
        albums: order.slice(0, 8).map((a, i) => ({ title: albumOf(a), artist: L.artists[a], plays: Math.round(ac[a] * 0.7), cover: '', owned: i % 3 !== 2 })),
        tracks: ranked(tc).slice(0, 10).map((t) => ({ title: L.titles[t], artist: L.artists[ta[t]], plays: tc[t] })),
      },
      lost: x.lostArtists.filter(inG), lostAlbums: x.lostAlbums.filter((o) => inKey(genreOf(findArtist(o.artist)))),
      decades: { known: plays, total: plays, decades: Object.keys(decs).map(Number).sort((a, b) => a - b).map((d) => ({ decade: d, plays: decs[d] })) },
    };
  }

  function tick() {
    phoneTick();
    if (!spin) return;
    const now = Date.now();
    let changed = scrobbleDue(now, false);
    const end = Math.max(...spin.items.filter((x) => x.state !== 'skipped').map((x) => x.start + x.dur * 1000));
    if (!spin.items.some((x) => x.state === 'planned') && now >= end) { spin = null; changed = true; }
    if (changed) { pushState(); pushAct(); }
  }

  function startSpin(m) {
    const rel = release(m.releaseId);
    const def = S.prefs.defaultTrackSec;
    const tracks = m.tracks.map((i) => { const t = rel.tracks[i]; return { title: t.title, artist: t.artist, pos: t.pos, side: t.side, dur: t.dur > 0 ? t.dur : def, est: !(t.dur > 0) }; });
    const now = Date.now();
    const p = planStarts(tracks, m.when, m.at, now);
    const items = tracks.map((t, i) => ({ ...t, start: p.starts[i], state: 'planned' }));
    const c = coll.find((x) => x.id === rel.id);
    if (c) c.spins = (c.spins || 0) + 1;
    if (p.end <= now) {
      for (const it of items) {
        const old = now - it.start > 14 * DAY;
        act.push(actEntry({ ...it, album: rel.title }, it.start, old ? 'ignored' : 'sent', old ? 'Too old: Last.fm only takes scrobbles from the last 2 weeks' : ''));
      }
      pushAct(); pushState(); pushColl();
      return;
    }
    if (spin) { scrobbleDue(now, false); spin.items.forEach((x) => { if (x.state === 'planned') x.state = 'skipped'; }); }
    spin = { id: `${rel.id}-${now}`, releaseId: rel.id, album: rel.title, artist: rel.artist, cover: '', items, nextSide: null };
    spin.nextSide = nextSide(rel, items);
    tick();
    pushState(); pushAct(); pushColl();
  }

  return {
    init() {
      if (mode === 'new') {
        Object.assign(S.lastfm, { hasKey: false, connected: false, user: '', keyHint: '' });
        Object.assign(S.discogs, { connected: false, user: '', lastSync: 0 });
        Object.assign(S.phone, { mode: 'off', access: false, connected: false, apps: S.phone.apps.slice(0, 1) });
        Object.assign(S.history, { count: 0, done: false, lastSync: 0 });
      } else {
        // A few days of shadow-mode listening.
        const now = Date.now();
        for (let d = 3; d >= 1; d--) {
          let t = now - d * DAY - 5 * 3600e3;
          for (let k = 0; k < 6; k++) {
            const [artist, track, album, dur] = PHONE_TRACKS[k % PHONE_TRACKS.length];
            act.push({ id: 'p' + seq++, artist, track, album, ts: Math.floor(t / 1000), src: 'phone', app: 'TIDAL', cover: '', status: 'shadow', msg: '', at: t });
            t += dur * 1000;
          }
        }
        makeCollection();
        const y = Date.now() - DAY - 3 * 3600e3;
        const rel = release(1000);
        let t = y;
        for (const tr of rel.tracks.filter((x) => x.side === 'A')) { act.push(actEntry({ ...tr, album: rel.title }, t, 'sent')); t += tr.dur * 1000; }
        const kob = release(1001);
        act.push(actEntry({ ...kob.tracks[0], album: kob.title }, Date.now() - 19 * DAY, 'ignored', 'Too old: Last.fm only takes scrobbles from the last 2 weeks'));
      }
      if (mode === 'phone') {
        // Something playing in TIDAL on the phone, no record spinning.
        S.phone.now = { pkg: 'com.aspiro.tidal', app: 'TIDAL', artist: 'Fontaines D.C.', title: 'Starburster', album: 'Romance', dur: 212000, listened: 64000, at: Date.now(), playing: true, threshold: 106000, scrobbled: false, eligible: true, on: true, cover: '' };
      }
      if (mode === 'spin') {
        const rel = release(1000);
        startSpin({ releaseId: rel.id, tracks: rel.tracks.map((x, i) => i).filter((i) => rel.tracks[i].side === 'A'), when: 'at', at: Date.now() - 6.2 * 60e3 });
      }
      setInterval(tick, 1000);
    },

    handle(m) {
      switch (m.t) {
        case 'hello': later(20, () => { pushState(); pushColl(); pushAct(); }); break;
        case 'openRelease': {
          const id = m.id;
          if (!releases[id] || m.refresh) {
            onRelease({ id, loading: true });
            later(m.refresh ? 900 : 500, () => { onRelease(clone(release(id))); if (m.refresh) toast('Tracklist updated'); });
          } else onRelease(clone(release(id)));
          break;
        }
        case 'startSpin': startSpin(m); break;
        case 'spinStop':
          if (spin) { scrobbleDue(Date.now(), false); spin = null; pushState(); pushAct(); }
          break;
        case 'spinFlip': {
          if (!spin || !spin.nextSide) break;
          const now = Date.now();
          scrobbleDue(now, false);
          spin.items.forEach((x) => { if (x.state === 'planned') x.state = 'skipped'; });
          const rel = release(spin.releaseId);
          let t = now;
          for (const tr of rel.tracks.filter((x) => x.side === spin.nextSide)) {
            const dur = tr.dur > 0 ? tr.dur : S.prefs.defaultTrackSec;
            spin.items.push({ title: tr.title, artist: tr.artist, pos: tr.pos, side: tr.side, dur, est: !(tr.dur > 0), start: t, state: 'planned' });
            t += dur * 1000;
          }
          spin.nextSide = nextSide(rel, spin.items);
          pushState(); pushAct();
          break;
        }
        case 'spinResync': {
          if (!spin) break;
          const now = Date.now();
          let t = now, prev = null;
          for (let i = m.i; i < spin.items.length; i++) {
            const it = spin.items[i];
            if (it.state === 'skipped') continue;
            if (prev != null && it.side !== prev) t += FLIP_GAP_MS;
            it.start = t; t += it.dur * 1000; prev = it.side;
          }
          let b = now;
          for (let i = m.i - 1; i >= 0 && spin.items[i].state === 'planned'; i--) { b -= spin.items[i].dur * 1000; spin.items[i].start = b; }
          tick(); pushState();
          break;
        }
        case 'flush': toast('All scrobbles sent'); break;
        case 'setPhoneMode':
          if (m.mode === 'shadow' && S.phone.mode !== 'shadow') S.phone.since = Date.now();
          S.phone.mode = m.mode; S.phone.now = null; pushState();
          break;
        case 'setPhoneApp': {
          const a = S.phone.apps.find((x) => x.pkg === m.pkg);
          if (a) a.on = m.on;
          if (S.phone.now && S.phone.now.pkg === m.pkg) S.phone.now.on = m.on;
          pushState();
          break;
        }
        case 'openNotifAccess':
          toast('(preview) Android\'s notification access settings would open now');
          later(1500, () => { Object.assign(S.phone, { access: true, connected: true }); pushState(); });
          break;
        case 'openAppInfo': case 'openBattery': toast('(preview) Android settings would open now'); break;
        case 'stats':
          if (!S.history.count) { onStats({ empty: true }); break; }
          later(250, () => onStats(fakeStats(m.range || '')));
          break;
        case 'story': later(400, () => onStory(fakeStory(m.range || ''))); break;
        case 'artist': later(250, () => onArtist({ ...fakeArtist(m.name), req: m.req })); break;
        case 'search': later(120, () => onSearch(fakeSearch(m.q))); break;
        case 'phoneControl': {
          if (m.action === 'play' || m.action === 'pause') phonePlaying = m.action === 'play';
          if (m.action === 'next' || m.action === 'prev' || m.action === 'queue') {
            phoneIdx = m.action === 'queue' ? m.arg : (phoneIdx + (m.action === 'next' ? 1 : PHONE_TRACKS.length - 1)) % PHONE_TRACKS.length;
            phoneListened = 0;
            phoneScrobbled = false;
          }
          if (m.action === 'seek') phoneListened = m.arg;
          if (S.phone.now) S.phone.now.title = '';
          phoneTick();
          break;
        }
        case 'openPhoneApp': toast('(preview) TIDAL would open on its now playing screen'); break;
        case 'nowAlbum': later(300, () => onNowAlbum({ ...fakeNowAlbum(m.album), q: m.q })); break;
        case 'lyrics': {
          const words = ['', 'First line of the first verse', 'Second line, a little longer than the first', 'Third line comes back around', 'And the fourth one ends the verse',
            '', 'Here is where the chorus starts', 'Sung a little louder now', 'Same words as the second time', 'And the chorus ends', '',
            'Second verse, first line', 'Second verse, another line', 'Third line of the second verse', 'The last line of the verse', '', 'Chorus once more', 'And out'];
          const lrc = words.map((w, i) => `[${String(Math.floor((6 + i * 4) / 60)).padStart(2, '0')}:${String((6 + i * 4) % 60).padStart(2, '0')}.00] ${w}`).join('\n');
          later(500, () => onLyrics({ q: m.q, synced: lrc, plain: words.filter(Boolean).join('\n'), instrumental: false }));
          break;
        }
        case 'tilt': break;
        case 'palette': {
          // Made-up colors from the address, as if read from the picture.
          const h = hashStr(m.url) % 360;
          later(150, () => onPalette({ url: m.url, dominant: hslHex(h, 0.35, 0.3), vibrant: hslHex((h + 25) % 360, 0.7, 0.55) }));
          break;
        }
        case 'tidy': later(200, () => onTidy(S.history.count ? tidyOf() : { empty: true })); break;
        case 'discography': later(400, () => onDiscography(fakeDiscography(m.name))); break;
        case 'dzAlbum': later(250, () => onDzAlbum({ ...fakeDzAlbum(m.id), req: m.req })); break;
        case 'track': later(250, () => onTrack({ ...fakeTrack(m.artist, m.title), req: m.req })); break;
        case 'album': later(250, () => onAlbum({ ...fakeAlbum(m.artist, m.title), req: m.req })); break;
        case 'explore': later(300, () => onExplore(fakeExplore())); break;
        case 'historyCheck': {
          // As if 2024 had come up short, until it is fetched again.
          const years = [2023, 2024, 2025, 2026].map((y, k) => ({ year: y, lastfm: [9120, 31873, 28440, 16579][k], here: [9120, m.repair ? 31873 : 22410, 28440, 16577][k] }));
          later(400, () => onHistoryCheck({ busy: true, step: 'Counting your scrobbles on Last.fm…' }));
          later(1300, () => onHistoryCheck({ lastfm: 86012, here: years.reduce((n, y) => n + y.here, 0), added: m.repair ? 9463 : 0, checked: 500, years,
            artists: m.repair ? [] : [{ name: 'Fela Kuti', lastfm: 412, here: 2 }, { name: 'Tony Allen', lastfm: 96, here: 31 }] }));
          break;
        }
        case 'badges': {
          // A few of each, as the history would give them.
          const ids = coll.map((r) => r.id);
          const b = {};
          if (ids[0]) b[ids[0]] = { k: 'heavy', n: 34 };
          if (ids[1]) b[ids[1]] = { k: 'new', at: Date.now() - 4 * DAY };
          if (ids[2]) b[ids[2]] = { k: 'worn', n: 811, rank: 1 };
          if (ids[4]) b[ids[4]] = { k: 'lost', n: 214, at: Date.UTC(2025, 2, 14) };
          if (ids[6]) b[ids[6]] = { k: 'sealed', at: Date.UTC(2023, 8, 2) };
          if (ids[9]) b[ids[9]] = { k: 'worn', n: 455, rank: 3 };
          later(200, () => onBadges(b));
          break;
        }
        case 'genre': later(300, () => onGenre({ ...fakeGenre(m.key), req: m.req })); break;
        case 'atlas': later(500, () => onAtlas(fakeAtlas())); break;
        case 'backupExport': later(700, () => onBackup({ scrobbles: history().ts.length })); break;
        case 'backupImport': later(400, () => onToast('In the app, a file picker opens here')); break;
        case 'vs':
          if (!vsLooked) {
            // The first time: Last.fm is asked, a few artists a second.
            vsLooked = true;
            onVs({ plays: 1, knownPlays: 0, knownArtists: 0, lastfm: true });
            Object.assign(S.history, { globalsBusy: true, globalsDone: 0, globalsTotal: 240 });
            pushState();
            const step = () => {
              S.history.globalsDone += 60;
              if (S.history.globalsDone >= 240) { S.history.globalsBusy = false; pushState(); return; }
              pushState();
              later(600, step);
            };
            later(600, step);
          } else later(250, () => onVs(fakeVs()));
          break;
        case 'playlist': later(300, () => onPlaylist(fakePlaylist(m.kind))); break;
        case 'hide': case 'unhide': case 'keep':
          tidyApply(m.t, m.items || [m]);
          later(300, () => { onTidy(tidyOf()); if (S.history.count) onStats(fakeStats(statsRange)); });
          break;
        case 'historyImport': {
          const H = S.history;
          Object.assign(H, { importing: true, error: '', fetched: H.fetched || 0, total: 86012 });
          pushState();
          const step = () => {
            if (!H.importing) return;
            H.fetched = Math.min(H.total, H.fetched + 9600);
            if (H.fetched >= H.total) {
              Object.assign(H, { importing: false, done: true, resumable: false, count: H.total, lastSync: Date.now(), tagsTotal: 140, tagsDone: 0 });
              pushState();
              onStats(fakeStats(statsRange));
              const tags = setInterval(() => { H.tagsDone += 20; if (H.tagsDone >= H.tagsTotal) { H.tagsTotal = 0; clearInterval(tags); } pushState(); }, 400);
              return;
            }
            pushState();
            later(500, step);
          };
          later(500, step);
          break;
        }
        case 'historyCancel': Object.assign(S.history, { importing: false, resumable: true }); pushState(); break;
        case 'comparePano':
          S.phone.comparing = true; S.phone.compareError = ''; pushState();
          later(1200, () => { S.phone.comparing = false; pushState(); onCompare(fakeCompare()); });
          break;
        case 'sync':
          S.discogs.syncing = true; S.discogs.pages = 3; S.discogs.page = 0; S.discogs.done = 0; S.discogs.error = '';
          pushState();
          [1, 2, 3].forEach((p) => later(p * 700, () => {
            S.discogs.page = p; S.discogs.done = Math.min(RECORDS.length, p * 10);
            if (p === 3) { makeCollection(); S.discogs.syncing = false; S.discogs.lastSync = Date.now(); pushColl(); }
            pushState();
          }));
          break;
        case 'saveDiscogs':
          S.discogs.syncing = true; pushState();
          later(800, () => { const user = m.user || 'vinylfan'; Object.assign(S.discogs, { connected: true, user, public: !m.token }); toast(`Connected to Discogs as ${user}`); Mock.handle({ t: 'sync' }); });
          break;
        case 'discogsDisconnect':
          Object.assign(S.discogs, { connected: false, user: '', lastSync: 0, count: 0 }); coll = []; pushState(); pushColl();
          break;
        case 'saveTidal':
          Object.assign(S.tidal, { busy: true, error: '' }); pushState();
          later(500, () => { Object.assign(S.tidal, { busy: false, hasApp: true, clientHint: '…' + m.clientId.slice(-4) }); pushState(); toast('TIDAL app connected'); });
          break;
        case 'tidalLogin': later(700, () => { S.tidal.loggedIn = true; pushState(); toast('Signed in to TIDAL'); }); break;
        case 'tidalSignOut': S.tidal.loggedIn = false; pushState(); break;
        case 'tidalForget': Object.assign(S.tidal, { hasApp: false, loggedIn: false, clientHint: '' }); pushState(); break;
        case 'tidalOpen': toast(`Would open the exact ${m.kind.replace(/s$/, '')} in TIDAL: ${m.artist}${m.title ? ' – ' + m.title : ''}`); break;
        case 'tidalPlaylist': {
          const n = m.tracks.length;
          let done = 0;
          const tick = () => {
            done = Math.min(n, done + 3);
            const found = done - (done > 10 ? 1 : 0) - (done > 25 ? 1 : 0);
            if (done < n) { onTidalJob({ state: 'finding', done, total: n, found, name: m.name }); later(120, tick); return; }
            const missing = m.tracks.filter((t, i) => i === 10 || i === 25).map((t) => ({ artist: t.artist, title: t.title }));
            onTidalJob({ state: 'done', total: n, added: n - missing.length, missing, name: m.name, link: 'https://tidal.com/browse/playlist/demo' });
          };
          later(200, tick);
          break;
        }
        case 'saveLastfm':
          Object.assign(S.lastfm, { hasKey: true, keyHint: '…' + m.key.slice(-4), connected: false, busy: true, error: '' }); pushState();
          later(700, () => { Object.assign(S.lastfm, { busy: false, waiting: true }); pushState(); toast('(preview) The Last.fm approval page would open now'); });
          break;
        case 'lastfmConnect':
          Object.assign(S.lastfm, { waiting: true, needsAuth: false }); pushState(); toast('(preview) The Last.fm approval page would open now');
          break;
        case 'lastfmCheck':
          Object.assign(S.lastfm, { waiting: false, connected: true, user: 'vinylfan', error: '' }); pushState(); toast('Connected to Last.fm as vinylfan');
          break;
        case 'lastfmDisconnect': Object.assign(S.lastfm, { connected: false, waiting: false, user: '', needsAuth: false }); pushState(); break;
        case 'lastfmForget': Object.assign(S.lastfm, { hasKey: false, connected: false, waiting: false, user: '', keyHint: '' }); pushState(); break;
        case 'setDefaultTrack': S.prefs.defaultTrackSec = m.sec; pushState(); if (isOpen('recordPage')) renderRecord(); break;
        case 'setTheme': {
          S.prefs.theme = m.v;
          const root = document.documentElement;
          if (m.v === 'system') delete root.dataset.theme; else root.dataset.theme = m.v;
          pushState();
          break;
        }
        case 'openUrl': window.open(m.url, '_blank', 'noopener'); break;
        default: toast('(preview) ' + m.t);
      }
    },
  };
})();
