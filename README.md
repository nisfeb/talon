# Talon

Native Android chat client for Urbit. See [PLAN.md](PLAN.md).

## Requirements

- Android Studio Koala (2024.1+) or newer
- JDK 17 (project uses `/home/sneagan/jdk-install/jdk-17.0.12+7`)
- Android SDK 34, build-tools 34
- Gradle 8.9+

## Bootstrap

The gradle wrapper jar is not checked in yet. Generate it once:

```bash
cd /home/sneagan/software/personal/talon
gradle wrapper --gradle-version 8.9
```

Then build and install on a connected device:

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
ANDROID_HOME=/home/sneagan/Android/Sdk \
./gradlew installDebug
```

## Layout

```
app/src/main/java/io/nisfeb/talon/
  MainActivity.kt       — entry, sets Compose content
  TalonApp.kt           — root composable
  urbit/
    UrbitSession.kt     — auth + SSE channel
    UrbitChannel.kt     — subscribe / poke / scry
  data/
    AppDatabase.kt      — Room database
  ui/
    theme/              — Material 3 theme
    screens/            — Home, Chat, Profile
```

## Status

Week 0: skeleton + plan only. Nothing wires up yet.
