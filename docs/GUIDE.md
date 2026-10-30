# Heavy Rotation: the guide

Everything the app does, and how to set it up. The short version is in the [README](../README.md).

## Set up (a few minutes)

### 1. Discogs: your shelf

Gear → Discogs → type your Discogs username → Connect. If your collection is public on Discogs
(set in your Discogs privacy settings), that is all. The collection syncs right away; tap the
shelf's sync button whenever you add records.

If your collection is private, go to "Connect with a token" instead. On discogs.com, open
Settings → Developers (<https://www.discogs.com/settings/developers>), tap Generate new token, paste
the token into the app and connect. The app uses it only to read your collection and release data,
and you can revoke it on that page at any time.

### 2. Last.fm: where scrobbles go

Gear → Last.fm → Connect Last.fm. Your browser opens Last.fm's "connect application" page: tap
"Yes, allow access", then switch back to Heavy Rotation. The connection finishes on its own; if it
doesn't, tap "I've allowed it".

The app may ask for an API key instead, either because the build doesn't include the app's own key
or because you chose "Use your own API key". In that case, signed in to Last.fm, open
<https://www.last.fm/api/account/create>, fill in an email, a name such as "Heavy Rotation" and any
description, leave Callback URL empty, and paste the API key and Shared secret the page shows into
the app.

### 3. Moving from another phone or version

Gear → Backup → Export backup saves one file with your history, hide list, activity, collection,
tracklists, caches and settings. In the other app, tap Restore… and pick the file; the app restarts
with it. Keys, tokens and logins are not in the file, so connect again after restoring. Scrobbles
still waiting to be sent are not in it either.

### 4. Notifications

Allow notifications when the app asks (on your first scrobble). The "Now spinning" notification
shows the current track with a countdown and has two buttons, Stop and "Flip to side B".

### 5. Phone scrobbling (replacing Pano Scrobbler)

1. Gear → Phone scrobbling → Shadow.
2. Tap Allow notification access and switch Heavy Rotation on. Android calls this notification
   access because it is the only way an app may see other apps' media players; Heavy Rotation
   doesn't read your notifications.
   - If the switch is grayed out with "Restricted setting" (Android does that for apps installed
     from an APK file), open App info (a button in the app), tap ⋮ at the top right, choose Allow
     restricted settings, then try again.
3. TIDAL is switched on by default. Other apps appear in the list once they play something.
4. Keep Pano running and listen as usual for a day or two. In shadow mode Heavy Rotation only logs
   what it would scrobble (marked "Shadow" on the Activity tab) and sends nothing.
5. On the Activity tab, tap Compare with Pano. It reads what reached Last.fm since shadow mode
   started and shows how much Heavy Rotation caught, what only Pano scrobbled, what only Heavy
   Rotation would have scrobbled, and tracks the two apps named differently.
6. When you're happy with the result, switch Phone scrobbling to On and turn off Pano's scrobbling
   (or uninstall Pano). Otherwise every track is scrobbled twice; the app shows a warning while Pano
   is installed.

Heavy Rotation follows Last.fm's rules. A track counts once it has played for half its length or 4
minutes, whichever comes first, and only if it is longer than 30 seconds. Only time spent actually
playing counts; pauses, buffering and seeking forward don't. Each scrobble carries the time the
track started. Playing a track again from the start, including on repeat, makes a new scrobble.
Tracks whose length the music app doesn't report need 4 minutes. In On mode, "now playing" goes to
Last.fm too, and scrobbles use the same offline queue as records.

When no record is spinning, the card at the top of the Shelf shows what a music app on the phone
(TIDAL, for example) is playing: the song, the app, the cover (from your shelf or from Deezer) and
the progress. Tap it for the song's page.

Fix names (Settings → Scrobbling) changes names before they are scrobbled, like Pano Scrobbler's
edits, for the phone and for records alike. It has ready-made fixes you can switch on
("Remastered", "Explicit", "feat. …", "- Single", YouTube's "- Topic") and your own rules. A rule
looks at the artist, the song or the album. With "is", a name that equals the given text is
replaced as a whole. With "contains", or "matches" for a regular expression, the matching part is
replaced or taken out. A rule can be limited to one artist, and the rule sheet tries it on your
recent scrobbles. Scrobbles already sent stay as they are; Last.fm can only be edited on its
website.

The home screen widget (long-press the home screen → Widgets → Heavy Rotation) shows what is
playing now, on the turntable or the phone, or else the last song you played. It shows the cover
and today's and this week's plays, in your wallpaper's colors. Tap it to open the app, on the
record or Now playing page while something plays.

### 6. TIDAL (optional: exact links and playlists)

Without a TIDAL connection, the TIDAL buttons open TIDAL's search. Once you are connected, they open
the exact song, album or artist, and playlists from the Explore tab go straight into your TIDAL
account.

Gear → TIDAL → Sign in to TIDAL. TIDAL's login page opens; approve, and the browser hands you back
to Heavy Rotation, which shows "Signed in to TIDAL".

The app may ask for a Client ID and secret instead, either because the build doesn't include the
app's own TIDAL app or because you chose "Use your own TIDAL app". Then open
<https://developer.tidal.com/dashboard>, log in, accept the developer guidelines and create an app
(any name). Add the redirect URI `recordshelf://tidal` (Settings → TIDAL has a Copy button for it)
and the scope `playlists.write`, then copy the app's Client ID and Client Secret into Heavy Rotation
and sign in.

Once you are signed in, a playlist page has "Create in TIDAL". Heavy Rotation finds each song in
TIDAL's catalog, creates an unlisted playlist in your library and adds the songs; songs TIDAL
doesn't have are listed. Found songs are remembered on the phone, so the next playlist is quicker.

Your TIDAL login (and your own client secret, if you entered one) stays in the app's private
storage and, like the Last.fm keys, is left out of backups. Disconnect forgets them; playlists
already made stay in TIDAL.

If the dashboard doesn't accept `recordshelf://tidal` as a redirect URI, the sign-in can't return to
the app. Exact links still work without signing in, and playlists can still reach TIDAL through
Copy list and TuneMyMusic.

### 7. Stats

Open the Stats tab and tap Import history. The app copies all your scrobbles from Last.fm to the
phone, 200 per request. A long history takes a few minutes the first time, roughly one minute per
20,000 scrobbles. You can use other apps meanwhile; a notification shows the progress. If the
import is interrupted, it continues where it stopped the next time you open the app. After that,
only new scrobbles are fetched, at most every 30 minutes, when you open the app.

The period is the page's title. Step back and forth with ‹ and ›, and pick Day, Week, Month, Year
or All below the title. A period that is still running is compared with the same stretch of the one
before ("vs this time last week").

The Day tab is built for a handful of songs. It shows the time you listened, and how that compares
with your usual for that weekday by the same time of day over the last 8 weeks. Your day appears on
a 24-hour strip with its listening sessions: a new session starts after half an hour without music,
and a session that was one record shows the album or the turntable. Below come the first and latest
song, what you had on repeat, artists and songs new that day, the same date in other years (tap one
to see that day), your streak and how much came off vinyl.

A week shows its seven days, the week before in outline, and each day on a 24-hour strip. Charts of
change over the period (the river, phases, genre drift) appear once the period has four days with
music.

A week, month, year or all time comes in four tabs under the period: Overview (the story, the
numbers, the highlights, your week), Listening (your record, plays over time, the river, when you
listen, streaks, phases, habits), Music (top lists, new artists, obsessions, genre drift, decades)
and Shelf (rarely played records and wishlist ideas). The period and the genre or decade filter
apply to every tab. A day is short, so it stays one page. Settings → Look → Stats and Explore turns
the tabs off.

"Arrange this page", at the bottom, reorders the blocks (drag the handles) and hides the ones you
don't want; in tabs, each block stays in its tab, in the order you gave it. The Day tab has its own
order. The layout goes into backups with the other settings.
The charts adapt to the period: a week or a month is shown per day, a year per week or month, all
time per month, quarter or year. These are the blocks for the chosen period:

- Your story, the card at the top, is a Wrapped-style story you tap through like Instagram stories:
  right side forward, left side back, hold to pause. Its colors come from the period's top album
  and drift behind each slide. The slides show total plays with a ticker running across; your
  listening personality (night owl, early bird, loyalist, explorer, on repeat, weekender, crate
  digger, genre hopper or all-rounder) in a turning ring of words; your top artist's photo
  full-screen in two colors; the top 5 with photos; the song of the period as a 7" single spinning
  at 45; the album as a moving cover; your golden hour on a sundial; one star per new artist; a song
  on repeat; your genres as a cloud of words; vinyl; your longest streak as a row of days; and a
  summary printed like the back of a record sleeve. If your setlist.fm concerts are set up, the
  period's shows appear as ticket stubs.
- Totals shows plays, hours (about 3.5 minutes a track), artists and new artists, each with the
  change against the period before. For a period still running (this week, this month) it compares
  with the same stretch of the previous one: Monday to Wednesday against last Monday to Wednesday.
- Highlights describes the period in sentences: more or less than last time, whether your top
  artist held on, an artist you came back to after months, discoveries, your busiest day and hours,
  night listening, the song of the period, streaks, your biggest day and how much was vinyl.
- "Your record" puts every play as a dot on a vinyl disc. Each groove is a day, from the label (the
  first day of the period) to the rim (the last); noon is at the top, midnight at the bottom. Your
  eight most played artists have their own colors, with a legend below. To light up one artist, tap
  a name in the legend, type in the "Find an artist on the record" field, or tap near a dot; tap the
  label to show everyone again.
- Listening over time shows plays per day, week or month, with the average marked. You can tap a
  column.
- The river shows the same over time, split into your top eight artists (in the same colors as on
  the record) stacked on everyone else. Tap it for a day's numbers.
- "When you listen" shows plays per weekday and hour. Tap a cell for its numbers.
- Streaks shows your current run of days with music (counted back from today, including days before
  the period started), the longest run in the period, and artists you played many days in a row.
- Phases shows plays of your most played artists as ridges, ordered by when they peaked.
- New artists shows artists heard for the first time, per day, week or month. The stretch where your
  Last.fm history starts is grayed out, since everything is new there.
- Genre drift shows how your genres shift over time. Every play gets one genre, weighed from the
  Discogs styles of the record (if you own it), your other records by the artist, the artist's
  Last.fm tags and, for your most played albums, the album's own Last.fm tags, which catches an
  artist's electronic album among their rock ones. Non-genres like "seen live" are removed. The six
  biggest genres keep their colors whatever range you pick.
- Obsessions lists tracks played at least 7 times in one week, and songs played back to back
  several times in a row.
- "On your shelf, rarely played" lists your records, least played first.
- Wishlist ideas lists albums you play a lot, across several different tracks, that aren't on your
  shelf. Tap one to search Discogs.
- Top 10 lists your ten most played artists, albums and tracks.

#### Songs, albums and artists

Tap a scrobble on the Activity tab, or a song in the stats lists, for the song's page: all-time
plays and rank, this period's rank, first and last play, its biggest day, how much of the artist it
makes up, plays per month, time of day, and what you tend to play right after it. Album pages (from
Top 10, an artist's albums, or a record's "Your stats" chip) show the same, plus which of their
songs you play.

Song, album, artist and record pages have "Listen on" buttons that search TIDAL and Spotify for
them. Choose the services under Settings → Listen on; YouTube Music, Apple Music and Deezer are
available too. Spotify opens in its app; TIDAL opens its search page, which lists the match first.

Song, album and artist pages take their colors from the cover or the artist's photo: a glow behind
the header, and charts in that color. Each has a listening barcode (one line per week since you
first played it, stronger for more plays), plays per month as a smooth curve with its peak marked,
and "around the clock", the 24 hours as a ring around the cover with longer wedges for the hours
you play it most. Tap any of them for the numbers.

These pages cover all your time with that artist, album or song, whatever period the Stats tab is
on. Opened from a period other than All, they add that period: a tile with its plays and rank, and
on artist and album pages the songs you played most in it.

Tap a rank tile ("#3 of 735 songs") for the whole list it comes from: every song, album or artist of
all time or of that period, by plays, with this one marked and scrolled to. Tabs switch between
songs, albums and artists. Equal plays share a rank. "The whole list", under Top 10 on the Stats
tab, opens the same list for the Stats period.

Album and song pages show their genres too: the album's own when Last.fm knows them (looked up when
you open the page, if the album isn't among your most played), otherwise the artist's.

Artist pages show the whole discography from Deezer's catalog: albums, EPs, singles and
compilations, with editions folded together ("Romance" and its deluxe edition are one record). Each
title shows your plays, a foil star if it's on your shelf and "New" for the last two months; titles
you haven't heard are dimmed. Filter by Played, Not heard yet or On your shelf. Tap a title for its
album page, which works for any album, played or not, and shows the tracklist with your plays per
song, the year and the label. Discographies are kept on the phone for a week, so new releases show
up.

With setlist.fm set up, artist pages have "On stage lately": the songs of the artist's usual set at
their recent shows, in their usual order (songs played at about a third of the shows or more,
encore songs last), with your plays of each and "new" for the ones you haven't heard. It also lists
their next show, if one is listed, and their recent shows. Tapping Make a concert playlist turns the
usual set into a playlist; tap a show for a playlist of that night's setlist. Both go to TIDAL or
through Copy list, like every playlist. Fans write the setlists on setlist.fm, so recent shows can
still be missing.

To set it up, log in to [setlist.fm](https://www.setlist.fm) (a free account), open its
[API settings](https://www.setlist.fm/settings/api) and apply for a key (free for personal,
non-commercial use; any application name will do), then paste the key in Settings → setlist.fm.

To bring in your own concerts, add your setlist.fm username there too. The shows you marked "I was
there" on setlist.fm come in, and are fetched again once a week or when you tap Update now. They
show on the artist's page under "You saw them live", in Explore, in the period's Highlights on the
Stats tab, and as ticket stubs in the period's story. In Explore, "Your concerts" has every show by
year, the artists you saw most, and the afterglow: the shows that sent you back to an artist's
music most, measured as your plays of them in the four weeks after the show against the four weeks
before. Tap a show for that night's setlist as a playlist.

Artist photos come from Deezer's public catalog (no account needed). Each is looked up once and kept
on the phone; they show on artist pages, in Top 10 and Explore, and in the story.

Phone scrobbles carry no cover, so the Activity tab takes one from your shelf (if you own the album)
or from Deezer, looked up once and kept on the phone.

#### Search

The round button next to the tabs searches your records (by title, artist, label, year or style)
and every artist, album and song in your listening history, most played first. On the shelf your
records come first; on the other tabs what you played comes first. Every word you type has to match
somewhere, so "fire strokes" finds Room on Fire.

#### Genres

Genre names are buttons: the styles on a record page, the genres on an artist page, and the legend
of the genre charts on the Stats tab. Each opens that genre's page. It shows how much of your
listening the genre makes up and where it ranks, its plays and artists, since when you've played it
and its biggest day. Below come your top artists (with photos), albums and songs in it; Rediscover,
the artists and albums of that genre you loved and haven't played for half a year or more;
"Everyone in …", every artist you played in it; when its music came out; its listening barcode,
plays per month and hours; and the records on your shelf with that genre or style. "Full stats for
…" opens the Stats tab for that genre.

#### Your musical atlas

The Musical atlas tile at the top of the Explore tab opens three views of your whole history, worked
out from the order of your scrobbles.

- Universe shows your 150 most played artists as stars, sized by plays. Two stars are linked when
  you often play them back to back (in the same listening session, relative to how much you play
  both), or, for artists you rarely play next to anything, when they share the same months. Linked
  stars are pulled close, so groups form. These groups are the constellations, each colored, named
  after its biggest star and labeled with its main genre. Bridges are artists strongly linked into
  two constellations. Drag to move, pinch or use + and − to zoom, and tap near a star: the nearest
  one within a finger's width is picked, the map flies to it and a card shows its links as buttons,
  so you can hop from star to star without aiming. The card also says how you found the artist and
  what you usually play before and after them. You can search for an artist, pick a constellation,
  or pick a year to light up the artists you played then.
- Family tree shows, for every artist you kept playing (5 plays or more), what you played right
  before your very first play of them, in the same session. The artists that led you to the most
  others are the gateways; unfold them into their tree. "Right after" means the very next song, so
  with radio or autoplay the new artist was the app's pick. Artists from the first two weeks of your
  history have no known origin. Artist pages show the same under "How you found them": the chain
  back as far as it goes, and who they led you to.
- Landscape shows your 14 most played artists as mountains of plays per month, the earliest peaks
  at the back. Each artist is labeled an obsession (most plays within a few months), a companion (a
  year or more), a comeback (six quiet months or more, then back) or a phase. Tap a mountain or a
  row for its peak.

Play counts are song plays: an album's "124 plays" means 124 scrobbles of its songs. Record and
album pages turn that into "≈ 12 times through" when the number of songs is known.

#### You vs everyone

Last.fm knows how many people listened to every artist, album and song, and how often, over all
time. The app asks for those counts (with your API key, a few lookups a second, about two minutes
the first time) for your 200 most played artists and 150 most played songs, plus everyone's most
played songs by your 25 top artists. It keeps them on the phone and looks them up again once a
month. Other artists, songs and albums are looked up when you open their page.

The You vs everyone tile, next to the Musical atlas tile, opens the comparison:

- "How mainstream your artists are" splits your plays by how many listeners the artist has
  (1M+, 250K–1M, 50K–250K, 10K–50K, under 10K), with the line "half of your plays go to artists
  with fewer than … listeners".
- "A bigger fan than most" lists artists you play many times more than their average listener
  does. The average is all their plays divided by all their listeners; a few heavy listeners pull it
  up, so most people played less. The bar is you, the tick is the average listener.
- "Your share" gives your part of all of an artist's plays on Last.fm, such as "1 in 230".
- "Favorites few people know" lists the artists you play a lot that have the fewest listeners.
- "Songs you play more than anyone" does the same for songs.
- "Your favorite vs everyone's" shows your most played song by each top artist, where it ranks on
  Last.fm, and everyone's favorite, with how often you played it.

Artist, song and album pages get a You vs everyone block (listeners, plays, you against the average
listener, your share). Artist pages also list everyone's most played songs with your plays next to
each, and dim the ones you haven't heard yet. The story gets a slide with your top artist.

#### Explore

Explore has three tabs. For you suggests music you haven't played. Rediscover has the lists below
and Crate dig. Your taste has the genre map, the musical atlas, you vs everyone and your concerts.
With Settings → Look → Stats and Explore set to One long page, all of it comes on one page.

#### For you

For you starts from the 25 artists you play most, with recent plays counting the most (a play
counts half as much after four months). For each of them the app asks Last.fm which artists its
listeners find similar, and Deezer which artists its listeners play alongside. When both name an
artist, that counts for more than either one alone. Each suggestion gets the likeness from each
of your artists that points to it, weighed by how much you play that artist, so one that several of
your artists point to ranks high. Artists you've played or have on your shelf never show up. The
first look takes a minute or two; after that it runs again once a week, when you open Explore.

- Artists you might like lists the best 40 with the reason ("Because you play Shame, IDLES and
  Fontaines D.C."), their genre and how many fans they have on Deezer. Close keeps to your taste,
  Further out prefers less known artists and also looks one step further, at who is like your best
  suggestions. Tap a suggestion to see which of your artists point to them and how strongly, and
  their best-known songs, with a playlist of those and their artist page. The artist page of
  someone you haven't played shows their best-known songs, their discography and their shows.
- Not for me (the ✕ on a row, or in the sheet) drops an artist for good, and the artists of yours
  that led to them count a little less from then on. Undo takes it back right away; Settings →
  Stats and listening → Suggest them again brings back everyone you turned down.
- The discovery mix is 30 songs you haven't played: the two best-known songs of each of 15
  suggested artists, no artist twice in a row. It stays the same all week and changes on Monday.
  Make another picks other artists and songs. Like every playlist, it can go to TIDAL.
- New from your artists has the albums, EPs and singles your artists put out in the last eight
  weeks, or that come out soon, from Deezer.

Settings → Stats and listening → Suggestions on Explore turns all of this off, and with it the
lookups.

#### Rediscover

The Rediscover tab collects listening that never makes the top lists:

- Lost favorites are artists you played a lot that have gone quiet for half a year or more, with
  the month they were big for you.
- Albums to rediscover are albums you played through (several different songs), then left.
- Quiet regulars are artists outside your top ones that come back month after month.
- One-day wonders are what you played all day once and never again.
- Deep cuts are songs by your favorite artists you've played only once or twice.
- On this day shows what you played on today's date in earlier years.

Each list can become a playlist of up to 40 songs. So can Crate dig (songs you played a few times
and forgot, shuffled anew each time) and Playlist of the top 40, under Top 10 on the Stats tab. On
a playlist, Copy list puts it on the clipboard as "Artist - Title" lines, which you can paste into
TuneMyMusic or Soundiiz (import from text) to send it to TIDAL. Share hands the playlist to any
app. The pencil next to a playlist's name lets you give it your own name and description; TIDAL
gets both, and Share puts them above the songs.

Tap any artist (in the lists, the phases, the record, or on a record page) for their own page:
plays, rank, first play, busiest month, longest streak, plays per month, time of day, their plays
lit up on your record, top tracks and albums, and the records of theirs on your shelf.

#### Tidy up: videos and app sounds

A scrobbler that listened to every app may have sent things that aren't music: "HBO Max", a game
trailer from YouTube, a short clip that played on loop. When the history has such scrobbles, a
banner on the Stats tab opens Tidy up stats (also under Settings → Stats and listening). It has
three lists:

- "Look like videos or apps" has tracks without an album that are named like an app, have a
  video-style title (trailer, episode, "Title | Channel"), have the artist's name as their title,
  or were scrobbled again every few seconds. Hide them one by one or all at once, or tap Keep on the
  ones that are music after all.
- "Most played without an album" is probably music, but worth a look.
- "Hidden" has everything you hid, with Show to bring it back.

You can also hide a single track from an artist's top tracks, or a whole artist with the hide button
at the top of their page. Hidden plays are left out of all the stats, the story and the artist
pages. Nothing changes on Last.fm, because apps can't delete scrobbles there. To delete them on
Last.fm too, open a hidden track (↗) and delete its scrobbles from your library on the website.

Features and collaborations count for the main artist, as long as that artist also appears on their
own: "Fontaines D.C., ROSALÍA" counts for Fontaines D.C., and anything "feat." goes to the first
name, but "Earth, Wind & Fire" stays one band.

Days, weeks and hours are in the phone's local time.

### The look

Headings and big numbers are set in Fraunces, a soft serif, and labels and details in DM Mono, like
liner notes. Both fonts are bundled, under the SIL Open Font License, in `assets/ui/fonts`. A faint
print grain lies over everything. Behind the top of the app glows the color of what is playing now
(the record spinning, or the song on the phone), or else of the last thing you played; in the
Tinted soft style the tabs take a calm shade of it too. Choose Your records, Wallpaper (your
Material You colors) or Off in Settings → Look → Background color.

Artist, album and genre pages come in tabs, each a screen or two long; Settings → Look → Pages
switches them to One long page. An artist's tabs are Overview (the numbers, the period, you vs
everyone, how you found them), Listening (the charts), Music (top tracks, albums, your shelf, the
discography, their most played songs) and Live (the shows you were at, and what they play
lately). An album's are Overview, Songs (track by track and the tracklist) and Listening (when it
clicked, its songs over time, the charts). A genre's are Overview (the numbers and the styles in
it), Artists, Music (albums, songs, records to rediscover, your shelf, decades) and Listening. The
tabs stay at the top as you scroll. Swipe sideways to move to the next tab: it comes in beside the
open one as you drag, and slides into place when you let go past a third of the way or flick.
Rows that scroll sideways and charts you can scrub keep their own swipes. In an artist's Live tab
the setlist comes in two columns and the shows scroll sideways as ticket stubs.

The number of records on the shelf sits next to its title. One line under the title has the sort
order (tap it for the others: recently or first added, artist A–Z or Z–A, year, plays), the filter
button, and three views: the grid of covers, big covers two to a row with year and plays, and a
list with year, label, format, two styles and plays.

What is playing right now, on the turntable or in a music app on the phone, shows as a sleeve with
the record half out of it, turning while it plays. Settings → Look → Now playing puts it On the
shelf (a line at the top, with the progress) or In the corner (the record peeking out above the
search button, on every tab). Tap it for Now spinning or, for the phone, Now playing. Now playing
has the song's exact position (drag to seek), play/pause and skip, what the app plays next (with
the playlist's or mix's name) when the app shares its queue, and the whole album the song is on,
with how much of the album is left (tracklists from Deezer). Tap the app's name to open the app on
its own player screen. Apps that work with Android Auto usually share their queue. The chart button
at the top opens the song's stats.

