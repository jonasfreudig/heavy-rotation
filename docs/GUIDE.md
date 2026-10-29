# Heavy Rotation: the guide

Everything the app does, and how to set it up. The short version is in the [README](../README.md).

## Set up (a few minutes)

### 1. Discogs: your shelf

Gear → **Discogs** → type your **Discogs username** → **Connect**. That's all if your collection is
public on Discogs (your Discogs privacy settings). The collection syncs right away; tap the shelf's
sync button whenever you add records.

Private collection? Under **Connect with a token**: on discogs.com open **Settings → Developers**
(<https://www.discogs.com/settings/developers>), tap **Generate new token**, paste it, connect. The
app only reads your collection and release data with it; revoke it there any time.

### 2. Last.fm: where scrobbles go

Gear → **Last.fm** → **Connect Last.fm**. Your browser opens Last.fm's "connect application" page:
tap **Yes, allow access**, then switch back to Heavy Rotation. It finishes on its own; if not, tap
**I've allowed it**.

If the app asks for an API key instead (a build without the app's own key, or you chose **Use your
own API key**): signed in to Last.fm, open <https://www.last.fm/api/account/create>, fill in an email,
a name such as "Heavy Rotation" and any description, leave **Callback URL** empty, and paste the **API
key** and **Shared secret** it shows.

### 3. Moving from another phone or version

Gear → **Backup** → **Export backup** saves one file with your history, hide list, activity,
collection, tracklists, caches and settings. In the other app: **Restore…** and pick the file; the
app starts again with it. Keys, tokens and logins aren't in the file (connect again after
restoring), and neither are scrobbles still waiting to be sent.

### 4. Notifications

Allow notifications when the app asks (on your first scrobble). The "Now spinning" notification
shows the current track with a countdown and has **Stop** and **Flip to side B** buttons.

### 5. Phone scrobbling (replacing Pano Scrobbler)

1. Gear → **Phone scrobbling** → **Shadow**.
2. Tap **Allow notification access** and switch Heavy Rotation on. Android calls it notification
   access because that is the only way an app may see other apps' media players; Heavy Rotation
   doesn't read your notifications.
   - If the switch is greyed out with **"Restricted setting"** (Android does that for apps
     installed from an APK file): open **App info** (button in the app), tap **⋮** at the top right,
     choose **Allow restricted settings**, then try again.
3. TIDAL is switched on by default. Other apps appear in the list once they play something.
4. Keep Pano running and listen as usual for a day or two. Heavy Rotation only *logs* what it would
   scrobble (marked "Shadow" on the Activity tab) and sends nothing.
5. On the Activity tab, tap **Compare with Pano**. It reads what reached Last.fm since shadow mode
   started and shows how much Heavy Rotation caught, what only Pano scrobbled, what only Heavy Rotation
   would have, and tracks the two named differently.
6. When you're happy, switch **Phone scrobbling** to **On** and turn off Pano's scrobbling (or
   uninstall it). Otherwise every track is scrobbled twice; the app warns while Pano is installed.

