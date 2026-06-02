# DL24 Android Monitor

Native Android-App (Kotlin) zur Echtzeitüberwachung und Steuerung des **Atorch DL24** elektronischen Lastwiderstands über Bluetooth Low Energy (BLE).

> Portierung der fertigen [Linux/PyQt6-Version](https://github.com/whisker73/bl24_linux) als vollständig natives Android-Projekt — kein Python, kein Framework-Overhead.

---

## Features

| Bereich | Funktion |
|---|---|
| **Verbindung** | BLE-Scan, Verbindungsaufbau, Auto-Detect Gerätetyp (AC / DC / USB) |
| **Dashboard** | Live-Werte: Spannung, Strom, Leistung, Energie (Wh), Kapazität (mAh), Temperatur, Laufzeit |
| **Charts** | Echtzeit-Liniendiagramme für V, A, W, °C (MPAndroidChart, scrollbar) |
| **Steuerung** | Last EIN/AUS, Strom-Slider (0–20 A), Cutoff-Spannung-Slider (0–200 V) |
| **LiPo-Profile** | 1S–6S Schnellprofile (setzt Cutoff + Strom automatisch) |
| **Reset** | W·h / A·h / Dauer / Alles zurücksetzen |
| **Auto-Aus Timer** | Automatisches Ausschalten der Last nach einstellbarer Zeit (0–65535 s) |
| **CSV Export** | Aufgezeichnete Messdaten als CSV in Downloads speichern |

---

## Unterstützte Geräte

| Gerätetyp | Byte | Besonderheit |
|---|---|---|
| DC Meter | `0x02` | W·h wird lokal aus Δ(A·h) × U integriert (Gerätewert unzuverlässig) |
| AC Meter | `0x01` | Frequenz, Leistungsfaktor, Preis |
| USB Meter | `0x03` | D+/D−, A·h, W·h |

BLE-Namenfilter: `DL24`, `AT24`, `UD18`, `-BLE`, `-SPP`

---

## Architektur

```
dl24_android/
├── app/src/main/
│   ├── java/com/dl24/monitor/
│   │   ├── ble/
│   │   │   ├── BleConstants.kt       UUIDs, Protokoll-Konstanten
│   │   │   ├── DL24Protocol.kt       Paket-Parser + CommandBuilder
│   │   │   └── BleManager.kt         Android GATT: Scan, Connect, Notify
│   │   ├── data/
│   │   │   └── DataStore.kt          Ringpuffer (7200 Samples), CSV-Export
│   │   └── ui/
│   │       ├── MainViewModel.kt      MVVM – StateFlow/SharedFlow
│   │       ├── MainActivity.kt       Host: TabLayout + ViewPager2
│   │       ├── DashboardFragment.kt  Live-Messwerte
│   │       ├── ChartsFragment.kt     Liniendiagramme
│   │       ├── ControlsFragment.kt   Gerätesteuerung
│   │       ├── ScanDialogFragment.kt BLE-Scan BottomSheet
│   │       └── MainPagerAdapter.kt   ViewPager2-Adapter
│   └── res/
│       ├── layout/                   XML-Layouts (Dark Theme)
│       ├── values/                   Farben, Strings, Theme
│       ├── drawable/                 card_bg, Launcher-Icon
│       ├── mipmap-anydpi-v26/        Adaptive Icons
│       └── menu/                     Toolbar-Menü
├── gradle/wrapper/                   Gradle 8.7 Wrapper
├── build.gradle.kts                  Root-Build (AGP 8.5.2, Kotlin 1.9.25)
├── app/build.gradle.kts              App-Build
├── gradle.properties                 AndroidX, Jetifier, Heap
└── settings.gradle.kts               JitPack-Repo für MPAndroidChart
```

### Technologiestack

| Komponente | Bibliothek / API |
|---|---|
| Sprache | Kotlin 1.9.25 |
| Build | Android Gradle Plugin 8.5.2, Gradle 8.7 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |
| UI | XML Layouts, ViewBinding, Material3 Dark Theme |
| Navigation | TabLayout + ViewPager2 (3 Tabs) |
| Asynchron | Kotlin Coroutines + StateFlow / SharedFlow |
| BLE | Android BluetoothGatt API (kein 3rd-Party-Wrapper) |
| Charts | [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) v3.1.0 |

---

## BLE-Protokoll (Atorch FF55)

### Verbindung

| Parameter | Wert |
|---|---|
| Service UUID | `0000ffe0-0000-1000-8000-00805f9b34fb` |
| Characteristic UUID | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| Client Config UUID | `00002902-0000-1000-8000-00805f9b34fb` |

### Paketformat (Report, 36 Byte)

```
Byte  0  1   2    3       4–35       Last
      FF 55  01  Type   Payload    Checksum
      ─────  ──  ────   ───────    ────────
      Magic  MT  Dev               XOR
```

- **Magic Header:** `0xFF 0x55`
- **Message Type:** `0x01` = Report, `0x02` = Reply, `0x11` = Command
- **Checksum:** `(Σ Payload-Bytes & 0xFF) XOR 0x44`

### DC Meter Paket-Layout (36 Byte)

| Offset | Länge | Wert | Skalierung |
|---|---|---|---|
| 4–6 | 3 | Spannung (raw) | ÷ 10 → V |
| 7–9 | 3 | Strom (raw) | ÷ 1000 → A |
| 10–12 | 3 | Kapazität (raw) | ÷ 100 → A·h |
| 24–25 | 2 | Temperatur | °C |
| 26–27 | 2 | Stunden | h |
| 28 | 1 | Minuten | min |
| 29 | 1 | Sekunden | s |
| 30 | 1 | Backlight | s |

> **Hinweis:** Leistung (W) wird lokal berechnet: `P = U × I`. Der W·h-Wert bei Offset 13 ist auf manchen DL24-Firmwares unzuverlässig — die App integriert ihn lokal: `ΔWh += ΔAh × U`.

### Direktsteuerung (B1 B2 Protokoll)

```
B1 B2  CMD  INT  FRAC  B6
```

| CMD | Funktion | INT/FRAC |
|---|---|---|
| `0x01` | Output EIN/AUS | 1/0 = Ein, 0/0 = Aus |
| `0x02` | Strom setzen | Ganzzahl / (Nachkomma × 100) |
| `0x03` | Cutoff-Spannung | Ganzzahl / (Nachkomma × 100) |
| `0x04` | Timer setzen | Hi-Byte / Lo-Byte (Sekunden) |

### PX100 Query-Protokoll

Abfrage: `B1 B2 CMD 00 00 B6`  
Antwort: `CA CB d1 d2 d3 CE CF`

| CMD | Abfrage |
|---|---|
| `0x10` | Output-Status (0x00 = Aus, 0x01 = Ein) |
| `0x17` | Gesetzter Strom (×10 mA → A) |
| `0x18` | Cutoff-Spannung (×10 mV → V) |

---

## Entwicklungsumgebung einrichten (CachyOS / Arch Linux)

### Voraussetzungen

```fish
sudo pacman -S jdk17-openjdk gradle
```

### Android SDK

```fish
# Command-line Tools herunterladen
mkdir -p ~/Android/Sdk/cmdline-tools
cd ~/Android/Sdk/cmdline-tools
curl -Lo cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q cmdline-tools.zip && mv cmdline-tools latest && rm cmdline-tools.zip

# Umgebungsvariablen in ~/.config/fish/config.fish eintragen:
set -x ANDROID_HOME $HOME/Android/Sdk
set -x ANDROID_SDK_ROOT $HOME/Android/Sdk
set -x JAVA_HOME /usr/lib/jvm/java-17-openjdk
fish_add_path $ANDROID_HOME/cmdline-tools/latest/bin
fish_add_path $ANDROID_HOME/platform-tools
fish_add_path $ANDROID_HOME/emulator

# SDK-Pakete installieren
yes | sdkmanager --licenses
sdkmanager "platform-tools" "build-tools;35.0.0" "platforms;android-35" "emulator"
```

### Android Emulator (optional)

```fish
# KVM-Beschleunigung aktivieren
sudo usermod -aG kvm $USER
# Danach neu einloggen!

# System-Image + AVD anlegen
sdkmanager "system-images;android-35;google_apis;x86_64"
avdmanager create avd --name "DL24_Test" \
  --package "system-images;android-35;google_apis;x86_64" \
  --device "pixel_6"
```

---

## Build & Deployment

### APK bauen

```fish
cd ~/Development/dl24_android

# Debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK (unsigned)
./gradlew assembleRelease
```

### Auf Emulator deployen

```fish
# Emulator starten
emulator -avd DL24_Test &

# App installieren + starten
./gradlew installDebug
adb shell am start -n com.dl24.monitor/.ui.MainActivity
```

### Auf echtes Gerät deployen

```fish
# USB-Debugging am Gerät aktivieren, dann:
adb devices                          # Gerät prüfen
./gradlew installDebug               # installieren
# oder
adb install app/build/outputs/apk/debug/app-debug.apk
```

> **Hinweis:** BLE funktioniert **nur** auf echten Android-Geräten, nicht im Emulator.

### APK auf Google Drive hochladen

[rclone](https://rclone.org/) ist als `gdrive:` Remote konfiguriert (`~/.config/rclone/rclone.conf`, Scope: `drive.file`).

```fish
# Debug APK bauen und direkt auf Drive hochladen
./gradlew assembleDebug
rclone copy app/build/outputs/apk/debug/app-debug.apk "gdrive:DL24 Android/"

# Aktuellen Inhalt des Drive-Ordners prüfen
rclone ls "gdrive:DL24 Android/"
```

APK landet in **Google Drive → DL24 Android → app-debug.apk**.

Falls der Token abläuft:
```fish
rclone config reconnect gdrive:   # öffnet Browser → "Zulassen" klicken
```

---

## Berechtigungen

Die App fordert zur Laufzeit folgende Berechtigungen an:

| Permission | Android | Zweck |
|---|---|---|
| `BLUETOOTH_SCAN` | ≥ 12 | BLE-Scan |
| `BLUETOOTH_CONNECT` | ≥ 12 | Verbindung aufbauen |
| `ACCESS_FINE_LOCATION` | < 12 | BLE-Scan (ältere Android-Versionen) |
| `WRITE_EXTERNAL_STORAGE` | < 9 | CSV-Export |

---

## Bedienung

### Verbinden

1. **Scan-Symbol** in der Toolbar antippen
2. DL24 einschalten (Gerät muss BLE aktiviert haben)
3. Gerät aus der Liste wählen → Verbindung wird automatisch aufgebaut

### Dashboard (Tab 1)

Zeigt alle Messwerte in Echtzeit. Der farbige Punkt oben links zeigt den Output-Status (grün = EIN, rot = AUS).

### Charts (Tab 2)

Vier scrollbare Liniendiagramme für Spannung, Strom, Leistung und Temperatur. Zoom per Pinch-Geste.

### Steuerung (Tab 3)

| Element | Funktion |
|---|---|
| ON / OFF | Last ein-/ausschalten (B1 B2 Direktprotokoll) |
| Strom-Slider | 0.00–20.00 A in 10-mA-Schritten |
| V-Cut-Slider | 0.0–200.0 V in 0.1-V-Schritten — Unterspannungsabschaltung |
| LiPo-Profile | 1S–6S: setzt Cutoff und Strom automatisch |
| Reset W·h / A·h / Dauer / Alles | Zähler zurücksetzen |
| Auto-Aus Timer | Automatisches Abschalten nach N Sekunden (0 = deaktiviert) |
| CSV Exportieren | Messdaten als `dl24_YYYYMMDD_HHMMSS.csv` in Downloads speichern |

---

## Datenspeicherung

Die App speichert maximal **7200 Samples** (= 2 Stunden bei 1 Hz) im Ringpuffer. Ältere Werte werden automatisch verworfen.

### CSV-Format

```
Zeit (s);Spannung (V);Strom (A);Leistung (W);Energie (Wh);Kapazität (Ah);Temperatur (°C);Dauer
0.0;12.34;1.500;18.51;0.00;0.000;27;00:00:00
1.0;12.33;1.501;18.50;0.01;0.001;27;00:00:01
...
```

---

## Verwandtes Projekt

Die Linux-Version (`bl24_linux`) mit PyQt6-UI und pyqtgraph-Charts diente als Protokoll-Referenz:

- Gleiches Protokoll (FF55 + B1B2 + PX100)
- Gleiche W·h-Integrationslogik
- Gleiche Farbpalette (Catppuccin Mocha)
