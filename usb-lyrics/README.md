# USB Lyrics

An Android app that downloads lyrics for the songs on a USB drive (or any folder on the phone) and saves them as `.lrc` files next to each song. Car stereos, head units and USB music players that support lyrics read `Song.lrc` when it sits beside `Song.mp3`.

Lyrics are fetched on the phone as the app runs; none are bundled.

## Lyrics sources

Each song goes through these sources in order, stopping at the first timed (synced) match. Each can be turned off in the **Lyrics sources** card:

1. **Lyric files already on your drive**: a matching `.lrc` kept elsewhere in your picked folders (a `Lyrics` folder, files from another player) is copied next to the song.
2. **[LRCLIB](https://lrclib.net)**: free, open, keyless, with timed lyrics. Tried as an exact lookup, then field searches, then a free-text search, then title-only when the song's length can rule out same-named songs.
3. **[lyrics.ovh](https://lyricsovh.docs.apiary.io)**: free, keyless, unsynced only. Used when LRCLIB has nothing and unsynced lyrics are allowed.

Every step is retried with cleaned-up tags: `Song (Remastered 2011)`, `Song - Live at X` and `Song (feat. Y)` are also tried as `Song`, and `Artist A & Artist B` as `Artist A`. The full artist is always tried first, so band names like AC/DC aren't split. Results whose length is more than 15 seconds off the song's are rejected, as are results from a different artist.

**Choose match** in the bubble lists every candidate from every source, with its length, whether it's synced, and where it came from.

## USB Audio Player PRO companion

USB Lyrics can run alongside USB Audio Player PRO (UAPP). Each time UAPP starts a new song, it:

1. reads the song's title, artist, album and length from UAPP's now-playing info,
2. finds that song's file on your drive,
3. looks the lyrics up on LRCLIB and saves `Song.lrc` next to `Song.flac`, which is where UAPP reads lyrics from.

A floating bubble over UAPP shows what happened: ✓ green when the song has lyrics, ! amber when none were found or the file couldn't be located. Tap it for details, **Find lyrics** to check again, or **Choose match** to pick a different LRCLIB result (shows each result's length next to your song's, so the right recording is easy to spot). Drag the bubble anywhere.

Songs that already have a `.lrc` are left alone. If UAPP doesn't show lyrics that were just saved, restart the song.

### Setup (one time)

Open USB Lyrics; the **USB Audio Player PRO** card walks through four steps:

1. **Notification access.** Android only lets apps read another player's now-playing info through this. On Android 13+ the switch is greyed out for apps installed from an APK: open **App info**, tap ⋮, choose **Allow restricted settings**, then try again.
2. **Display over other apps**, for the bubble (optional; turn the bubble off and you get a short toast instead).
3. **Music library access**, so the app can find the song's file from its tags.
4. **Music folder.** Pick the USB drive (or its music folder) in the picker. Saving needs this; you can add several folders.

Switches: get lyrics automatically for each song (on), show the floating bubble (on), and also work with other music players (off).

### How the song file is found

Players share tags, not file paths, so the app tries, in order: a path the player exposes, Android's media library matched on title/artist/album/length, then a file-name search of your picked folders (checking length when several files share a title). Files must be inside a folder you picked.

## Install

Copy `USBLyrics.apk` to your phone and open it (allow "Install unknown apps" for your file manager when asked). Android 10 or newer.

## Whole library

To do a whole drive at once instead of song by song:


1. Plug the USB drive into the phone (USB-C or OTG adapter).
2. Tap **Pick folder**, choose the drive in the picker's side menu, open the music folder and tap **Use this folder**. Subfolders are included.
3. Tap **Get lyrics**. Each song gets a `.lrc` next to it; the log shows ✓ saved, ✗ not found.

Options:
- **Replace existing .lrc files**: off by default, so songs that already have lyrics are skipped.
- **Save unsynced lyrics when no timed version exists**: on by default. Turn it off if your player only handles timed (`[mm:ss.xx]`) lyrics.

**Single song** looks up one song by artist and title and saves `Artist - Title.lrc` into the picked folder.

## How songs are matched

The artist, title, album and length are read from the file's tags. Files without tags fall back to the file name (`Artist - Title.mp3`, `01 - Artist - Title.mp3`). The app tries LRCLIB's exact lookup first, then a search, preferring timed lyrics and a recording whose length is within 15 seconds of the file's.

Supported audio: mp3, flac, m4a, aac, ogg, oga, opus, wav, wma, alac, aiff, ape, wv, dsf, dff.

## Build

```sh
ANDROID_HOME=/path/to/sdk ./build_apk.sh
```

Needs a JDK and the Android SDK build-tools and a platform (API 35 by default). Writes `build/USBLyrics.apk`, signed with the debug keystore in `../keystore/`.