How it decides, following Last.fm's rules: a track counts once it has played for half its length
or 4 minutes, whichever comes first, and only if it is longer than 30 seconds. Only time actually
playing counts (pauses, buffering and seeking forward don't). Each scrobble carries the time the
track started. Playing a track again from the start, including on repeat, is a new scrobble. Tracks
whose length the app doesn't report need 4 minutes. In On mode "now playing" goes to Last.fm too,
and scrobbles use the same offline queue as records.

With no record spinning, the card at the top of the Shelf shows what a music app on the phone is
playing (TIDAL and the like): the song, the app, its cover (from your shelf or Deezer) and how
far it is; tap it for the song's page.

**Fix names** (Settings → Scrobbling) changes names before they're scrobbled, like Pano Scrobbler's
edits, for the phone and for records alike: ready-made fixes to switch on ("Remastered",
"Explicit", "feat. …", "- Single", YouTube's "- Topic") and your own rules. A rule looks at the
artist, the song or the album: when it **is** a text the whole name becomes another, when it
**contains** one (or a regular expression **matches** part of it) that part is replaced or taken
out, optionally only for one artist. The rule sheet tries it on your recent scrobbles. Scrobbles
already sent stay as they are; Last.fm can only be edited on its website.

**The widget** (long-press the home screen → Widgets → Heavy Rotation): what plays now, on the
turntable or the phone, else the last song you played, with its cover and today's and this
week's plays, in your wallpaper's colors. Tap it for the app (the record or Now playing page
while something plays).

### 6. TIDAL (optional: exact links and playlists)

Without TIDAL, the TIDAL buttons open TIDAL's search. Connected, they open the exact song, album or
artist, and playlists from the Explore tab go straight into your TIDAL.

Gear → **TIDAL** → **Sign in to TIDAL**. TIDAL's login page opens; approve, and the browser hands
you back to Heavy Rotation ("Signed in to TIDAL").

If the app asks for a Client ID and secret instead (a build without the app's own TIDAL app, or you
chose **Use your own TIDAL app**): open <https://developer.tidal.com/dashboard>, log in, accept the
developer guidelines, create an app (any name), add the **redirect URI** `recordshelf://tidal`
(Settings → TIDAL has a Copy button) and the scope **playlists.write**, then copy its **Client ID**
and **Client Secret** into the app and sign in.

Then a playlist page has **Create in TIDAL**: Heavy Rotation finds each song in TIDAL's catalog,
creates an unlisted playlist in your library and adds them; songs TIDAL doesn't have are listed.
Found songs are remembered on the phone, so the next time is quicker.

Your TIDAL login (and your own client secret, if you entered one) stays in the app's private
storage, left out of backups like the Last.fm keys. **Disconnect** forgets them; playlists already made stay in TIDAL.

If the dashboard doesn't accept `recordshelf://tidal` as a redirect URI, the sign-in can't come
back to the app; exact links still work without signing in, and playlists still go through
**Copy list** and TuneMyMusic.

### 7. Stats

Open the **Stats** tab and tap **Import history**. The app copies all your scrobbles from Last.fm to
the phone (200 per request). A long history takes a few minutes the first time, roughly one minute
per 20,000 scrobbles. You can use other apps meanwhile; a notification shows the progress. If the
import is interrupted it continues where it stopped the next time you open the app. Afterwards
only new scrobbles are fetched, at most every 30 minutes when you open the app.

The period is the page's title: step back and forth with **‹ ›**, and pick **Day**, **Week**,
**Month**, **Year** or **All** under it. A period that is still running is compared with the same
stretch of the one before ("vs this time last week").

**Day** is made for a handful of songs rather than thousands: the time you listened (and how
that compares with your usual for that weekday by the same time of day, over the last 8 weeks),
your day on a 24-hour strip with its listening sessions (a new one after half an hour without
music, with the album or the turntable when a session was one record), the first and latest song,
what you had on repeat, artists and songs new that day, the same date in other years (tap one to
see that day), your streak and how much came off vinyl.

A **week** shows its seven days, the week before in outline, and each day on a 24-hour strip. Charts
of change over the period (the river, phases, genre drift) appear once it has four days with music.

**Arrange this page** at the bottom reorders the blocks (drag the handles) and hides the ones you
don't want; the Day tab has its own order. The layout goes into backups with the other settings.
The charts adapt: a week or a month is shown per day, a year per week or month, all time per
month, quarter or year. What the tab shows for the chosen period:

- **Your story** (the card at the top): a Wrapped-style story you tap through, like Instagram
  stories (right side forward, left side back, hold to pause). It takes its colors from the
  period's top album, drifting behind each slide. Total plays with a ticker running across, your
  listening personality (night owl, early bird, loyalist, explorer, on repeat, weekender, crate
  digger, genre hopper or all-rounder) in a turning ring of words, your top artist's photo
  full-screen in two colors, the top 5 with photos, song of the period as a 7" single spinning at
  45, the album as a moving cover, your golden hour on a sundial, one star per new artist, a song
  on repeat, your genres as a cloud of words, vinyl, your longest streak as a row of days, and a
  summary printed like the back of a record sleeve. With **Photos in your stories** switched on
  (Settings → Stats and listening), it also shows photos you took in those days: one full-screen
  with the song that was playing as you took it, then a few more as prints, spread over the
  period. By default only photos taken while a song was playing count; **Which photos** can also
  take ones from up to 4 hours around music (each with the closest song, and how long before or
  after it started; the closer, the likelier a photo is picked), or any photo. **From these folders** lists the
  phone's photo folders: the camera's are on (no screenshots, nothing saved from other apps), tap
  others to add them. **Change photos** on a story's photo leaves photos out (another one takes
  their place) and changes which photos count; Settings brings hidden ones back. They never leave
  the phone. Google Photos can't be used for this: since 2025 Google lets apps see only photos the
  user picks by hand, not a stretch of time, so only photos still on the phone show up.
- **Totals**: plays, hours (about 3.5 minutes a track), artists and new artists, each with the
  change against the period before. For a period still running (this week, this month) it
  compares with the same stretch of the previous one: Monday to Wednesday against last Monday to
  Wednesday.
- **Highlights**: the period in sentences: more or less than last time, whether your top artist
  held on, an artist you came back to after months, discoveries, your busiest day and hours,
  night listening, song of the period, streaks, your biggest day, how much was vinyl.
- **Your record**: every play as a dot on a vinyl disc. Each groove is a day, from the label (the
  first day of the period) to the rim (the last); noon is at the top, midnight at the bottom. Your
  eight most played artists have their own colors, with a legend below. Tap a name in the legend,
  type in **Find an artist on the record**, or tap near a dot to light up one artist; tap the
  label to show everyone again.
- **Listening over time**: plays per day, week or month, with the average marked. Tap a column.
- **The river**: the same over time, split into your top eight artists (same colors as the
  record) stacked on everyone else. Tap it for a day's numbers.
- **When you listen**: plays per weekday and hour. Tap a cell for its numbers.
- **Streaks**: your current run of days with music (counted back from today, also before the
  period started), the longest in the period, and artists you played many days in a row.
- **Phases**: plays of your most played artists as ridges, ordered by when they peaked.
- **New artists**: artists heard for the first time, per day, week or month. The stretch where
  your Last.fm history starts is grayed out, since everything is new there.
- **Genre drift**: how your genres shift over time. Every play gets one genre, weighed from the
  Discogs styles of the record (if you own it), your other records by the artist, the artist's
  Last.fm tags and, for your most played albums, the album's own Last.fm tags (an artist's
  electronic album among their rock ones), with non-genres like "seen live" removed. The six
  biggest genres keep their colors whatever range you pick.
