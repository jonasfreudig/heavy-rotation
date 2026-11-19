# Heavy Rotation: the guide

This is the long version: how to set the app up and what everything in it does. If you just want
an overview, read the [README](../README.md).

## Set up (a few minutes)

The first time you open the app, a welcome screen tells you in three lines what it does and lists
the setup steps: Last.fm, Discogs and, if you want, scrobbling what you play on your phone. Each
step has a button and gets a check mark once it's done. Start (or Skip for now) takes you to the
shelf. You can do all of these later too, under Settings. The steps below are the same ones.

### 1. Discogs: your shelf

Gear → Discogs → type your Discogs username → Connect. If your collection is public on Discogs
(you set that in your Discogs privacy settings), that's all. The collection syncs right away. When
you add records later, tap the sync button on the shelf.

If your collection is private, use "Connect with a token" instead. On discogs.com, open
Settings → Developers (<https://www.discogs.com/settings/developers>), tap Generate new token,
paste the token into the app and connect. The app only uses it to read your collection and release
data, and you can revoke it on that page whenever you like.

### 2. Last.fm: where scrobbles go

Gear → Last.fm → Connect Last.fm. Your browser opens Last.fm's "connect application" page. Tap
"Yes, allow access" and switch back to Heavy Rotation. The connection finishes by itself. If it
doesn't, tap "I've allowed it".

Sometimes the app asks for an API key instead. That happens when the build you installed doesn't
include the app's own key, or when you chose "Use your own API key". In that case, sign in to
Last.fm and open <https://www.last.fm/api/account/create>. Fill in an email, a name such as "Heavy
Rotation" and any description, and leave Callback URL empty. The page then shows an API key and a
Shared secret. Paste both into the app.

### 3. Moving from another phone or version

Gear → Backup → Export backup saves one file with your history, hide list, activity, collection,
tracklists, caches and settings. On the other phone, tap Restore… and pick that file. The app
restarts with everything in place.

Keys, tokens and logins are never in the backup, so you have to connect your accounts again after
restoring. Scrobbles that were still waiting to be sent aren't in it either.

### 4. Notifications

Allow notifications when the app asks (it asks on your first scrobble). While a record plays, the
"Now spinning" notification shows the current track with a countdown and has two buttons, Stop and
"Flip to side B".

### 5. Phone scrobbling

This scrobbles what you play in TIDAL, Spotify and other music apps on the phone.

1. Gear → Phone scrobbling → Shadow.
2. Tap Allow notification access and switch Heavy Rotation on. Android calls this "notification
   access" because that's the only way one app is allowed to see another app's media player. The
   only notifications Heavy Rotation actually reads are the ones from Pixel's Now Playing (see
   Heard around you below).
   - If the switch is grayed out and says "Restricted setting" (Android does that for apps
     installed from an APK file), open App info (there's a button for it in the app), tap ⋮ at the
     top right, choose Allow restricted settings and try again.
3. TIDAL is switched on from the start. Other apps show up in the list once they've played
   something.
4. If you already use another scrobbler, leave it running and listen as usual for a day or two. In
   shadow mode Heavy Rotation sends nothing. It only writes down what it would have scrobbled
   (marked "Shadow" in Activity).
5. Open Activity (the clock button at the top) and tap Compare with Last.fm. The app reads what
   reached Last.fm since you turned shadow mode on and shows you how much Heavy Rotation caught,
   what only the other scrobbler sent, what only Heavy Rotation would have scrobbled, and tracks
   the two named differently.
6. When you're happy with the result, switch Phone scrobbling to On and turn the other scrobbler
   off. If both run, every track is scrobbled twice. The app warns you if Pano Scrobbler is still
   installed.

Heavy Rotation follows Last.fm's rules:

- A track counts once it has played for half its length or for 4 minutes, whichever comes first,
  and only if the track is longer than 30 seconds.
- Only time spent actually playing counts. Pauses, buffering and skipping ahead don't.
- Each scrobble carries the time the track started.
- Playing a track again from the start (also on repeat) makes a new scrobble.
- If the music app doesn't say how long a track is, the track needs 4 minutes.

In On mode the app also sends "now playing" to Last.fm, and phone scrobbles use the same offline
queue as records.

When no record is spinning, the card at the top of the Shelf shows what a music app on the phone
is playing: the song, the app, the cover (from your shelf or from Deezer) and how far along it is.
Tap it to open the song's page.

Fix names (Settings → Scrobbling) cleans up names before they are scrobbled. It works for the phone
and for records. There are ready-made fixes you can switch on ("Remastered", "Explicit",
"feat. …", "- Single", YouTube's "- Topic"), and you can add your own rules:

- A rule looks at the artist, the song or the album.
- With "is", a name that equals your text is replaced completely.
- With "contains", or "matches" for a regular expression, only the matching part is replaced or
  removed.
- You can limit a rule to one artist.
- The rule sheet tries the rule on your recent scrobbles, so you see what it would do.

Scrobbles that were already sent stay as they are. Last.fm can only be edited on its website.

#### Heard around you

On a Pixel, Now Playing recognizes songs that are playing around you. Turn on its notifications
(Settings → Sound and vibration → Now Playing), and every song it recognizes shows up in Heard
around you at the top of Activity, with when it was heard and for how long.

Nothing is scrobbled until you say so. The tick scrobbles a song (from the time it started), the ✕
drops it (Undo brings it back), and Scrobble all takes everything. The badge on the clock button
counts these songs together with scrobbles that are still waiting. While the phone is playing
music itself, Now Playing's songs are ignored. Songs wait for two weeks, which is as long as
Last.fm accepts late scrobbles.

If Now Playing hears a song that is on one of your records and no record is spinning, a
notification asks whether you're playing that record. Tap its button (or "Scrobble the record from
here" in the list) and the app scrobbles the record from that song to the end of the side,
starting from when the song was heard.

While a record spins, the songs Now Playing hears keep its timing right. If a song comes at a
different time than planned, the times shift to match. If you start the record from its own page
instead, the songs heard from it leave the list because they now count as plays of the record. A
song you already ticked by hand isn't scrobbled a second time.

Identify listens through the microphone for ten seconds and asks AudD what's playing. A song it
finds goes on the same list. Its time is when the song started, worked out from where in the song
the recording was. AudD answers a few lookups a day without an account and tells you when they're
used up for the day. The recording only goes to AudD and isn't kept. Android asks you once for
microphone access.

The home screen widget (long-press the home screen → Widgets → Heavy Rotation) shows what's
playing right now, on the turntable or on the phone, or otherwise the last song you played. It has
the cover and your plays for today and this week, in your wallpaper's colors. Tap it to open the
app. If something is playing, it opens right on the record or on Now playing.

### 6. TIDAL (optional: exact links and playlists)

Without a TIDAL connection, the TIDAL buttons open TIDAL's search. Once you're connected, they open
the exact song, album or artist, and playlists from the Explore tab go straight into your TIDAL
account.

Gear → TIDAL → Sign in to TIDAL. TIDAL's login page opens. Approve, and the browser sends you back
to Heavy Rotation, which then says "Signed in to TIDAL".

Sometimes the app asks for a Client ID and secret instead. That happens when the build doesn't
include the app's own TIDAL app, or when you chose "Use your own TIDAL app". Then:

1. Open <https://developer.tidal.com/dashboard>, log in and accept the developer guidelines.
2. Create an app. Any name works.
3. Add the redirect URI `recordshelf://tidal` (Settings → TIDAL has a Copy button for it) and the
   scope `playlists.write`.
4. Copy the app's Client ID and Client Secret into Heavy Rotation and sign in.

Once you're signed in, every playlist page has "Create in TIDAL". Heavy Rotation looks up each song
in TIDAL's catalog, creates an unlisted playlist in your library and adds the songs. Songs TIDAL
doesn't have are listed for you. The app remembers the songs it found, so the next playlist is
quicker.

Your TIDAL login (and your own client secret, if you entered one) stays in the app's private
storage. Like the Last.fm keys, it's left out of backups. Disconnect makes the app forget them.
Playlists you already made stay in TIDAL.

If the dashboard doesn't accept `recordshelf://tidal` as a redirect URI, the sign-in can't get back
to the app. Exact links still work without signing in, and you can still get playlists into TIDAL
with Copy list and TuneMyMusic.

### 7. Stats

Open the Stats tab and tap Import history. The app copies all your scrobbles from Last.fm to the
phone, 200 per request. A long history takes a few minutes the first time, roughly one minute per
20,000 scrobbles. You can use other apps in the meantime, and a notification shows the progress.
If the import gets interrupted, it picks up where it stopped the next time you open the app.

After that, only new scrobbles are fetched. That happens when you open the app or the Stats tab
(at most once a minute), and a few seconds after the app scrobbles something itself while it's
open.

#### Picking a period

At the top you pick Day, Week, Month, Year or All. The filter button sits next to them. Below that
you see the period's name, how its plays compare with the period before, and a strip with every
period of that kind since your history began (for days, the last 120). Each one has a bar for its
plays.

- Scroll the strip sideways and tap a period to go there.
- Or swipe sideways on the period's name to step to the one before or after.

A period that's still running is compared with the same stretch of the previous one ("vs this time
last week"). At the very bottom of the page you can see how many scrobbles you have and when they
were last updated.

When you scroll down and the strip disappears, a small pill stays at the top of the screen with
the period's name between ‹ and ›. Tap an arrow, or swipe the pill sideways, to go to the period
before or after. The chart you were looking at stays where it is on the screen while the page
changes around it, so you can flip through the years on one chart. Tap the name to jump back up to
the strip. A block that you opened on its own page (in Cards) has the same two arrows in its top
bar.

#### A day

The Day tab is made for a handful of songs. It shows how long you listened and how that compares
with what's usual for you on that weekday, by the same time of day, over the last 8 weeks.

Your day is laid out on a 24-hour strip with your listening sessions. A new session starts after
half an hour without music. A session that was one record shows the album or the turntable (tap
its name for the album).

- Tap a session to see its songs in a row underneath, one by one with their times. Scroll the row
  sideways, tap a song for its page, and tap the session again to close it.
- Touch the strip itself to see what played at that moment. A marker appears, and the song that
  was playing then (or the closest one) shows under the strip with its session highlighted. Keep
  your finger down and slide to move through the day.
- Swipe the song sideways for the next one (to the left) or the one before (to the right). One
  swipe moves one song, however hard you swipe, and the marker moves along.
- Tap the song to open its page. The ✕ clears the marker.

Further down you get the first and latest song, the day in covers, what you had on repeat,
artists that were new that day (as cards) and new songs, the same date in other years (tap one to
go to that day), your streak, how much came off vinyl, the day's lineup if four or more artists
played, and the top 10.

#### A week

A week shows its seven days as bars in the colors of your top artists, with the week before as a
line on each bar, and every day on a 24-hour strip in the same colors. The charts that show change
over time (the river, phases, genre drift) appear once the period has four days with music.

#### Three ways to lay out the page

Settings → Look → Stats decides how a week, month, year or all time looks.

Full size is the default. Every block is shown in full on one long page.

Cards keeps the page short. Days and streaks, who you played when, new artists, songs on repeat
and decades become cards, one under the other. Each card sums up what it found in one sentence
("30 days in a row", "Air steady, Fontaines D.C. in a wave") and has a small picture that you can
still read at that size. Tap a card and its block opens on its own page for the same period, with
everything in it: every new artist, every song on repeat, up to 16 artists in who you played when.
The bigger charts stay full size on the page.

Sections mixes several forms:

- Highlights, your chart, who you played when, your record and new artists stay full size.
- Decades and on repeat, and rarely played records and the wishlist, come as pairs of squares. Tap
  one and its block opens under the pair. Tap it again to close it.
- Days and streaks is a pill with the period's days and your longest run. Milestones is a pill with
  the biggest one. They open the same way.
- Related blocks of about the same height sit side by side in a strip: your week and your days,
  the river and genre drift, listening over time and when you listen, in covers and your lineup.
  Swipe sideways or tap a name above the strip to move between them. A strip is as tall as its
  tallest block, so it doesn't jump while you swipe. When you change the period, it keeps showing
  the block you picked, and squares you opened stay open. A sideways swipe on a chart inside a
  strip moves to the next block (taps on the chart work as usual).
- Your taste comes as small cards (your concerts as a ticket, each part of Through the years, the
  atlas), followed by you vs everyone with how mainstream your artists are. Tap a tier for its
  artists.

In every layout, rows of covers or cards run past the edge of their card to the edges of the
screen while you scroll them. Sections replaced the old Classic page in 0.93. If you used Classic,
you now get Sections.

A day is short, so it always shows its blocks full size.

"Arrange this page", at the bottom, lets you reorder the blocks (drag the handles) and hide the
ones you don't want. In Cards you can also choose for each block whether it's a card or full size
(Card or Full next to its name). The Day tab has its own order. Your layout is saved in backups
together with the other settings.

#### What's on the page

The numbers and highlights are links. Tap Plays for listening over time, Artists for the whole
list, New artists for the new artists, and a highlight for the song, album, artist, day or block
it's about.

The charts adapt to the period. A week or a month is shown per day, a year per week or month, and
all time per month, quarter or year. These are the blocks:

- Your story is the slim card at the top, with a picture disc of the period's top covers turning
  on it. It opens a Wrapped-style story that you tap through like Instagram stories: right side
  forward, left side back, hold to pause. Its colors come from the period's top album and drift
  behind each slide. The slides are: total plays with a ticker running across; your listening
  personality (night owl, early bird, loyalist, explorer, on repeat, weekender, crate digger, genre
  hopper or all-rounder) in a turning ring of words; your top artist's photo full-screen in two
  colors; the top 5 with photos; the song of the period as a 7" single spinning at 45; the album as
  a moving cover; your golden hour on a sundial; one star per new artist; a song on repeat; your
  genres as a word cloud; vinyl; your longest streak as a row of days; and a summary printed like
  the back of a record sleeve. If you set up your setlist.fm concerts, the period's shows appear as
  ticket stubs.
- Totals shows plays, hours (worked out from the time between plays, see Time below), artists and
  new artists, each with the change from the period before. A period that's still running (this
  week, this month) is compared with the same stretch of the previous one: Monday to Wednesday
  against last Monday to Wednesday.
- Highlights describes the period in sentences: more or less than last time, whether your top
  artist held on, an artist you came back to after months, discoveries, your busiest day and hours,
  night listening, the song of the period, streaks, your biggest day and how much was vinyl.
- "Your record" draws every play as a dot on a vinyl disc. Each groove is a day, from the label
  (the first day of the period) to the rim (the last). Noon is at the top, midnight at the bottom.
  Your eight most played artists each have a color, with a legend below. To light up one artist,
  tap a name in the legend, type in the "Find an artist on the record" field, or tap near a dot.
  Tap the label to show everyone again.
- Listening over time shows plays per day, week or month, with the average marked. Tap a column
  for its numbers.
- The river shows the same thing split into your top eight artists (in the same colors as on the
  record), stacked on top of everyone else. Tap it for one day's numbers.
- Listening over time, The river and Genre drift each have an All time switch at their top right.
  It widens that one chart to your whole history and draws a frame around the period you came
  from, so you can see what came before and after without leaving the period. Tap it again to go
  back. The chart stays wide while you step through periods, and the frame moves along. A chart
  that covers several years has a tick where each year begins. On All there's no switch, because
  it already shows everything.
- "When you listen" shows plays per weekday and hour. Tap a cell for its numbers.
- Days and streaks is the period as a calendar. There's a box per day, darker for more plays. Your
  longest run of days with music is underlined, and dots mark the days when a new artist or a song
  on repeat came in. Tap a day to open its Day view. A year is shown as twelve small months (tap a
  month's name to go to that month), and all time as one box per month. Below that you see your
  longest run, your current one (counted back from today, including days before the period
  started) and the artists you played most days in a row.
- Who you played when has a row per top artist, ordered by when they peaked, with their plays as a
  curve. A word says how you played them: new (first played in this period), steady (on most of
  the days or weeks with music), a wave (half their plays within a quarter of the period) or now
  and then.
- New artists shows the artists you heard for the first time as collectible cards with their
  photo, genre and plays, the day you found them and what was playing right before. The ones you
  kept playing (ten plays or more) are foil. All opens every one of them as a list. More like them
  looks for artists you haven't played that are similar to your new ones. It works like the
  suggestions on Explore, with the reasons and with Not for me.
- Genre drift shows how your genres shift over time. Every play gets one genre. The app weighs it
  up from the Discogs styles of the record (if you own it), your other records by that artist, the
  artist's Last.fm tags and, for your most played albums, the album's own Last.fm tags. That last
  part catches an artist's electronic album among their rock ones. Tags that aren't genres, like
  "seen live", are thrown out. The six biggest genres keep their colors whatever range you pick.
- On repeat lists the songs you played over and over: seven times or more in one week, or several
  times in a row.
- Decades shows when the music you played came out, as one bar from the oldest to the newest. Tap
  a decade for its share, for Stats of that decade alone, and for the records from it on your
  shelf.
- "On your shelf, rarely played" lists your records, least played first.
- Wishlist ideas lists albums that you play a lot, across several different tracks, and that
  aren't on your shelf. Tap one to search for it on Discogs.
- In covers is a wall of the albums you played. The more plays, the bigger the cover.
- Your lineup shows the period's artists as a festival poster in the colors of the top album. Your
  most played artist is the headliner, and the names get smaller further down the bill.
- Your chart has your most played songs, albums and artists as a hit parade. Swipe sideways on the
  list to move between the three (that works here and on the whole list). Each entry shows how it
  moved since the period before (up, down, new or the same) and where it stood then ("was 3"). All
  time has nothing to compare with, so it's a plain top 10. Plays and Time, at the top right,
  switch between ranking by plays and by time listened.
- Your taste, at the end, always looks at your whole history, whatever period you picked: the
  musical atlas, you vs everyone, your concerts and the genre map.

#### Songs, albums and artists

Tap a scrobble in Activity, or a song in one of the stats lists, to open the song's page. It shows
all-time plays and rank, the rank in the current period, first and last play, its biggest day,
how much of the artist's plays it makes up, plays per month, time of day, and what you usually
play right after it. Album pages (from the Top 10, an artist's albums, or the "Your stats" chip on
a record) show the same, plus which of the album's songs you play.

Song, album, artist and record pages have "Listen on" buttons that search TIDAL and Spotify for
them. You choose the services under Settings → Listen on. YouTube Music, Apple Music and Deezer are
available too. Spotify opens in its app. TIDAL opens its search page with the match at the top.

Song, album and artist pages take their colors from the cover or the artist's photo. There's a
glow behind the header, and the charts use that color.

- Plays over time shows plays per month as a smooth curve with its peak marked. Under it, on the
  same time axis, is a barcode with one line for every week since you first played it. The more
  plays, the stronger the line. Tap it to pick a week and its month: the week lights up in the
  barcode and the month in the curve.
- "Around the clock" puts the 24 hours in a ring around the cover (on a genre page, around a
  picture disc printed with its top covers). The hours you play it most have longer wedges. Tap a
  wedge for its plays.
- A song's page also has its chart run. That's its position among all the songs you played, month
  by month, with number 1 at the top. Tap a month for its position and plays.

These pages cover all your time with that artist, album or song, whatever period the Stats tab is
on. If you open one from a period other than All, it adds that period: a tile with its plays and
rank, and on artist and album pages the songs you played most in it.

Tap a rank tile ("#3 of 735 songs") to open the whole list it comes from: every song, album or
artist of all time or of that period, by plays, with this one marked and scrolled into view. Tabs
switch between songs, albums and artists. Entries with the same number of plays share a rank. "The
whole list", under Top 10 on the Stats tab, opens the same list for the Stats period.

Album and song pages show their genres too. If Last.fm knows the album's own genres, those are
used (the app looks them up when you open the page, unless the album is already among your most
played). Otherwise you see the artist's.

##### Discographies

Artist pages show the whole discography from Deezer's catalog: albums, EPs, singles and
compilations. Different editions are folded together, so "Romance" and its deluxe edition are one
record. Each title shows your plays, a foil star if it's on your shelf and "New" if it came out in
the last two months. Titles you haven't heard are dimmed. Records of theirs that you played but
that Deezer doesn't list, such as a live album, come last under Also played.

You can filter by Most played (everything you played in one row, most played first), Not heard yet
or On your shelf. Tap a title to open its album page. That works for any album, played or not, and
shows the tracklist with your plays per song, the year and the label. Discographies are kept on
the phone for a week, so new releases show up.

A few rules decide which album a play counts for:

- An edition whose title adds words to the album's title ("Skinty Fia go deo") counts as that
  album everywhere in your stats, as long as the songs you played from it are mostly the album's
  songs. Extra versions are included.
- A song on a single or an EP (five songs or fewer played) that you also played from one of the
  artist's albums (six songs or more) counts for that album. So "More" counts for Sorry's 925 and
  "Quiet Life" for shame's Cutthroat. This never applies to a best-of or a live record. Songs that
  only the single or EP has stay with it, and the single's own card in the discography keeps all
  the plays it had.

##### Covers and photos

To find a cover, the app first searches Deezer for the album the way your player names it. If that
finds nothing, it tries again without the extras players like to add ("(Special Version)", "EP"),
and then looks for the song you played most from that album. Singles, and compilations you only
know a song or two from, get the cover Deezer has for that song. If Deezer doesn't have the album
at all, the app asks the Cover Art Archive (the cover collection of MusicBrainz).

If Deezer didn't have a cover yet for an album you played in the last two weeks, the app asks
again after a day. New albums often show up in your history before Deezer lists them.

Artist photos come from Deezer's public catalog, which needs no account. Each one is looked up once
and kept on the phone. You see them on artist pages, in the Top 10, on Explore and in the story.

Phone scrobbles don't come with a cover, so Activity takes one from your shelf (if you own the
album) or from Deezer. That is looked up once and kept on the phone too.

##### Concerts and setlists

With setlist.fm set up, artist pages have "On stage lately". It shows the songs of the artist's
usual set at their recent shows, in their usual order: songs played at about a third of the shows
or more, with encore songs last. Next to each song are your plays, and "new" marks the ones you
haven't heard.

It also lists all their upcoming shows, the next one first (as far as fans have added them on
setlist.fm; tap one for its page there), and their recent shows. All of them are ticket stubs in
rows that scroll sideways. Upcoming shows have a stronger shade of the page's color than recent
ones, and shows you went to have a color of their own.

Make a concert playlist turns the usual set into a playlist. Tap a show for a playlist of that
night's setlist. Both go to TIDAL or through Copy list, like every playlist. Fans write the
setlists on setlist.fm, so a recent show can still be missing.

To set it up, log in to [setlist.fm](https://www.setlist.fm) (accounts are free), open its
[API settings](https://www.setlist.fm/settings/api) and apply for a key. It's free for personal,
non-commercial use, and any application name will do. Paste the key into Settings → setlist.fm.

To bring in your own concerts, add your setlist.fm username there as well. The shows you marked "I
was there" on setlist.fm are imported. They are fetched again when you open Your concerts and the
last check is an hour old, when you open Explore and it's a week old, or when you tap Update now.

Your concerts show up on the artist's page under "You saw them live", under Your taste on the
Stats tab, in the period's Highlights, and as ticket stubs in the period's story. "Your concerts"
opens with your latest stubs fanned out. Then comes the afterglow: the shows that sent you back to
an artist's music the most, as bars of your plays in the four weeks before and after the show.
Below are the artists you saw most often and every show by year, as ticket stubs. Tap a show to
get that night's setlist as a playlist.

#### Search

The round button next to the tabs searches your records (by title, artist, label, year or style)
and every artist, album, song and genre (styles too) in your listening history, most played first.
A genre opens its page. On the shelf your records come first. On the other tabs, what you played
comes first. Every word you type has to match somewhere, so "fire strokes" finds Room on Fire.

Below those results, "Albums you haven't played" shows what Deezer's catalog finds for what you
typed, once you stop typing for a moment. That's how you get to an album that isn't in your
history yet.

#### Genres

Genre names are buttons: the styles on a record page, the genres on an artist page, and the legend
of the genre charts on the Stats tab. Each one opens that genre's page. It shows:

- how much of your listening the genre makes up and where it ranks, its plays and artists, since
  when you've played it and its biggest day
- your top artists (with photos), albums and songs in it
- Rediscover: artists and albums of that genre that you loved and haven't played for half a year
  or more (an artist you still play under another genre isn't listed)
- "Everyone in …": every artist you played in it
- when its music came out, its plays over time and its hours
- the records on your shelf with that genre or style
- its lineup: its artists as a festival bill with the most played at the top
- its year: the last twelve months day by day, darker on days with more of it

"Full stats for …" opens the Stats tab for that genre. The page takes its colors from the genre's
most played album. Settings → Look → Genre pages decides what sits at the top: its top albums
fanned out (Covers), a picture disc that turns as you scroll, or nothing (Plain).

#### Your musical atlas

The Musical atlas tile, under Your taste at the end of the Stats tab, opens your whole history as
a map of islands, a family tree and a world map. The first two are worked out from the order of
your scrobbles.

##### Islands

Islands shows your 150 most played artists as photos on a map. The more plays, the bigger the
photo.

Two artists are linked when you often play them back to back (in the same listening session,
relative to how much you play both). For artists you rarely play next to anyone, sharing the same
months counts instead. Linked artists form groups, and each group is an island with its own color.
It's named after its biggest artist and labeled with its main genre. Islands that have the most in
common lie next to each other, and artists that belong to no group float between them. Bridges are
artists with strong links into two islands. A dotted line connects each of them to the other
island.

Drag to move the map, and pinch or use + and − to zoom. When you tap, the nearest artist within a
finger's width is picked, so you don't have to aim. The map flies to that artist, draws its links
and opens a card that lists them as buttons. That way you can hop from one artist to the next. The
card also tells you how you found the artist and what you usually play before and after them.

You can also search for an artist, pick an island, or pick a year to light up the artists you
played then.

##### Family tree

The family tree shows who led you to whom. For an artist you discovered, it looks at what you
played right before your very first play of them, in the same session.

There's a catch. Your history only knows your first scrobble. An old favorite that you play for
the first time since your history began looks exactly like a new discovery. So the app only counts
an artist as a find when that's certain or likely. This applies to artists you kept playing (5
plays or more):

- It's a find if everything of theirs that you played came out after the year your history starts.
- It's a find if the first play came two years or more into your history.
- It's a find if the first play came a year or more into your history and you started with just a
  song or two.
- Artists from the first two weeks of your history were with you already.
- Artists from the first year whose music is at least three years older than your history were
  most likely with you already. Their page says so, and you can correct it with "No, I found them
  then".

For everyone else, the app asks you. Under the tree, "New find or old favorite?" lists them, most
played first, with Found and Knew buttons. An artist's page asks the same thing under "How you
found them". The app remembers what you say and the tree updates. "Your answers" lists what you
said so far, in case you want to take something back.

The tree shows one gateway at a time. A gateway is an artist that led you to two or more finds.
Pick one from the row above the tree, and tap a find with a number on it to keep going from there.
Finds that nothing led to are listed under Out of the blue.

"Right after" really means the very next song. With radio or autoplay, the new artist was the
app's pick. Artist pages show the chain back as far as it goes, and who they led you to.

A note on play counts: they are song plays. An album's "124 plays" means 124 scrobbles of its
songs. Record and album pages turn that into "≈ 12 times through" when the number of songs is
known.

#### Through the years

A tile under Your taste opens your whole history as charts:

- The race shows your top 10 artists by plays so far, month by month. Press play or drag the
  slider.
- Every play gives each of your 40 most played artists a row (in the order you found them) with a
  dot for every day you played them. Everyone else appears as small bars along the bottom.
- How deep you go is the largest number n for which you have n artists that you played n times or
  more. The same is worked out for songs and albums, along with how it grew each year.
- Gaps lists artists with 50 plays or more that have been quiet for 90 days or longer, and the
  longest silences that ended with a comeback.
- Old favorites are artists you still play whose plays are, on average, two years old. Newcomers
  are the ones whose plays are the newest on average.
- Milestones lists round numbers: your 10,000th play, an artist's 500th, a song's 100th.

A month also has its own race under your chart: your top 10 artists of the month by plays so far,
day by day up to today. Press play to watch the days add up, or drag the slider.

##### Time

Your chart can rank by time listened instead of plays (Time, at its top right). Scrobbles on
Last.fm don't include a length. So for every song the app scrobbles itself, it keeps the real
length as your music app or the record reports it. For any other song, the length is the usual
time between that song and the next play (Last.fm dates a play at its start). If a song was heard
only once or twice and that comes out at more than twice the artist's usual length, it gets the
usual length instead, because you probably paused after it. The hours in Totals are counted the
same way.

##### World

The atlas's World tab shows where your 300 most played artists are from, shaded by plays, with
the countries ranked below the map. Tap a country for its artists. The countries come from
MusicBrainz, which answers one artist per second, so the first time takes about five minutes. The
map fills in as the answers arrive, and they're kept on the phone.

Stats has the same map for the period you picked. It's called "Where they're from" and lists the
five countries you played most in that period. Its artists are looked up as needed.

#### You vs everyone

Last.fm knows how many people listened to every artist, album and song, and how often, over all
time. The app asks for those numbers for your 200 most played artists and 150 most played songs,
plus everyone's most played songs by your 25 top artists. It uses your API key and makes a few
lookups a second, which takes about two minutes the first time. The numbers are kept on the phone
and refreshed once a month. Other artists, songs and albums are looked up when you open their
page.

The You vs everyone tile, next to the Musical atlas tile, opens the comparison. (In Sections, Your
taste shows the first part right on the page.)

- "How mainstream your artists are" splits your plays by how many listeners the artist has (1M+,
  250K–1M, 50K–250K, 10K–50K, under 10K) and adds the line "half of your plays go to artists with
  fewer than … listeners". Tap a tier for the 60 artists in it that you played most, with their
  listeners and when you first played them. Found first sorts them by that date. Last.fm only has
  today's numbers, so the list can't tell you how big an artist was when you found them.
- "A bigger fan than most" lists artists you play many times more than their average listener
  does. The average is all their plays divided by all their listeners. A few heavy listeners pull
  it up, so most people played less. The bar is you, and the tick is the average listener.
- "Your share" is your part of all of an artist's plays on Last.fm, such as "1 in 230".
- "Favorites few people know" lists the artists you play a lot that have the fewest listeners.
- "Songs you play more than anyone" does the same for songs.
- "Your favorite vs everyone's" shows your most played song by each top artist, where it ranks on
  Last.fm, and everyone's favorite with how often you played that one.

Artist, song and album pages get a You vs everyone block (listeners, plays, you against the
average listener, your share). Artist pages also list everyone's most played songs with your plays
next to each, and dim the ones you haven't heard yet. The story gets a slide with your top artist.

#### Explore

Explore helps you find something to play next. It's made of rows. Each list is one row that you
scroll sideways, with its playlist button next to the heading. Tap a heading, or the All card at
the end of a row, to open the whole list (up to 100 entries) with more detail: when you last
played an artist, when they were big for you, how many plays.

At the top are the mixes, side by side: the discovery mix, Songs like your favorites, Crate dig,
New releases (songs from what your artists put out lately, from Deezer) and On this day. After
that come the suggestions, the new releases and the other lists from For you and New releases
(both described below), and then the lists to rediscover. In the full On this day list, tap a year
to see that day's stats.

If you set Settings → Look → Explore to One long page, the lists come one after the other in full
size. The views of your whole history (the atlas, you vs everyone, your concerts and the genre
map) aren't on Explore. They're under Your taste at the end of the Stats tab.

#### For you

For you starts with the 25 artists you play most. Recent plays count the most: a play counts half
as much after four months. For each of those artists, the app asks Last.fm which artists its
listeners find similar, and Deezer which artists its listeners play alongside. If both name the
same artist, that counts for more than one of them alone. A suggestion that several of your
artists lead to ranks high, and artists you play a lot have more say. Artists you've already
played or have on your shelf never show up.

The first run takes a minute or two. After that it runs again once a week, when you open Explore.

- Artists you might like shows the best ones as a row. All opens the full list of up to 40, each
  with the reason ("Because you play Shame, IDLES and Fontaines D.C."), their genre and how many
  fans they have on Deezer. Close stays near your taste. Further out prefers lesser-known artists
  and also looks one step further, at who is similar to your best suggestions. Tap a suggestion to
  see which of your artists lead to them and how strongly, plus their best-known songs with a
  playlist of those and a link to their artist page. The artist page of someone you haven't played
  shows their best-known songs, their discography and their shows.
- Not for me (the ✕ on a row, or in the sheet) removes an artist for good, and the artists of
  yours that led to them count a little less from then on. Undo takes it back right away. Settings
  → Stats and listening → Suggest them again brings back everyone you turned down.
- The discovery mix is 30 songs you haven't played: the two best-known songs of each of 15
  suggested artists, never the same artist twice in a row. It stays the same all week and changes
  on Monday. Make another picks other artists and songs. Like every playlist, it can go to TIDAL.
- Songs like your favorites (in the top row) is 30 songs you haven't played that Last.fm finds
  similar to your ten most played songs of the last month. The closest ones come first, with at
  most two per artist.
- Because you've been playing… rows are based on the artists that filled your last two weeks (ten
  plays or more). Each row has artists similar to that one artist, without the ones the main row
  already shows.
- Albums to start with has the best-known album on Last.fm for each of your best suggestions
  (never a live record or a best-of). A suggestion's sheet shows it too.
- New from artists you might like has what your best suggestions released lately.
- Suggestions you took up lists the artists suggested here that you've played three times or more
  since. Each of them makes the artists of yours that led there count a little more in future
  suggestions.

#### New releases

New from your artists shows what came out in the last eight weeks from the artists you've played
15 times or more or have on your shelf, up to 300 of them. The 40 you care about most (based on
all your plays, with recent ones counting more, and your records of theirs) are checked on Deezer
every day. The others are checked once a week, when you open Explore. The first run takes a few
minutes, and the row fills up as it goes.

- There's one entry per title. A deluxe edition of an old album doesn't count as new, and singles
  released up to five months before an album or EP are folded into it. Compilations are left out.
  Live records and remixes are labeled.
- The ones you haven't played yet come first, then the artists you care about most, albums before
  EPs before singles, and the newest.
- Coming soon shows what's due in the months ahead, with the singles that are already out. For
  your 40 artists the app also asks MusicBrainz once a week, to catch albums that were announced
  before Deezer lists them.
- Your old favorites are back shows new releases from artists you used to play a lot and haven't
  played for half a year or more.
- Tap a row's heading for its full list. The first release is shown big with the reason it's there
  (how much you play the artist, the singles or EP before it, your plays of it so far), and the
  rest as a grid of covers. The ✕ on a cover hides that release for good. Undo takes it back.
- Albums you're missing shows albums by your 30 most played artists that you've never played and
  don't own, as long as you know at least one of their albums. Live records, remixes and best-ofs
  don't count, and neither does a longer edition of an album you know. This is checked weekly.

Settings → Stats and listening → Suggestions on Explore turns all of this off, including the
lookups.

#### Lists to rediscover

These lists collect the listening that never makes it into your top lists:

- Lost favorites are artists you played a lot that have gone quiet for half a year or more, with
  the month they were biggest for you.
- Albums to rediscover are albums you played through (several different songs) and then left.
- Quiet regulars are artists outside your top ones that keep coming back month after month.
- One-day wonders are things you played all day once and never again.
- Deep cuts are songs by your favorite artists that you've only played once or twice.
- On this day shows what you played on today's date in earlier years.

Tap a list's heading to open it in full. The first entry is shown big with the reason it's on the
list, and the rest as a grid of covers (or photos, for artists). The Playlist button next to the
title makes the playlist. The full Artists you might like list looks the same, with your best
match first.

#### Playlists

Each list can become a playlist of up to 40 songs. So can Crate dig, which is songs you played a
few times and then forgot, shuffled differently every time.

Highlights are playlists of what you played most:

- Of a period: Make a playlist under the top list on the Stats tab uses the period Stats is
  showing. The Highlights card at the top of Explore starts with last year. On the playlist itself,
  Month, Year and All time pick the kind of period, and ‹ and › step to the one before or after.
  That way you can go back through the years, or to one particular month, without leaving the page.
- Of an artist: Playlist next to Top tracks on their page takes the songs of theirs that you
  played most, over all your time with them. A song you played in several versions is in it once.
- Songs sets the length: 10, 20, 30, 50 or 100 songs. The line under the name tells you how long
  it runs.

A playlist shows each song with its album's cover (or the artist's photo, for songs you haven't
played). Its first four covers make up the playlist's picture.

- Copy list puts it on the clipboard as "Artist - Title" lines. You can paste those into
  TuneMyMusic or Soundiiz (choose import from text) to send the playlist to TIDAL.
- Share hands the playlist to any app.
- The pencil next to the playlist's name lets you give it your own name and description. TIDAL
  gets both, and Share puts them above the songs.

#### Artist pages and cards

Tap any artist (in the lists, the phases, the record, or on a record page) to open their page. It
has their plays, rank, first play, busiest month, longest streak, plays per month, time of day,
their plays lit up on your record, top tracks, their discography and the records of theirs on your
shelf. Top tracks look like Your chart on the Stats tab, each song with its album's cover. The
first five are shown, and Show all opens the rest.

Your cards shows their songs as collectible cards with the album cover. A song is bronze at 10
plays, silver at 25, gold at 50 and holo at 100, and each card says how many plays it still needs
for the next tier.

A song's own page wears its card too. From bronze on, the cover sits in a frame of that metal with
a soft glow behind it. If the album is on your shelf, the record that slides out is pressed in the
same metal. The line under the numbers tells you how far away the next tier is. Tap the cover to
turn it over and see the card: its plays, the way to the next tier, the month it reached each
tier, and a link to All your cards. A song with fewer than 10 plays tells you how many more it
needs to become a card.

All your cards are in one binder. You open it with Your cards on the Stats tab (under the
numbers) or with All your cards on an artist page. It has two sides, Albums and Songs. It opens on
Albums, unless you come from an artist page or from a song's card, in which case it opens on
Songs.

- Each side counts how many cards you hold in each tier and says where the tier begins.
- It shows the cards that are closest to their next tier, and the songs or albums that are close
  to becoming a card at all.
- The cards are laid out by tier, most played first. A tier shows its first nine. Show more brings
  the rest, 90 at a time.
- New this month shows the cards that reached their tier this month.
- By artist sorts by artist instead, starting with the artists that have the most cards, 20 at a
  time.
- Tap a tier's count to jump to it, and a card to open its page.

On the Songs side, three or more songs from one album in the same tier (or by one artist, in By
artist) are stacked as one pile with the album's cover and the number of songs in it. That way an
album you played a lot takes one spot instead of a dozen. Tap a pile and its songs spread out under
its row, each as its own card, with the album's name linking to its page. Tap the pile again, or
the ✕, to stack them up again.

Albums get cards for how many times you played them through: bronze at twice, silver at 5 times,
gold at 10 and holo at 20. Only songs played as an album count. That means three or more of its
songs in a row (each within 20 minutes of the one before), measured against the songs of the album
that you know. One song on shuffle doesn't add anything. A side of a record counts as half a time
through if it has half the songs.

Album and record pages wear their card the same way a song's page does: the cover in the tier's
frame, a line under the title with the times through, and the card on the back of the cover. A
record on your shelf also gets the card as a sticker.

Records on your shelf also have a badge for what they are right now: heavy rotation (your most
played of the last two weeks), new arrival (added this month), lost favorite (one of your most
played, but quiet for half a year), worn in (your three most played) or still sealed (never
played). The badge shows on the cover on the shelf, next to the card under the record's title, and
as a sticker on its card in the binder.

#### Tidy up: videos and app sounds

A scrobbler that listened to every app may have sent things that aren't music: "HBO Max", a game
trailer from YouTube, a short clip that played on loop. If your history has scrobbles like that, a
banner on the Stats tab opens Tidy up stats (you can also find it under Settings → Stats and
listening). It has three lists:

- "Look like videos or apps" has tracks without an album that are named like an app, have a title
  that looks like a video (trailer, episode, "Title | Channel"), have the artist's name as their
  title, or were scrobbled again every few seconds. Hide them one by one or all at once, or tap
  Keep on the ones that are music after all.
- "Most played without an album" is probably music, but worth a look.
- "Hidden" has everything you hid, with Show to bring it back.

You can also hold any song or artist, in any list, to hide it. A sheet offers to hide the song or
everything by the artist. For a song it can also start a Fix names rule with the song's name
already filled in. The hide button at the top of an artist's page hides the whole artist.

Hidden plays are left out of all the stats, the story and the artist pages. Nothing changes on
Last.fm, because apps aren't allowed to delete scrobbles there. If you want them gone from Last.fm
too, open a hidden track (↗) and delete its scrobbles from your library on the website.

#### How plays are counted for artists and songs

Features and collaborations count for both artists wherever that makes sense: on each artist's
page, in the top artist lists and in search. In places where a play can only go to one of them
(the river, the colors on your record, shares of your listening), it goes to the main artist:

- For "feat.", that's the first name.
- For "A, B", "A & B", "A with B" and similar, it's the artist whose album the song is on (meaning
  they have songs of their own on it). If that doesn't settle it, it's the one you play more on
  their own.

