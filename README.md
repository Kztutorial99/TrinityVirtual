# TrinityVirtual ⚡

**Virtual Android Environment** — Hybrid Container + Root Layer

## Features
- 📦 **Virtual Container** — Install & run APKs in isolated virtual space
- 🔓 **Virtual Root** — Fake `su` via Linux namespace isolation (no real root needed)
- 🎭 **Device Spoof** — Manufacturer, Model, Fingerprint, IMEI, Android ID
- 📍 **GPS Spoof** — Fake location anywhere in the world
- 🔧 **Module Manager** — Install Xposed/Hook/Spoof/System modules
- 🏗️ **GitHub Actions** — Auto build APK on every push

## Architecture

```
TrinityVirtual
├── Container Layer  — BlackBox-style app isolation
├── Root Engine      — Linux namespace + fake su binary
├── Spoof Layer      — Native hook for device/GPS spoofing
└── Module Manager   — Dynamic module loader
```

## Build

### Via GitHub Actions (Automatic)
Every push to `main` triggers a build. Download APK from Actions > Artifacts.

### Manual
```bash
./gradlew assembleDebug
```

## Requirements
- Android 9+ (API 28+)
- ARM64 / ARMv7 / x86_64

## Disclaimer
For educational and research purposes only.