- **Obsessions**: a track played at least 7 times in one week, and songs played back to back
  several times in a row.
- **On your shelf, rarely played**: your records, least played first.
- **Wishlist ideas**: albums you play a lot (several different tracks, not just one hit) that
  aren't on your shelf; tap one to search Discogs.
- **Top 10** artists, albums and tracks.

#### Songs, albums and artists

Tap a scrobble on the Activity tab, or a song in the stats lists, for the song's page: all-time
plays and rank, this period's rank, first and last play, its biggest day, how much of the artist
it makes up, plays per month, time of day, and what you tend to play right after it. Albums (Top
10, an artist's albums, a record's **Your stats** chip) get the same, plus which of their songs you
play.

Song, album, artist and record pages have **Listen on** buttons that search TIDAL and Spotify
for them. Pick the services under Settings → Listen on (YouTube Music, Apple Music and Deezer too).
Spotify opens in its app; TIDAL opens its search page, which lists the match first.

#### Search

The round button next to the tabs searches everything: your records (by title, artist, label, year
or style), and every artist, album and song in your listening history, most played first. On the
shelf your records come first; on the other tabs what you played does. Every word you type has to
match somewhere, so "fire strokes" finds Room on Fire.

Song, album and artist pages take their colors from the cover or the artist's photo: a glow
behind the header, and charts in that color. Each has a **listening barcode** (one line per week
since you first played it, stronger for more plays), **plays per month** as a smooth curve with
its peak marked, and **around the clock**: the 24 hours as a ring around the cover, longer wedges
for the hours you play it most. Tap any of them for the numbers.