So "ROSALÍA, Fontaines D.C." on a Fontaines D.C. album counts for Fontaines D.C., with ROSALÍA
credited too. "Earth, Wind & Fire" stays one band, because you don't play any of those names on
their own. The single follows the album: "The God of Lying" by "Gorillaz, IDLES" is on a Gorillaz
album, so plays of the single count for Gorillaz too.

A song with a guest in its title is the same song as the one without. "The God of Lying" and "The
God of Lying (feat. IDLES)" are counted together, and the title you see is the one you played last.

Sometimes the same artist is in your history under two names. Bands rename themselves (Slaves
became Soft Play), and your music service then renames everything it has, while your history still
has the old name from when you played it. The app treats two names as one band when they share at
least two albums with the same songs and almost all plays under the old name happened before the
first play under the new one. The band then shows under its new name.

The other case is a name that is only written differently, like Damian Marley and Damian "Jr. Gong"
Marley, or Beginner and Absolute Beginner. If you played the same song from the same album under
both, they are merged. You see the name that mentions the fewest artists, and if that's a tie, the
one you played more.

Either way the old name still works in search, and records on your shelf under the old name keep
their plays. Artists that only happen to share an album title are left alone, and so are a cover
version of a whole album and a compilation two artists are both on.

Days, weeks and hours are in the phone's local time.

