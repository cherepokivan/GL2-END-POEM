# GL2 End Poem Video

Client-side NeoForge mod for Minecraft 1.21.1. It replaces the End `WinScreen` with a full-screen MP4 player, starts the OGG soundtrack on the first rendered frame, and returns to the game when ESC is pressed or playback completes.

## Media files

Add the media files to the project before building:

```text
src/main/resources/assets/glpoem/video/end_poem.mp4
src/main/resources/assets/glpoem/sounds/end_poem.ogg
```

Use an H.264 video track in the MP4 container and encode the video at 30 FPS. JCodec does not support every codec that may be placed inside an MP4. Minecraft's built-in sound engine expects OGG under `assets/<namespace>/sounds/`; the sound is streamed from that path. If the MP4 is absent, the custom screen closes without crashing the game. If the OGG is absent or invalid, playback continues without the soundtrack.

## Build locally

Use Java 21 and run:

```bash
./gradlew build
```

The mod JAR is written to `build/libs/`. JCodec is included in the mod JAR using NeoForge Jar-in-Jar.

## GitHub Actions

The `Build mod` workflow runs on pushes to `main` and on manual dispatch. It compiles the mod with Java 21 and uploads the JAR as a workflow artifact for 14 days. The artifact version is derived from GitHub Actions' monotonically increasing workflow run number: patch counts 0–9, then minor counts 0–9, then major increments. The version is passed to Gradle for that build and does not create an automatic source commit. Local builds use `mod_version` from `gradle.properties`.
