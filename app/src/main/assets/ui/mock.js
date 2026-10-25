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
    lastfm: { hasKey: true, keyHint: '…9f2c', connected: true, user: 'vinylfan', waiting: false, busy: false, error: '', needsAuth: false },
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
    version: '0.3 (browser preview)',
  };

  // A fake TIDAL session: plays these in a loop (lengths shortened so the preview moves).
  const PHONE_TRACKS = [
    ['Khruangbin', 'Maria También', 'Con Todo El Mundo', 190],
    ['Khruangbin', 'Como Me Quieres', 'Con Todo El Mundo', 160],
    ['Beach House', 'Myth', 'Bloom', 258],
  ];
  let phoneIdx = 0, phoneListened = 0, phoneScrobbled = false;
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
    phoneListened += 1000;
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
    P.now = { pkg: 'com.aspiro.tidal', app: 'TIDAL', artist, title, album, dur: dur * 1000, listened: phoneListened, at: Date.now(), playing: true, threshold, scrobbled: phoneScrobbled, eligible: true, on: true };
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

  // ---- stats (made-up numbers with a realistic shape) ----

  const MOCK_ARTISTS = ['Khruangbin', 'Tame Impala', 'Beach House', 'Radiohead', 'Fleetwood Mac', 'Bonobo', 'Air', 'Portishead', 'Frank Ocean', 'Kendrick Lamar', 'Mac DeMarco', 'Bon Iver'];
  const GENRES = ['Indie Rock', 'Psychedelic Rock', 'Electronic', 'Soul', 'Dream Pop', 'Jazz', 'Other'];
  function mockRanges() {
    const y = new Date().getFullYear();
    const out = [{ id: '12m', label: 'Last 12 months' }, { id: 'all', label: 'All time' }];
    for (let k = y; k >= 2019; k--) out.push({ id: 'y' + k, label: String(k) });
    return out;
  }
  function rangeInfo(id) {
    const now = new Date();
    if (id === 'all') return { id, label: 'All time', from: new Date(2019, 2, 14), to: now, scale: 7.3 };
    if (id === '12m') return { id, label: 'Last 12 months', from: new Date(now.getFullYear() - 1, now.getMonth(), now.getDate() + 1), to: now, scale: 1 };
    const y = +id.slice(1);
    const to = y === now.getFullYear() ? now : new Date(y + 1, 0, 1);
    return { id, label: String(y), from: new Date(Math.max(new Date(y, 0, 1), new Date(2019, 2, 14))), to, scale: (to - new Date(y, 0, 1)) / 365 / DAY };
  }
  function monthsBetween(a, b) { return (b.getFullYear() - a.getFullYear()) * 12 + b.getMonth() - a.getMonth() + 1; }
  function fakeClock(r, total) {
    const w = [];
    for (let d = 0; d < 7; d++) for (let h = 0; h < 24; h++) {
      const weekend = d >= 5;
      let v = h < 7 ? (weekend && h < 2 ? 0.6 : 0.05) : h < 10 ? (weekend ? 0.3 : 1.1) : h < 17 ? (weekend ? 0.9 : 0.55) : h < 23 ? (d === 4 ? 1.9 : 1.4) : 0.7;
      w.push(v * (0.75 + 0.5 * r()));
    }
    const sum = w.reduce((a, b) => a + b, 0);
    return w.map((x) => Math.round((x / sum) * total));
  }
  function fakeStats(id) {
    const ri = rangeInfo(id);
    const r = rng(hashStr(id) + 5);
    const scrobbles = Math.round(11800 * ri.scale);
    const months = monthsBetween(ri.from, ri.to);
    const yearly = months > 36;
    const naBuckets = [];
    if (yearly) for (let y = ri.from.getFullYear(); y <= ri.to.getFullYear(); y++) naBuckets.push({ start: +new Date(y, 0, 1), count: y === 2019 ? 412 : 120 + Math.round(r() * 90), historyStart: y === 2019 });
    else for (let k = 0; k < months; k++) {
      const d = new Date(ri.from.getFullYear(), ri.from.getMonth() + k, 1);
      const start = d.getFullYear() === 2019 && d.getMonth() === 2;
      naBuckets.push({ start: +d, count: start ? 212 : 4 + Math.round(r() * 22), historyStart: start });
    }
    naBuckets.forEach((b) => { b.top = MOCK_ARTISTS.slice(Math.floor(r() * 8)).slice(0, 3); });
    const gUnit = months <= 15 ? 'month' : months <= 60 ? 'quarter' : 'year';
    const per = gUnit === 'month' ? 1 : gUnit === 'quarter' ? 3 : 12;
    const periods = [];
    const n = Math.ceil(months / per);
    for (let k = 0; k < n; k++) {
      const t = k / Math.max(1, n - 1);
      const raw = [0.30 - 0.12 * t, 0.12 + 0.14 * t, 0.16 + 0.04 * r(), 0.08 + 0.06 * t, 0.10 - 0.03 * t, 0.05 + 0.03 * r(), 0.15];
      const sum = raw.reduce((a, b) => a + b, 0);
      const d = new Date(ri.from.getFullYear(), Math.floor(ri.from.getMonth() / per) * per + k * per, 1);
      periods.push({ start: +d, total: Math.round(scrobbles / n), shares: raw.map((x) => Math.round((x / sum) * 1000) / 1000) });
    }
    const owned = coll.slice().sort((a, b) => a.plays - b.plays);
    const top = {
      artists: MOCK_ARTISTS.slice(0, 10).map((name, i) => ({ name, plays: Math.round((620 - i * 48) * ri.scale * (0.9 + 0.2 * r())) })),
      albums: RECORDS.slice(0, 10).map((x, i) => ({ title: x[1], artist: x[0], plays: Math.round((300 - i * 24) * ri.scale), cover: '', owned: i % 3 !== 2 })),
      tracks: PHONE_TRACKS.concat(PHONE_TRACKS).slice(0, 6).map((x, i) => ({ title: i < 3 ? x[1] : `${x[1]} (Live)`, artist: x[0], plays: Math.round((88 - i * 9) * ri.scale) })),
    };
    top.artists.sort((a, b) => b.plays - a.plays);
    const record = fakeRecord(ri, rng(hashStr(id) + 11));
    return {
      record, pulse: fakePulse(record),
      loops: [{ track: 'Maria También', artist: 'Khruangbin', run: 7, at: Date.now() - 45 * DAY }, { track: 'Myth', artist: 'Beach House', run: 4, at: Date.now() - 12 * DAY }],
      range: { id, label: ri.label, from: +ri.from / 1000, to: +ri.to / 1000 },
      ranges: mockRanges(),
      summary: { scrobbles, artists: Math.round(1450 * Math.sqrt(ri.scale)), tracks: Math.round(6900 * Math.sqrt(ri.scale)), albums: Math.round(2300 * Math.sqrt(ri.scale)), days: Math.round(331 * ri.scale), spanDays: Math.round(365 * ri.scale), perDay: Math.round((scrobbles / (365 * ri.scale)) * 10) / 10 },
      clock: fakeClock(r, scrobbles),
      streaks: { current: 12, currentFrom: Date.now() - 11 * DAY, longest: 41, longestFrom: Date.now() - 140 * DAY, longestTo: Date.now() - 100 * DAY,
        artists: [{ artist: 'Khruangbin', days: 9, from: Date.now() - 60 * DAY, to: Date.now() - 52 * DAY }, { artist: 'Tame Impala', days: 7, from: Date.now() - 200 * DAY, to: Date.now() - 194 * DAY }, { artist: 'Beach House', days: 6, from: Date.now() - 30 * DAY, to: Date.now() - 25 * DAY }] },
      newArtists: { unit: yearly ? 'year' : 'month', buckets: naBuckets },
      genres: { names: GENRES, keys: GENRES.map((g) => g.toLowerCase()), unit: gUnit, periods, coverage: { discogs: 0.38, lastfm: 0.55, none: 0.07 } },
      obsessions: [
        { track: 'Maria También', artist: 'Khruangbin', week: Date.now() - 45 * DAY, count: 23 },
        { track: 'Let It Happen', artist: 'Tame Impala', week: Date.now() - 180 * DAY, count: 17 },
        { track: 'Myth', artist: 'Beach House', week: Date.now() - 20 * DAY, count: 11 },
      ],
      rarely: { items: owned.slice(0, 8).map((c) => ({ id: c.id, title: c.title, artist: c.artist, cover: '', plays: Math.round(c.plays * ri.scale / 8), last: c.plays ? Date.now() - (60 + c.plays) * DAY : 0 })), owned: coll.length, unplayed: 4, neverPlayed: 2 },
      wishlist: [
        ['Tame Impala', 'Lonerism', 212, 12], ['Khruangbin', 'Mordechai', 164, 10], ['Frank Ocean', 'Channel Orange', 131, 14],
        ['Bonobo', 'Migration', 96, 9], ['Beach House', 'Depression Cherry', 74, 8],
      ].map(([artist, album, plays, tracks]) => ({ artist, album, plays: Math.round(plays * Math.min(ri.scale, 1.5)), tracks, last: Date.now() - 9 * DAY, key: '', image: '', search: 'https://www.discogs.com/search/?type=master&q=' + encodeURIComponent(artist + ' ' + album) })),
      top,
    };
  }
  /** A plausible year of plays: evenings and commutes, gaps, a few favourite artists, phases. */
  function fakeRecord(ri, r) {
    const days = Math.max(1, Math.round((ri.to - ri.from) / DAY));
    const artists = MOCK_ARTISTS.concat(Array.from({ length: 60 }, (_, i) => `Artist ${i + 1}`));
    const hourW = [0.2, 0.1, 0.05, 0.02, 0.02, 0.03, 0.3, 1, 1.2, 0.8, 0.6, 0.6, 0.7, 0.6, 0.6, 0.7, 0.9, 1.2, 1.4, 1.6, 1.7, 1.5, 1.1, 0.6];
    const hsum = hourW.reduce((a, b) => a + b, 0);
    const pickHour = () => { let x = r() * hsum; for (let h = 0; h < 24; h++) { x -= hourW[h]; if (x <= 0) return h; } return 20; };
    const d = [], m = [], a = [], t = [], tracks = [];
    const counts = new Array(artists.length).fill(0);
    const trackIdx = {};
    for (let k = 0; k < days; k++) {
      if (r() < 0.08) continue; // a day without music
      const n = Math.round(r() * r() * 70);
      const phase = Math.floor((k / days) * 4); // each quarter favours other artists
      for (let j = 0; j < n; j++) {
        const fav = r() < 0.55;
        const ai = fav ? (phase * 2 + Math.floor(r() * 4)) % MOCK_ARTISTS.length : Math.floor(Math.pow(r(), 2) * artists.length);
        const tk = `${ai}:${Math.floor(r() * 6)}`;
        if (trackIdx[tk] == null) { trackIdx[tk] = tracks.length; tracks.push(`${W1[Math.floor(r() * W1.length)]} ${W2[Math.floor(r() * W2.length)]}`); }
        d.push(k); m.push(pickHour() * 60 + Math.floor(r() * 60)); a.push(ai); t.push(trackIdx[tk]);
        counts[ai]++;
      }
    }
    // Order artists by plays, like the app does.
    const order = counts.map((c, i) => i).sort((x, y) => counts[y] - counts[x]).filter((i) => counts[i] > 0);
    const local = {};
    order.forEach((ai, k) => { local[ai] = k; });
    return { start: +ri.from, days, d, m, a: a.map((x) => local[x]), t, tracks, artists: order.map((i) => artists[i]), plays: order.map((i) => counts[i]) };
  }
  function fakePulse(rec) {
    const bins = Math.max(2, Math.ceil(rec.days / 7));
    const rows = rec.artists.slice(0, 12).map((artist, k) => {
      const counts = new Array(bins).fill(0);
      rec.a.forEach((x, i) => { if (x === k) counts[Math.min(bins - 1, Math.floor(rec.d[i] / 7))]++; });
      let peak = 0;
      counts.forEach((c, i) => { if (c > counts[peak]) peak = i; });
      return { artist, plays: rec.plays[k], peak, counts };
    }).sort((x, y) => x.peak - y.peak);
    return { start: rec.start, binDays: 7, bins, rows };
  }
  function fakeArtist(name) {
    const r = rng(hashStr(name) + 3);
    const months = Array.from({ length: 34 }, (_, i) => Math.max(0, Math.round(30 * Math.sin(i / 5 + r() * 2) + 20 * r() - 5)));
    const hours = Array.from({ length: 24 }, (_, h) => Math.round((h > 6 ? 10 : 2) * (1 + Math.sin((h - 14) / 3)) + r() * 6));
    const owned = coll.filter((c) => c.artist === name).map((c) => ({ id: c.id, title: c.title, artist: c.artist, cover: '', year: c.year }));
    return {
      name, plays: months.reduce((x, y) => x + y, 0), days: 211,
      first: { ts: Date.now() - 1040 * DAY, track: 'Maria También' }, last: { ts: Date.now() - 2 * DAY, track: 'Pelota' },
      busiestMonth: { start: +new Date(2025, 2, 1), count: Math.max(...months) },
      monthsFrom: +new Date(2023, 11, 1), months, hours, streak: 9, streakEnd: Date.now() - 52 * DAY,
      range: { id: '12m', label: 'Last 12 months', plays: 412, rank: 2 },
      tracks: ['Maria También', 'Pelota', 'Como Me Quieres', 'Evan Finds the Third Room', 'Time (You and I)'].map((title, i) => ({ title, plays: 90 - i * 14 })),
      albums: [{ title: 'Con Todo El Mundo', plays: 240, cover: '', owned: owned.length > 0 }, { title: 'Mordechai', plays: 160, cover: '', owned: false }],
      owned, genres: ['Psychedelic Rock', 'Funk', 'Soul'],
    };
  }

  function fakeReview(id) {
    const s = fakeStats(id);
    const months = monthsBetween(new Date(s.range.from * 1000), new Date(s.range.to * 1000 - 1));
    const r = rng(hashStr(id) + 9);
    const m = Array.from({ length: Math.min(13, months) }, () => Math.round((s.summary.scrobbles / months) * (0.7 + 0.6 * r())));
    return {
      range: s.range, ranges: mockRanges().filter((x) => x.id !== 'all'), summary: s.summary,
      top: { artists: s.top.artists.slice(0, 5), albums: s.top.albums.slice(0, 5), tracks: s.top.tracks.slice(0, 5) },
      newArtists: 164, historyStartsHere: id === 'y2019',
      topNew: ['Khruangbin', 'Mac DeMarco', 'Bonobo', 'Air', 'Portishead'].map((name, i) => ({ name, plays: 140 - i * 22, first: Date.now() - (300 - i * 40) * DAY })),
      months: m, monthsFrom: +new Date(new Date(s.range.from * 1000).getFullYear(), new Date(s.range.from * 1000).getMonth(), 1),
      busiestDay: { day: Date.now() - 94 * DAY, count: 187 },
      streak: s.streaks, obsession: s.obsessions[0], clock: s.clock,
      vinyl: { count: 214, share: 0.018, top: [{ title: 'Rumours', artist: 'Fleetwood Mac', plays: 44, cover: '' }, { title: 'Kind of Blue', artist: 'Miles Davis', plays: 20, cover: '' }] },
      first: { track: 'Second Hand News', artist: 'Fleetwood Mac', ts: s.range.from * 1000 + 9 * 3600e3 },
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
          later(250, () => onStats(fakeStats(m.range || '12m')));
          break;
        case 'review': later(300, () => onReview(fakeReview(m.range || '12m'))); break;
        case 'artist': later(250, () => onArtist(fakeArtist(m.name))); break;
        case 'tilt': break;
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
              onStats(fakeStats('12m'));
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
          later(800, () => { Object.assign(S.discogs, { connected: true, user: 'vinylfan' }); toast('Connected to Discogs as vinylfan'); Mock.handle({ t: 'sync' }); });
          break;
        case 'discogsDisconnect':
          Object.assign(S.discogs, { connected: false, user: '', lastSync: 0, count: 0 }); coll = []; pushState(); pushColl();
          break;
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
