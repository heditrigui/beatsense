# BeatSense

Hold up your phone and hear the tempo. BeatSense listens to music through the microphone and tells you its **BPM** within a few seconds.

- **Live BPM detection** that locks in within about 6 seconds on songs with drums, with a pulsing dial that beats in time with the music
- **Tempo range** (Slow 60–120, Normal 80–160, Fast 110–220) plus **½× / 2×** buttons to fix the classic "is it 64 or 128?" ambiguity
- **Tap tempo** to double-check by hand
- **Metronome** with an accented downbeat, sample-accurate so it doesn't drift over a long song
- **Saved tempos**: name a song, keep its BPM, and replay it on the metronome later
- Shows the tempo marking (Andante, Allegro…) and typical genres for that speed
- Live rhythm visualizer and mic level meter

Everything runs on the phone. The audio is analysed in memory and never recorded, stored or sent anywhere, and the app has no internet permission.

## Install

Download the APK from the [latest release](../../releases/latest), open it on your Android phone (Android 8.0 or newer) and allow installing from your browser or file manager.

## How it works

The detector in [`TempoDetector.kt`](app/src/main/java/com/beatsense/app/dsp/TempoDetector.kt) runs in three steps:

1. **Spectral flux**: each 1024-sample FFT frame (about 11 ms apart) is compared with the previous one, and the rises in log energy across all frequencies are summed. Drum hits and note attacks show up as spikes.
2. **Autocorrelation** of the last 12 seconds of onset strength shows how often the spikes repeat. Harmonics (2× and 3× the period) are added so the real beat beats its subdivisions, and a gentle preference around 120 BPM settles half/double ambiguity. The result is folded into the chosen tempo range and refined on a fine fractional grid.
3. **Beat phase**: a comb at the detected period is slid over the recent onsets, so the dial can pulse on the beat.

The app reads the phone's *unprocessed* microphone source when it's available, because voice processing (noise suppression, auto gain) flattens drums.

## Build

You need JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleRelease    # APK in app/build/outputs/apk/release/
./gradlew test               # tempo detector tests on synthetic drum loops
```

The unit tests render drum loops at known tempos (house, hip-hop, drum & bass, a ballad, a loop with human timing jitter) and check that the detector finds each tempo within 1 BPM.

Built with Kotlin and Jetpack Compose.

## License

[MIT](LICENSE)
