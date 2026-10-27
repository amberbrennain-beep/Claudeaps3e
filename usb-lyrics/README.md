# USB Lyrics

An Android app that downloads lyrics for the songs on a USB drive (or any folder on the phone) and saves them as `.lrc` files next to each song. Car stereos, head units and USB music players that support lyrics read `Song.lrc` when it sits beside `Song.mp3`.

Lyrics come from [LRCLIB](https://lrclib.net), a free, open lyrics database with no account or API key.

## Install

Copy `USBLyrics.apk` to your phone and open it (allow "Install unknown apps" for your file manager when asked). Android 8.0 or newer.

## Use

1. Plug the USB drive into the phone (USB-C or OTG adapter).
2. Tap **Pick folder**, choose the drive in the picker's side menu, open the music folder and tap **Use this folder**. Subfolders are included.
3. Tap **Get lyrics**. Each song gets a `.lrc` next to it; the log shows ✓ saved, ✗ not found.

Options:
- **Replace existing .lrc files**: off by default, so songs that already have lyrics are skipped.
- **Save unsynced lyrics when no timed version exists**: on by default. Turn it off if your player only handles timed (`[mm:ss.xx]`) lyrics.

**Single song** looks up one song by artist and title and saves `Artist - Title.lrc` into the picked folder.

## How songs are matched

The artist, title, album and length are read from the file's tags. Files without tags fall back to the file name (`Artist - Title.mp3`, `01 - Artist - Title.mp3`). The app tries LRCLIB's exact lookup first, then a search, preferring timed lyrics and a recording whose length is within 15 seconds of the file's.

Supported audio: mp3, flac, m4a, aac, ogg, oga, opus, wav, wma, alac, aiff, ape, wv.

## Build

```sh
ANDROID_HOME=/path/to/sdk ./build_apk.sh
```

Needs a JDK and the Android SDK build-tools and a platform (API 35 by default). Writes `build/USBLyrics.apk`, signed with the debug keystore in `../keystore/`.