Record, album, song and artist pages and Now spinning use their cover or photo as a background. It
is shrunk to a few pixels once and stretched, which makes the blur, so nothing is recomputed while
scrolling. A veil of the page color over it keeps the text readable. Settings → Look → Style picks
between two styles. Tinted soft, the default, is a soft UI: the page takes a calm shade of the
cover's color, buttons and cards are raised from it with light and shade (pressed ones sink in),
and the cover only glows behind the top of the page. In Soft glass, the blurred cover lies behind
the whole page, and buttons and cards float over it, frosted, with a soft shadow and a lit edge.

Both styles give each page one solid button and its links in the cover's color. Top bars scroll
away with the page and come back over it, on a soft fade of the page's color, as soon as you scroll
up. The tabs sit in a floating bar at the bottom, with the page fading out underneath.

Animations show where each thing comes from: a pill slides to the chosen tab, view or option; pages
come in from the side and leave the same way; a record's cover flies from the shelf up to its page
and back; sheets rise from the bottom and can be dragged down; search grows out of its round
button. Android's back swipe shows where it goes. From Android 14, the page (or sheet, or search)
follows your finger and closes on release, or springs back if you change your mind. On the shelf,
with nothing left to close, back is Android's own, with its preview of leaving the app on phones
that show one (Android 15 does by default). The app icon is a record whose groove is one spiral in
the app's colors, turning in to the label. On Android 13 and later it can follow your wallpaper
colors (themed icons).

