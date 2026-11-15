# Room Launcher

Dunkler Tablet-Launcher im iOS-„Liquid Glass“-Stil für das Zimmer-Tablet – fest im Querformat,
mit drei Glas-Widgets: **Licht**, **AirPods** und **Apps**.

**Download:** [neueste Version (Releases)](../../releases/latest) → `RoomLauncher.apk`

- **Home-Launcher**: Als Standard-Startbildschirm landet die Home-Taste immer hier. Zurück-Taste verlässt ihn nicht.
- **Launcherboard** (8×4-Raster, wie iOS): Widgets und Apps frei anordnen.
  Gedrückt halten → Bearbeiten: alles wackelt, **ziehen** zum Verschieben, **„–“** entfernen, **„⤡“** Größe (Klein/Mittel/Groß).
- **Widgets**: Uhr · Licht (Govee) · AirPods – jeweils in mehreren Größen.
- **Dock** unten (Liquid Glass): an/aus, Apps hinzufügen, per Gedrückthalten sortieren/entfernen.
- **Einstellungen** (Zahnrad unten rechts): Hintergrund, Widgets, Apps, Dock, Geräte, Allgemein (inkl. Launcher verlassen).
- **Hintergründe**, auf die das Glas reagiert (ab Android 12 mit echter Unschärfe, sonst transparent):
  - *Regen*: dünne Tropfen mit Wind-Böen (Stärke, Wind, Tempo einstellbar)
  - *Schwarz*: schwarz mit weichen grauen schwebenden Blasen
  - *Farbig*: einfarbig oder Verlauf aus bis zu 4 Farben + Blasen (Anzahl, Größe, Tempo, Deckkraft, Farbe)
  - *Eigenes*: eigenes Bild/GIF/PNG/JPEG + optional Regen oder Blasen darüber
- **Govee-LED** direkt per Bluetooth LE: An/Aus, Helligkeit, Farbe, Presets, Szenen, Musikmodi, Status-Rücklesen.
- **AirPods** (Protokoll nach [GreenPods](https://github.com/andrewkomkov/GreenPods)): Verbinden/Trennen, Akku L/R/Case + Laden + „im Ohr“,
  Geräuschkontrolle Aus / Transparenz / Adaptiv / ANC, Stärke des adaptiven Audios, Gesprächserkennung,
  automatische Ohrerkennung, ANC mit einem AirPod.

## Installation

1. APK laden: GitHub → *Releases* → **Room Launcher v1.1.0** (neueste) → `RoomLauncher.apk`.
2. Auf dem Tablet installieren („Unbekannte Quellen“ erlauben).
3. App öffnen, Bluetooth- und Standortberechtigung erlauben (Standort braucht Android ≤ 11 für BLE-Scans).
4. Home-Taste drücken → **Room Launcher** als Standard wählen („Immer“).
   Alternativ: Einstellungen → Apps → Standard-Apps → Startbildschirm.

## Govee einrichten

Zahnrad in der Licht-Karte → Gerät wählen (meist `ihoment_H6xxx_XXXX` oder `Govee_…`).
Die Govee-App auf dem Handy schließen – das Licht akzeptiert nur eine BLE-Verbindung.
Falls die Farbe nicht reagiert: anderen „Farb-Befehl“ probieren (02 / 0D / 0B / 15), je nach Modell.

## AirPods

Einmal in den Android-Bluetooth-Einstellungen koppeln, dann im Zahnrad auswählen.

- Akku: funktioniert auf jedem Gerät (Apple-BLE-Werbepakete).
- Verbinden per Knopf: nutzt die versteckte A2DP-`connect()`-API, klappt bis ca. Android 12 meist ohne Root.
- Modi & Einstellungen: Apple Accessory Protocol (AAP) über einen sicheren L2CAP-Kanal (PSM `0x1001`,
  Apple-Service-UUID `74ec2172-0bad-4d01-8f77-997b2be0722a`), Socket-Aufbau wie GreenPods.
  Android hat dafür keine offizielle API. Laut GreenPods nehmen die AirPods den Kanal ohne Root
  zuverlässig erst ab Android 16 QPR3 / 17 an. Auf älteren Tablets zeigt das Widget dann
  „Steuerkanal fehlgeschlagen“ – Akku, Ohrerkennung und Verbinden funktionieren trotzdem.

## Bauen

GitHub Actions baut bei jedem Push automatisch (`.github/workflows/build-apk.yml`) und veröffentlicht
die Release `v<versionName>` aus `app/build.gradle.kts`.
Lokal: Android SDK installieren, dann `./gradlew assembleRelease`.

Die APK ist mit einem im Repo liegenden Schlüssel (`keystore/`) signiert, damit Updates sich drüber installieren lassen.
Nur für den privaten Gebrauch gedacht.
