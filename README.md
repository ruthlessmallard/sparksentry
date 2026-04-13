# SparkSentry

> Personal fire detection for lone mechanics. Android. Offline. Brutally simple.

## What This Is

SparkSentry turns your Android phone into a personal fire watch. Set it on a tripod, aim at your work area, and it monitors for fire using camera-based color detection.

**Two-phase detection:**
1. **Sentry Mode**: Check every 15 seconds for fire colors (orange/red/yellow)
2. **Confirm Mode**: If detected, rapid 1-second checks for 10 seconds
3. **Alarm**: Confirmed fire triggers max-volume alarm + vibration + auto-reset

## Built For

- Heavy equipment mechanics working alone
- Occasional welders/machinists
- Personal use only (see liability screen)

## Color Palette

- **Safety Yellow** (`#FFD700`) — Primary accent
- **Tool Truck Red** (`#B91C1C`) — Alerts, danger
- **Dark Grey** (`#1A1A1A`) — Shop floor background

## Project Structure

```
sparksentry/
├── app/
│   ├── src/main/
│   │   ├── java/com/ruthless/sparksentry/
│   │   │   ├── MainActivity.kt              # Entry point
│   │   │   ├── MonitoringService.kt         # Background monitoring service
│   │   │   ├── fire/
│   │   │   │   └── FireDetector.kt          # HSV color-based fire detection
│   │   │   └── ui/
│   │   │       ├── screens/
│   │   │       │   ├── LiabilityScreen.kt   # "This app is not your mother"
│   │   │       │   └── MonitoringScreen.kt  # Main UI
│   │   │       └── theme/
│   │   │           ├── Color.kt             # Brand colors
│   │   │           ├── Theme.kt             # Dark theme setup
│   │   │           └── Type.kt              # Typography
│   │   ├── res/
│   │   │   └── values/
│   │   │       ├── colors.xml
│   │   │       ├── strings.xml
│   │   │       └── themes.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

## What's Working (Scaffolded)

- [x] Project structure and build config
- [x] Theme with brand colors (safety yellow, tool truck red, dark grey)
- [x] Liability splash screen
- [x] Main monitoring UI with state management
- [x] Background service framework
- [x] Fire detection algorithm (HSV color analysis)

## What's Missing (Next Steps)

- [ ] CameraX integration for live preview
- [ ] Actual frame capture in MonitoringService
- [ ] Wire FireDetector to real camera frames
- [ ] Audio resources (chirp.mp3, alarm.mp3)
- [ ] Notification icons
- [ ] Testing and calibration of fire thresholds
- [ ] Baseline image comparison (for lighting changes)

## Building

1. Open in Android Studio
2. Sync Gradle
3. Run on device or emulator

## License

Personal use only. See liability screen. This app is not your mother.

---

*Built by Shawn (Ruthless) + Assistant | April 2026*