On a record page, or the album or song page of a record you own, tap the sleeve to turn it over.
The record rolls back into the sleeve first and rolls out again after you turn it back. The back
has the tracklist by side, the label and format, a barcode, and your plays stamped on it. Now
spinning shows the record on a turntable: the cover is the label, the platter turns while a track
plays, and the tonearm moves from the outer groove toward the label as the side goes on.

### Moving covers

Big covers tilt and catch the light as you move the phone. Records on your shelf also get holo foil
wherever they show up (record page, Now spinning, album and song pages, the story), and the record
slides out of its sleeve and spins while a side plays. Albums you don't own only tilt. Artist photos
sit in a frame that tilts too. The motion sensor runs only while such a page is open. Switch the
effect off in Settings → Look → Moving covers. The "plays on Last.fm" chip on a record page opens
your Last.fm library for that album (in the Last.fm app if it's installed).

## Scrobbling a record

Tap a record, then Scrobble, or the Scrobble button next to a side, or tap a single track. Choose
what you play: a side, the whole record, or chosen tracks. Then choose when:

- "Starting now" times the tracks from now. Last.fm shows "now playing", and each track is
  scrobbled once half of it (or 4 minutes) has played, which is Last.fm's own rule. When the side
  ends, a notification offers to start the next side.
- "Earlier" lets you pick the start date and time ("I played this last night").
- "Just finished" scrobbles everything right away, ending now.

If the timing drifts from the record, tap the track that is actually playing on the Now spinning
page; the times after it move.

Offline, scrobbles wait on the phone and go out automatically once it is online again (also after
a reboot). The Activity tab shows what was sent, what waits, and what Last.fm ignored and why.

Last.fm silently ignores scrobbles older than about two weeks. The app blocks scrobbling a listen
that old and warns when waiting scrobbles get close to the limit.

### Filtering the shelf

The filter button next to the sort order narrows the shelf by Decade or by "Genre or style"
(Discogs genres like Rock, or styles like Post-Punk), each option with how many records it has.
Filters in use show under the line, each with its ✕. A summary card then shows the selection:
records, artists, Last.fm plays, never played, the most played, and how it splits by decade or
style. For a genre, "Your … listening" opens the Stats tab for that genre.

On the Stats tab, the filter button next to Week, Month, Year and All offers One genre: everything
on the tab and in the story then counts only the plays whose main genre is that one (the same genre
the genre charts count them under). The One decade filter does the same for when the music came
out, and the two combine ("Post Punk from the 1970s"). The Decades chart shows the period by decade
of release; tap a decade to filter.

Release years come from Deezer, one artist's discography at a time. A title's year is the year it
first came out (remasters and deluxe editions come later), or the year of your own record if that
is earlier. The app looks the years up in the background for the albums behind most of your plays,
and again for new ones every few hours; the decade sheet says how much of your history has a year.
Songs scrobbled without an album have none.

### Track lengths

Discogs often has no track lengths. The app fills the gaps in this order: the MusicBrainz release
linked to that exact Discogs release, Last.fm's album info, a MusicBrainz search by artist and
title, and finally a default length you choose in Settings (3:30 unless you change it). Guessed
lengths show as "≈3:30" on the record page. "Look up lengths again" retries the lookups.

### "Most played"

Play counts on the shelf are your Last.fm all-time album plays, so they include streaming (TIDAL
via Pano Scrobbler) as well as vinyl. They refresh after a sync and at most every 12 hours.