These pages are about all your time with that artist, album or song, whatever period the Stats
tab is on. Opened from a period other than All, they add that period: a tile with its plays and
rank, and on artist and album pages the songs you played most then.

Tap a rank tile ("#3 of 735 songs") for the whole list it comes from: every song, album or artist
of all time or of that period by plays, with this one marked and scrolled to (tabs switch between
songs, albums and artists). Equal plays share a rank. **The whole list** under Top 10 on the Stats
tab opens the same list for the Stats period.

Album and song pages show their genres too: the album's own when Last.fm knows them (looked up
when you open it, if it isn't among your most played), else the artist's.

Artist pages show the whole **discography** from Deezer's catalog: albums, EPs, singles and
compilations, with editions folded together ("Romance" and its deluxe edition are one record).
Each shows your plays, a foil star if it's on your shelf, "New" for the last two months, and dims
what you haven't heard; filter by Played, Not heard yet or On your shelf. Tap one for its album
page: for any album, played or not, it shows the tracklist with your plays per song, the year and
the label. Discographies are kept on the phone for a week, so new releases show up.

**On stage lately** (artist pages, with setlist.fm set up): the songs of the artist's usual set at
their recent shows, in their usual order (songs played at about a third of the shows or more,
encore songs last), with your plays of each and "new" for the ones you haven't heard, plus their
next show if one is listed and their recent shows. **Make a concert playlist** turns the usual set
into a playlist; tap a show for a playlist of that night's setlist. Both go to TIDAL or the copy
list like every playlist. Setlists are written down by fans on setlist.fm, so recent shows can
still be missing.