### Your TIDAL history, from before Last.fm

Last.fm only knows what was scrobbled to it. TIDAL knows everything you streamed there, and you
can ask TIDAL for a copy of your data. It comes as a folder that contains a file called
`streaming.csv`, with one row per stream. In Settings → Your data → TIDAL history, tap Import
streaming.csv and pick that file (from the phone or from a cloud folder). Its plays are added to
your stats: the years before your Last.fm history starts, and plays since then that never reached
Last.fm.

- A stream counts as a play by Last.fm's rule: at least 30 seconds, and half the song or four
  minutes. The file doesn't say how long a song is, so the app uses the length most of its streams
  ran for (or what this phone knows from scrobbling the song).
- A play's time is when it started. Streams that TIDAL reported in one go after you listened
  offline all carry the same time. The app lines them up end to end before that time, so the day
  is right and the hour is close.
- If Last.fm has the same song within ten minutes (within half a day for those offline plays), the
  play counts once, as Last.fm's. This is checked again whenever older scrobbles come in.
- Videos are left out, and rows that appear twice count once. Fix names applies to imported plays
  just like it does to scrobbles.
- The plays stay in this app. Last.fm doesn't accept plays older than two weeks, so your Last.fm
  pages don't change, and the history check keeps comparing only what Last.fm has. Backups include
  them.
