# PteroManager — NookTheme Android Client

A native Android management client for [Pterodactyl](https://pterodactyl.io) panels, built in Kotlin and Jetpack Compose with a sleek **NookTheme** dark-mode design system.

---

## ✨ Features

| Feature | Details |
|---|---|
| **Multi-panel** | Connect up to 4 Pterodactyl panels simultaneously |
| **Unified feed** | All servers from all panels in one scrollable list |
| **Live stats** | CPU & RAM bars refreshed every 8 seconds via Client API |
| **Power controls** | Start / Restart / Stop / Kill from every server card |
| **Live console** | Full WebSocket console with auto-scroll, color-coded output |
| **Command bar** | Send commands directly from the terminal view |
| **Secure storage** | API keys encrypted with AES-256-GCM (EncryptedSharedPreferences) |
| **NookTheme** | Deep Void backgrounds, Electric Blue accents, rounded 16dp cards |

---

## 🗂 Project Structure

```
ptero-android/
├── .github/workflows/build.yml          # GitHub Actions CI/CD
├── gradle/
│   ├── libs.versions.toml               # Version catalog
│   └── wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/
│       │   ├── values/{strings,themes}.xml
│       │   └── xml/network_security_config.xml
│       └── java/com/example/ptero/
│           ├── MainActivity.kt          # All Compose UI screens
│           ├── api/PterodactylApi.kt    # Retrofit interface + WS helper
│           ├── data/
│           │   ├── PanelAccount.kt      # Models & API response types
│           │   └── SecureStorage.kt     # EncryptedSharedPreferences wrapper
│           ├── ui/NookTheme.kt          # Design system: colors, typography, shapes
│           └── viewmodel/
│               ├── ServersViewModel.kt  # Server list + power signals
│               └── ConsoleViewModel.kt  # WebSocket console state
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🚀 Quick Start

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- An Android device or emulator running API 26+

### 1. Clone

```bash
git clone https://github.com/YOUR_USERNAME/ptero-android.git
cd ptero-android
```

### 2. Open in Android Studio

File → Open → select the `ptero-android` folder.

### 3. Build & Run

```bash
./gradlew assembleDebug
# or just hit ▶ in Android Studio
```

---

## 🔑 Adding a Panel

1. Tap **+** on the home screen
2. Enter a label (e.g., "Host 1"), your panel URL (e.g., `https://panel.example.com`), and your **Client API key** (`ptlc_…`)
3. Tap **Save & Connect**

> **Creating an API key:** In your Pterodactyl panel go to **Account → API Credentials → Create**.  
> Only Client API keys (`ptlc_`) are supported — not Application keys.

---

## 🏗 GitHub Actions

### Debug builds (every push / PR)

A debug APK is built automatically and uploaded as an artifact on every push to `main` or `develop`.

### Release builds (version tags)

Tag a commit `v1.0.0` and push it:

```bash
git tag v1.0.0
git push origin v1.0.0
```

A signed release APK is built and a GitHub Release is created automatically.

#### Optional: Signing secrets

Add these secrets to your GitHub repo (`Settings → Secrets → Actions`):

| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` output |
| `KEY_ALIAS` | Key alias in the keystore |
| `KEYSTORE_PASS` | Keystore password |
| `KEY_PASS` | Key password |

---

## 🎨 NookTheme Color Palette

| Token | Hex | Use |
|---|---|---|
| App Background | `#0B0E14` | Deep Void |
| Card Surface | `#131822` | Nook Dark Slate |
| Card Border | `#1E2638` | Subtle Slate Outline |
| Text Primary | `#FFFFFF` | Headings |
| Text Secondary | `#8C9BAE` | Subtitles |
| Accent Blue | `#3B82F6` | Electric Blue |
| Online | `#10B981` | Emerald Green |
| Offline | `#EF4444` | Red |
| Starting | `#F59E0B` | Amber |

---

## 📄 License

MIT — use freely, attribute kindly.