To set it up: log in to [setlist.fm](https://www.setlist.fm) (a free account), open its
[API settings](https://www.setlist.fm/settings/api) and apply for a key (free for personal,
non-commercial use; any application name will do), then paste it in Settings → setlist.fm.

**Your concerts**: add your setlist.fm username there too, and the shows you marked "I was there"
on setlist.fm come in (again once a week, or with **Update now**). They show on the artist's
page (**You saw them live**), in Explore (**Your concerts**: every show by year, the artists you
saw most, and the **afterglow**, the shows that sent you back to an artist's music most: your
plays of them in the four weeks after against the four weeks before), in the Stats highlights of
the period and as ticket stubs in its story. Tap a show for that night's setlist as a playlist.

Artist photos come from Deezer's public catalog (no account needed), looked up once per artist
and kept on the phone; they show on artist pages, in Top 10 and Explore, and in the story.

Phone scrobbles carry no cover, so the Activity tab takes one from your shelf (if you own the
album) or from Deezer, looked up once and kept on the phone.

#### Genres

Genre names are buttons: the styles on a record page, the genres on an artist page, and the
legend of the genre charts on the Stats tab. Each opens that genre's page: how much of your
listening it is and where it ranks, plays and artists, since when and its biggest day; your top
artists (with photos), albums and songs in it; **Rediscover**, the artists and albums of that genre
you loved and haven't played for half a year or more; **Everyone in …**, every artist you played
in it; when its music came out; its listening barcode, plays per month and hours; and the records
on your shelf with that genre or style. **Full stats for …** opens the Stats tab for that genre.

#### Your musical atlas

The **Musical atlas** tile at the top of the Explore tab opens three views of your whole history, worked
out from the order of your scrobbles:

- **Universe**: your 150 most played artists as stars, sized by plays. Two stars are linked when
  you often play them back to back (in the same listening session, relative to how much you play
  both), or, for artists you rarely play next to anything, when they share the same months. Linked
  stars are pulled close, so groups form; those groups are the **constellations** (colored, named
  after their biggest star, with their main genre). **Bridges** are artists strongly linked into
  two constellations. Drag to move, pinch or use + and − to zoom, and tap near a star: the nearest
  one within a finger's width is picked, the map flies to it and a card shows its links as buttons,
  so you hop from star to star without aiming. The card also says how you found it and what you
  usually play before and after it. Search for an artist, pick a constellation, or pick a year to
  light up who was around then.
- **Family tree**: for every artist you kept playing (5 plays or more), what you played right
  before your very first play of them, in the same session. Artists that led you to the most
  others are the **gateways**; unfold them into their tree. "Right after" means the very next song:
  with radio or autoplay, that was the app's pick. Artists from the first two weeks of your
  history have no known origin. Artist pages show the same as **How you found them**: the chain
  back as far as it goes, and who they led you to.
- **Landscape**: your 14 most played artists as mountains of plays per month, the earliest peaks
  at the back. Each is read as an **obsession** (most plays within a few months), a **companion**
  (a year or more), a **comeback** (six quiet months or more, then back) or a **phase**. Tap a
  mountain or a row for its peak.

Play counts are song plays: an album's "124 plays" means 124 scrobbles of its songs. Record and
album pages turn that into "≈ 12 times through" when the number of songs is known.

#### You vs everyone

Last.fm knows how many people listened to every artist, album and song, and how often, all time.
The app asks for those counts (with your API key, a few lookups a second, about two minutes the
first time) for your 200 most played artists and 150 most played songs, plus everyone's most
played songs by your 25 top artists, keeps them on the phone and looks them up again once a month.
Other artists, songs and albums are looked up when you open their page.

The **You vs everyone** tile next to it opens the comparison:

- **From household names to the underground**: your plays by how many listeners the artist has
  (1M+, 250K–1M, 50K–250K, 10K–50K, under 10K), and the line "half of your plays go to artists
  with fewer than … listeners".
- **A bigger fan than most**: artists you play many times more than their average listener
  (all their plays divided by all their listeners; a few heavy listeners pull that up, so most
  people played less). The bar is you, the tick the average listener.
- **Your share**: "1 in 230" of all of an artist's plays on Last.fm are yours.
- **Favorites few people know**: artists you play a lot with the fewest listeners.
- **Songs you play more than anyone**, the same for songs.
- **Your favorite vs everyone's**: your most played song by each top artist, where it ranks on
  Last.fm, and everyone's favorite, with how often you played it.

Artist, song and album pages get a **You vs everyone** block (listeners, plays, you against the
average listener, your share), artist pages everyone's **most played songs** with your plays next
to each (the ones you haven't heard yet are dimmed), and the story a slide with your top artist.

#### Explore

The **Explore** tab digs into what never makes the top lists:

- **Lost favorites**: artists you played a lot that have gone quiet for half a year or more,
  with the month they were big for you.
- **Albums to rediscover**: albums you played through (several different songs), then left.
- **Quiet regulars**: artists outside your top ones that come back month after month.
- **One-day wonders**: played all day once, never again.
- **Deep cuts**: songs by your favorite artists you've played only once or twice.
- **On this day**: what you played on today's date in earlier years.

Each list makes a **playlist** (up to 40 songs), and so do **Crate dig** (songs you played a few
times and forgot, shuffled anew each time) and **Playlist of the top 40** under Top 10 on the
Stats tab. **Copy list** puts it on the clipboard as "Artist - Title" lines; paste that into
TuneMyMusic or Soundiiz (import from text) and send it to TIDAL. **Share** hands it to any app.
The pencil next to its name gives a playlist your own name and description; TIDAL gets both, and
Share puts them above the songs.

Tap any artist (in the lists, the phases, the record, or on a record page) for their own page:
plays, rank, first play, busiest month, longest streak, plays per month, time of day, their plays
lit up on your record, top tracks and albums, and the records of theirs on your shelf.

#### Tidy up: videos and app sounds

A scrobbler that listened to every app may have sent things that aren't music: "HBO Max", a game
trailer from YouTube, a short clip that played on loop. When the history has such scrobbles, a
banner on the Stats tab opens **Tidy up stats** (also under Settings → Stats and listening). It lists:

- **Look like videos or apps**: tracks without an album that are named like an app, have a
  video-style title (trailer, episode, "Title | Channel"), have the artist's name as their title,
  or were scrobbled again every few seconds. **Hide** them one by one or all at once, or **Keep**
  the ones that are music after all.
- **Most played without an album**: probably music, but worth a look.
- **Hidden**: everything you hid, with **Show** to bring it back.

You can also hide a single track from an artist's top tracks, or a whole artist with the hide
button at the top of their page. Hidden plays leave all the stats, the story and the artist
pages. Nothing changes on Last.fm: apps can't delete scrobbles there. To delete them on Last.fm
too, open a hidden track (↗) and delete its scrobbles from your library on the website.

Features and collaborations count for the main artist, as long as that artist also appears on
their own: "Fontaines D.C., ROSALÍA" counts for Fontaines D.C., and anything "feat." goes to the
first name, but "Earth, Wind & Fire" stays one band.

Days, weeks and hours are in the phone's local time.

### The look

Headings and big numbers are set in Fraunces, a soft serif; labels and details in DM Mono, like
liner notes (both bundled, under the SIL Open Font License, in `assets/ui/fonts`). A faint print
grain lies over everything. Behind the top of the app glows the color of what plays now (the
record spinning, or the song on the phone), else of the last one you played; in Tinted soft the
tabs take a calm shade of it too. Pick **Your records**, **Wallpaper** (your Material You colors)
or **Off** in Settings → Look → Background color.

The shelf's count sits next to its title. One line under it has the order (tap it for the others:
recently or first added, artist A–Z or Z–A, year, plays), the filter button, and three views: the
grid of covers, big covers two to a row with year and plays, and a list with year, label, format,
two styles and plays.

What plays right now, on the turntable or in a music app on the phone, shows as a sleeve with the
record half out of it, turning while it plays. Settings → Look → Now playing puts it **On the
shelf** (a line at the top, with the progress) or **In the corner** (the record peeking out above
the search button, from every tab). Tap it for Now spinning, or, for the phone, Now playing: the
song's exact position (drag to seek), play/pause and skip, what the app plays next (with the
playlist's or mix's name) when it shares its queue, and the whole album with the song in it and
how much of the album is left (tracklists from Deezer). Tap the app's name to open it on its own
player screen. Apps that work with Android Auto usually
share their queue; the chart button at the top opens the song's stats.