- You can import a newer file at any time. It replaces the one before. Remove takes the plays out
  again.

The file also contains the address and location of every stream. The app reads the song, album,
time and length columns and nothing else, and the file isn't copied or sent anywhere.

### The look

Headings and big numbers are set in Fraunces, a soft serif. Labels and details are in DM Mono,
like liner notes. Both fonts are bundled under the SIL Open Font License, in `assets/ui/fonts`. A
faint print grain lies over everything.

Behind the app glows the color of what's playing right now (the record that's spinning, or the
song on the phone), or otherwise of the last thing you played. The glow runs from the top of the
screen to the bottom, under the bar. In the Tinted soft style the tabs take a calm shade of that
color too. Settings → Look → Background color lets you choose Your records, Wallpaper (your
Material You colors) or Off.

#### Pages in tabs

Artist, album and genre pages are split into tabs, each a screen or two long. Settings → Look →
Pages switches them to One long page.

- An artist has Overview (the numbers, the period, the charts, how you found them, you vs
  everyone), Music (top tracks, their albums over time, the discography, your shelf, their most
  played songs) and Live (the shows you went to, and what they play lately).
- An album has Overview (the numbers, the period, when it clicked, the charts, you vs everyone)
  and Songs (the tracklist, track by track, and its songs over time).
