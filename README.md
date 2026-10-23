# Navidrome for Poweramp

An Android plugin that puts your [Navidrome](https://www.navidrome.org/) library inside [Poweramp](https://powerampapp.com/). Your server's songs show up in Poweramp next to your local music, with tags, covers and lyrics. They stream from your server, stay on the phone once played, and keep working offline. Crossfade, the equalizer and everything else in Poweramp work as usual.

It uses Poweramp's official [Track Provider API](https://github.com/maxmpz/powerampapi/tree/master/poweramp_provider_example), so there is no hack or patched Poweramp involved. Any Subsonic/OpenSubsonic server should work; it is tested with Navidrome 0.64.

## Features

- **Full quality.** Songs stream as the original files (FLAC, Opus, MP3, AAC…), byte-identical to the server. Hi-res FLAC included.
- **Crossfade and gapless.** Poweramp plays these songs like local files, and the next song is downloaded ahead so transitions stay smooth.
- **Cache that keeps.** A song you've played stays on the phone until you clear it, even if it's later deleted from the server. An optional size limit removes the least recently played songs.
- **Offline albums.** Pick albums (or the whole library) to keep on the phone. Optionally only over Wi-Fi.
- **Playlists and radio.** Your Navidrome playlists and internet radio stations appear in Poweramp's Playlists and Streams.
- **Tags, covers, lyrics.** Metadata comes from the server, so Poweramp never has to read the files. Synced lyrics (LRC) show in Poweramp's lyrics view.
- **Scrobbling.** Plays are sent to Navidrome, which shows "now playing" and forwards to Last.fm or ListenBrainz if you linked them. Offline plays are sent later.
- **Likes and ratings.** Like in Poweramp = ❤ and 5 stars in Navidrome; 2–4 stars = that many stars; unlike = 1 star; no rating clears both.
- **Lower quality on mobile data (optional).** Opus at 96, 128 or 192 kbps, on mobile data only or always. These copies are temporary and never replace the originals in the cache.
- **Kept apart from local music.** Navidrome songs live in their own "Navidrome" folder in Poweramp. An optional suffix on album names keeps them from merging with local albums of the same name.

## What it does to your server

Nothing it shouldn't. The app only reads your library, plus three calls that are the point of the features above: `scrobble`, `star`/`unstar` and `setRating`. Deleting or editing tags in Poweramp never reaches the server; Poweramp reports the action as failed and the song stays.

Your password is not stored. The app keeps the Subsonic token (an MD5 of password + random salt) and the salt, as the Subsonic API requires.

## Install

1. Download the APK from the [latest release](../../releases/latest) and install it. Android 8 or newer, Poweramp v3 (tested with build 1031).
2. Open **Navidrome for Poweramp**, enter your server address, user and password, and wait for the first sync.
3. Tap **Add to Poweramp Music Folders** and tick **Navidrome**. Poweramp scans it in a few seconds without downloading anything.
4. Play. After changes on the server, open the app and tap **Sync library**; Poweramp rescans by itself.

Turn off battery optimisation for the app (there's a button) so downloads and scrobbles aren't cut off in the background.

## Verifying the APK

Every release is built by [GitHub Actions](.github/workflows/build.yml) from the tagged source in this repository. Nothing is built on my machine.

- **Signing key.** Releases are signed with my own release key. It exists only in this repository's GitHub Secrets (where the workflow reads it) and in a private backup, and never in the source. The certificate's SHA-256 fingerprint is

  ```
  AB:86:FE:6A:36:1D:B6:00:45:F3:39:3D:99:36:1B:1A:13:62:AF:AE:6D:CA:B2:AA:FF:0C:F2:0D:1E:98:03:A1
  ```

  The workflow refuses to publish an APK signed with anything else. Check a download yourself with `apksigner verify --print-certs navidrome-for-poweramp-<version>.apk`. Android also refuses to update the app with an APK signed by a different key.
- **Build provenance.** Each release APK has a signed [GitHub attestation](https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations) linking it to the exact commit and workflow run that built it:

  ```bash
  gh attestation verify navidrome-for-poweramp-<version>.apk --repo ayhamo/poweramp-navidrome
  ```

- **Checksum.** Each release also lists the APK's SHA-256 (`.sha256` file).

## How it works

Poweramp asks the plugin (an Android `DocumentsProvider`) for folders and tracks. The plugin answers from a local copy of your library, made by the sync, so Poweramp's scan needs no network. When Poweramp plays a song:

- if it's on the phone, Poweramp gets the file directly;
- if not, the plugin downloads it and hands Poweramp a stream that fills as the download runs. Seeking past the downloaded part fetches that range directly. The finished file goes into the cache.

Covers and lyrics are fetched once and kept. Poweramp's track-change events drive scrobbling and rating sync, so the plugin doesn't need to run in the background otherwise.

## Building from source

The build needs only Docker:

```bash
powershell -File build.ps1            # debug APK: app/build/outputs/apk/debug/app-debug.apk
```

Or with JDK 17, the Android SDK (platform 36) and Gradle 8.14: `gradle assembleDebug`. A release build is unsigned unless `SIGNING_KEYSTORE` and `SIGNING_PASSWORD` are set.

## Known limits

- Poweramp merges albums with the same name in its Albums view (local and Navidrome copies of an album, for example). Use the album suffix option or untick your local folder.
- Poweramp doesn't crossfade between songs with different sample rates (e.g. a 96 kHz FLAC into a 48 kHz Opus).
- After a song is retagged on the server, Poweramp shows the new tags but keeps the old file name in its "show file names" mode.

## Credits

- [Poweramp API](https://github.com/maxmpz/powerampapi) by Maksim Petrov. `poweramp_api_lib/` is a copy of its library, under its own [license](poweramp_api_lib/LICENSE.txt).
- The idea came from a Subsonic provider for Poweramp shown on Reddit, which is no longer public.