Record, album, song and artist pages and Now spinning take their cover or photo: shrunk to a few
pixels once and stretched (that is the blur, so nothing is recomputed while scrolling), under a
veil of the page color so the text stays readable. Settings → Look → Style picks how:

- **Tinted soft** (the default): soft UI. The page takes a calm shade of the cover's color, buttons
  and cards are raised from it with light and shade (pressed ones sink in), and the cover only glows
  behind the top of the page.
- **Soft glass**: the blurred cover lies behind the whole page, and buttons and cards float over it
  frosted, with a soft shadow and a lit edge.

Both give each page one solid button and its links in the cover's color. Top bars scroll away
with the page and come back over it, on a soft fade of the page's color, as soon as you scroll up;
the tabs sit in a floating bar at the bottom, with the page fading out underneath.

Things move the way they connect: a pill slides to the chosen tab, view or option; pages come in
from the side and leave the same way; a record's cover flies from the shelf up to its page and back
home; sheets rise from the bottom and can be dragged down; search grows out of its round button.
Android's back swipe shows where it goes: from Android 14 the page (or sheet, or search) follows
your finger and closes on release, or springs back if you change your mind; on the shelf, with
nothing left to close, back is Android's own, with its preview of leaving the app where the phone
shows one (Android 15 does by default). The app icon is a record whose groove is one spiral in the app's
colors, turning in to the label; on Android 13 and later it can follow your wallpaper colors (themed icons).