- A genre has Overview (the numbers, the styles in it and the charts) and Music (its artists,
  albums and songs, records to rediscover, decades and your shelf).

The tabs stay at the top while you scroll. Swipe sideways to go to the next tab. The tabs lie side
by side and are already drawn, so the next one follows your finger and slides into place when you
let go past a third of the way, or when you flick. Rows that scroll sideways and charts you can
scrub keep their own swipes. In an artist's Live tab the setlist comes in two columns, and the
shows scroll sideways as ticket stubs.

#### The shelf

The number of records on the shelf sits next to its title. The line under the title has the sort
order (tap it for the others: recently or first added, artist A–Z or Z–A, album A–Z or Z–A, year,
plays), the filter button and three views: the grid of covers, big covers two to a row with year
and plays, and a list with year, label, format, two styles and plays.

When the shelf is sorted by artist, album or year, it gets a heading for each letter or decade. A
long shelf also shows an index down the right edge while you scroll, with the section you're in
marked. Drag along it to jump to a letter or a decade. Where space is tight, every other entry is
a dot.

#### What's playing

Whatever is playing right now, on the turntable or in a music app on the phone, is shown as a
sleeve with the record half out of it, turning while it plays. Settings → Look → Now playing puts
it in one of three places:

- On the shelf is a line at the top of the shelf, with the progress. On Stats and Explore, a small
  pill next to the title shows the song with its record turning, and a thin line at the very top
  shows how far along it is.