On a record page, or the album or song page of a record you own, tap the sleeve to turn it over (the record rolls back into the sleeve first, and rolls out again after you turn it back): the back has the tracklist by side, the label
and format, a barcode, and your plays stamped on it. Now spinning shows the record on a turntable:
the cover is the label, the platter turns while a track plays, and the tonearm moves from the
outer groove toward the label as the side goes on.

### Moving covers

Big covers tilt and catch the light as you move the phone. Records on your shelf are the
collector's edition, wherever they show up (record page, Now spinning, album and song pages, the
story): holo foil and the record sliding out of its sleeve, which spins
while a side plays. Albums you don't own just tilt. Artist photos sit in a frame that tilts too.
The motion sensor only runs while such a page is open. Switch it off in Settings → Look →
Moving covers. The "plays on Last.fm" chip on a record page opens your Last.fm library for that
album (in the Last.fm app if it's installed).

## Scrobbling a record

Tap a record, then **Scrobble** (or **Scrobble** next to a side, or tap a single track):

- **What you play:** a side, the whole record, or chosen tracks.
- **When:**
  - **Starting now** – the tracks are timed from now. Last.fm shows "now playing", and each track
    is scrobbled once half of it (or 4 minutes) has played, which is Last.fm's own rule.
    When the side ends, a notification offers to start the next side.
  - **Earlier** – pick the start date and time ("I played this last night").
  - **Just finished** – everything is scrobbled right away, ending now.
- **Out of step with the record?** On the Now spinning page, tap the track that is actually
  playing; the times after it move.
- **Offline?** Scrobbles wait on the phone and go out automatically once it is online again
  (also after a reboot). The Activity tab shows what was sent, what waits, and what Last.fm
  ignored and why.
- **Two-week limit:** Last.fm silently ignores scrobbles older than about two weeks. The app
  blocks scrobbling a listen that old and warns when waiting scrobbles get close to the limit.

### Filtering the shelf

The filter button next to the order narrows the shelf by **Decade** or **Genre or style** (Discogs
genres like Rock, or styles like Post-Punk), each with how many records it has; the filters in use
show under the line, each with its ✕. A summary card then shows the
selection: records, artists, Last.fm plays, never played, the most played, and how it splits by
decade or style. For a genre, **Your … listening** opens the Stats tab for that genre.

On the Stats tab, the filter button next to Week · Month · Year · All picks **One genre**: everything there and in the story then counts
only the plays whose main genre is that one (the same genre the genre charts count them under).
**One decade** does the same for when the music came out, and the two combine ("Post Punk from
the 1970s"). The **Decades** chart shows the period by decade of release; tap a decade to filter.

Release years come from Deezer, an artist's discography at a time: the year a title first came
out (remasters and deluxe editions come later), or the year of your own record if that is
earlier. The app looks them up in the background for the albums behind most of your plays, and
again for new ones every few hours; the decade sheet says how much of your history has a year.
Songs scrobbled without an album have none.

### Track lengths

Discogs often has no track lengths. The app fills the gaps in this order: the MusicBrainz release
linked to that exact Discogs release, Last.fm's album info, a MusicBrainz search by artist and title,
and finally a default length you choose in Settings (3:30 unless you change it). Guessed lengths show
as "≈3:30" on the record page. "Look up lengths again" retries the lookups.

### "Most played"

Play counts on the shelf are your Last.fm all-time album plays, so they include streaming (Tidal via
Pano Scrobbler) as well as vinyl. They refresh after a sync and at most every 12 hours.