- In the corner is the record peeking out above the search button, on every tab.
- In the bar turns the bottom bar into a bigger pill with the cover, the song, play and pause and
  the progress, above the tabs.

The tabs, the search button and the bar stay at the bottom of every page (except Now playing and
Now spinning). Tap a tab to go straight back to it, however many pages deep you are. Search closes
the pages the same way.

The lyrics button in the bar swaps the bar's content for the line that's being sung, one line at
a time on up to two lines. That works for a song on the phone and for a record's track. Play and
pause make room for it then (they're one tap away, on Now playing), and the screen stays on while
the lyrics are showing. The lyrics button always stays at the right end, with play and pause to
its left, so switching the lyrics on and off quickly never pauses the song by accident.

Tap the pill, the record or the bar to open Now spinning or, for the phone, Now playing. Now
playing has:

- the song's exact position (drag to seek), play/pause and skip
- what the app plays next, with the name of the playlist or mix, if the music app shares its queue
  (apps that work with Android Auto usually do)
- the whole album the song is on, with how much of the album is left (tracklists come from Deezer)
- the song's lyrics from LRCLIB

Timed lyrics light up line by line as the song goes, and you can tap a line to jump there. While
the lyrics of a playing song are open, the screen stays on. If you'd like it to turn off as usual,
switch off Keep the screen on, right on the lyrics. The lyrics lie on top of the song's cover,
blurred and darkened. The arrow next to them lets the lyrics fill the screen in bigger type, with
the song in one row above them (cover, title, play and pause). The arrow or the back gesture makes
them small again.

Tap the music app's name to open that app on its own player screen. The chart button at the top
opens the song's stats.

#### Backgrounds and styles

Record, album, song and artist pages and Now spinning use their cover or photo as a background.
The picture is shrunk to a few pixels once and then stretched, which is what makes the blur, so
nothing has to be recalculated while you scroll. A veil in the page color on top of it keeps the
text readable.

Settings → Look → Style gives you two styles:

- Tinted soft, the default, is a soft UI. The page takes a calm shade of the cover's color.
  Buttons and cards are raised from it with light and shadow, and pressed ones sink in. The cover
  glows behind the whole page under a veil of that shade, which gets denser further down.
- In Soft glass, the blurred cover lies behind the whole page. Buttons and cards float above it,
  frosted, with a soft shadow and a lit edge.

Both styles give each page one solid button and color its links like the cover. Top bars scroll
away with the page and come back over it, on a soft fade of the page's color, as soon as you
scroll up. Shelf, Stats and Explore sit in a floating bar at the bottom, and the page fades out
underneath it. Activity, the list of what was scrobbled, opens from the clock button at the top.
That button shows a number when scrobbles are waiting.

#### Animations

The animations show you where things come from. A pill slides to the tab, view or option you
picked. Pages come in from the side and leave the same way. A record's cover flies from the shelf
up to its page and back. Sheets rise from the bottom and can be dragged down. Search grows out of
its round button.

Android's back swipe shows where it's going. From Android 14 on, the page (or sheet, or search)
follows your finger and closes when you let go, or springs back if you change your mind. On the
shelf, when there's nothing left to close, back is Android's own, with its preview of leaving the
app on phones that show one (Android 15 does by default).

The app icon is a record whose groove is one spiral in the app's colors, winding in toward the
label. On Android 13 and later it can follow your wallpaper colors (themed icons).

#### Covers and sleeves

Your records show the album's usual cover (from Deezer) everywhere in the app. That's the one you
know the album by. Your own pressing can look quite different, for example a plain white sleeve.
Under the sleeve on its record page, Usual cover and Your copy let you pick which of the two the
record shows, on the shelf and everywhere else. If Deezer has no usual cover, the record shows
your copy's picture from Discogs.

On a record page, or on the album page of a record you own, tap the sleeve to turn it over. (A
song page turns over to the song's card instead, see Artist pages and cards.) The record rolls
back into the sleeve first, and rolls out again after you turn it back. The back shows the album's
card, like on every album: how many times through, the way up from bronze to holo, and when it
reached each tier. As long as your history has no card for it yet, the back shows the tracklist by
side, the label and format, a barcode, and your plays stamped on it.

Now spinning shows the record on a turntable as a picture disc. The cover fills the whole side
under the grooves, with a ring where each song of the side begins. The platter turns while a track
plays and stops where it is when the music stops. The tonearm moves from the outer groove toward
the middle as the side goes on, so the needle always sits in the band of the song that's playing.
The turntable takes on the record's colors: its body is a deep shade of the cover's main color,
and the speed button and the headshell are its brightest.

### Moving covers

Big covers tilt and catch the light as you move the phone. Records on your shelf also get holo
foil wherever they show up (record page, Now spinning, album and song pages, the story), and the
record slides out of its sleeve and spins while a side plays. Albums you don't own only tilt.
Artist photos sit in a frame that tilts too.

The collector cards catch the light as you scroll. A glint runs across each card on its way up the
screen (and across the cards of a row as you swipe the row), and holo and foil cards get a rainbow
one as well. The rainbow is kept faint over the picture, because the card itself is already
rainbow-colored and you should still be able to see the cover. The glint follows your finger and
rests when the page does.

The motion sensor only runs while a tilting cover is on screen. You can switch both effects off in
Settings → Look → Moving covers.

The "plays on Last.fm" chip on a record page opens your Last.fm library for that album (in the
Last.fm app, if it's installed).

## Scrobbling a record

Tap a record and then Scrobble, or tap the Scrobble button next to a side, or tap a single track.
First choose what you're playing: a side, the whole record, or individual tracks. Then choose
when:

- "Starting now" times the tracks from now. Last.fm shows "now playing", and each track is
  scrobbled once half of it (or 4 minutes) has played, which is Last.fm's own rule. When the side
  ends, a notification offers to start the next side.
- "Earlier" lets you pick the start date and time ("I played this last night").
- "Just finished" scrobbles everything right away, ending now.

If the timing drifts away from the record, tap the track that's actually playing on the Now
spinning page. The times after it shift.

Now spinning has the lyrics of the current track, from LRCLIB, lit up line by line along the
schedule. The screen stays on while they're showing (Keep the screen on switches that off). The
arrow next to them lets them fill the screen, like on Now playing. Tap the line that's being sung
to sync: the track is at that line now, and the tracks after it shift with it. The track's title
opens its song page, and the artist and the album open theirs.

When the last track of a side ends, the record stays up for 15 minutes so you can turn it over and
tap Flip (3 minutes if it was the last side), unless the phone starts playing something else. When
the next side starts, the record on the turntable turns over. If the next side is on another
record of the set (side B to side C), the record flies off and the next one comes in.

When you're offline, scrobbles wait on the phone and go out by themselves once you're online
again (also after a reboot). Activity, the clock button at the top, shows what was sent, what's
waiting, and what Last.fm ignored and why.

Last.fm silently ignores scrobbles that are older than about two weeks. The app won't let you
scrobble a listen that old, and warns you when waiting scrobbles get close to the limit.

### An album that isn't on your shelf

You can scrobble an album by hand that you heard somewhere the app can't see, like a first-listen
video or a friend's stereo. Find it in search, open its page and tap Scrobble a listen. The button
appears on any album that isn't on your shelf, as soon as Deezer's tracklist has loaded. Choose
all tracks or some of them, and when: "Just finished" ends the album now, and "Earlier" takes the
date and time you started. The tracks are sent at once, each with the time it played, using
Deezer's track lengths.

The listen has to be over already (you can't follow along while it plays) and no more than two
weeks ago. In Activity these scrobbles are marked "By hand".

### Filtering the shelf

The filter button next to the sort order narrows the shelf by Decade or by "Genre or style"
(Discogs genres like Rock, or styles like Post-Punk). Each option shows how many records it has.
Active filters are listed under the line, each with its ✕. A summary card then describes the
selection: records, artists, Last.fm plays, never played, the most played, and how it splits by
decade or style. For a genre, "Your … listening" opens the Stats tab for that genre.

On the Stats tab, the filter button next to Week, Month, Year and All offers One genre. Everything
on the tab and in the story then only counts the plays whose main genre is that one (the same
genre the genre charts count them under). The One decade filter does the same for when the music
came out, and you can combine the two ("Post Punk from the 1970s"). The Decades chart shows the
period by decade of release, and you can tap a decade to filter.

Only vinyl keeps just the records you scrobbled here from the turntable, with either filter or
both ("Post Punk from the 1970s on vinyl"). The full top lists behind the charts count the same
plays. Records you played before you used the app, or scrobbled some other way, look just like
streams on Last.fm, so they aren't included.

Release years come from Deezer, one artist's discography at a time. A title's year is the year it
first came out (remasters and deluxe editions come later), or the year of your own record if
that's earlier. The app looks up the years in the background for the albums behind most of your
plays, and again for new ones every few hours. The decade sheet tells you how much of your history
has a year. Songs that were scrobbled without an album have none.

### Track lengths

Discogs often has no track lengths. The app fills the gaps in this order: the MusicBrainz release
that's linked to that exact Discogs release, Last.fm's album info, a MusicBrainz search by artist
and title, and finally a default length that you choose in Settings (3:30 unless you change it).
Guessed lengths show as "≈3:30" on the record page. "Look up lengths again" retries the lookups.

### "Most played"

The play counts on the shelf are your all-time album plays on Last.fm, so they include streaming
(TIDAL through a phone scrobbler, for example) as well as vinyl. They refresh after a sync, and at
most every 12 hours.
