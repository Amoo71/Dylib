# Room Launcher

Dunkler Tablet-Launcher im iOS-„Liquid Glass“-Stil für das Zimmer-Tablet (läuft ab **Android 7.1**, z. B. Galaxy Tab E mit LineageOS) – fest im Querformat,
mit drei Glas-Widgets: **Licht**, **AirPods** und **Apps**.

**Download:** [neueste Version (Releases)](../../releases/latest) → `RoomLauncher.apk`

- **Home-Launcher**: Als Standard-Startbildschirm landet die Home-Taste immer hier. Zurück-Taste verlässt ihn nicht.
- **Launcherboard** (8×4-Raster, wie iOS): Widgets und Apps frei anordnen.
  Gedrückt halten → Bearbeiten: alles wackelt, **ziehen** zum Verschieben, **„–“** entfernen, **„Größe“** (Klein/Mittel).
- **Widgets**: Uhr · Licht (Govee) · AirPods – klein oder mittel. Tipp auf ein Widget öffnet das Detail-Panel mit allen Funktionen (Szenen, Musikmodi, ANC/Transparenz/Adaptiv, Gesprächserkennung …).
- **Dock** unten (Liquid Glass): an/aus, Apps hinzufügen, per Gedrückthalten sortieren/entfernen.
- **Einstellungen** (Zahnrad unten rechts): Hintergrund, Widgets, Apps, Dock, Geräte, Allgemein (inkl. Launcher verlassen).
- **Liquid Glass mit Milchglas**: Widgets, Dock, Icons und Panels zeigen den animierten Hintergrund verschwommen – Glas-Deckkraft per Regler einstellbar.
- **Nothing-OS-Look**: Punktmatrix-Schrift für Uhr, Titel und Zahlen, klare Grotesk für Text.
- **Selbstreparatur**: hängender Bluetooth-Scan wird neu gestartet, ein stummes Licht neu verbunden, bei wiederholten Bluetooth-Fehlern startet die App Bluetooth neu. Live-Diagnose unter Einstellungen → Geräte.
- **App-Icons** im Stil Original, Glas-Kachel oder Klar (einfarbig auf Glas).
- **Hintergründe**:
  - *Regen*: dünne Tropfen mit Wind-Böen (Stärke, Wind, Tempo einstellbar)
  - *Schwarz*: schwarz mit weichen grauen schwebenden Blasen
  - *Farbig*: einfarbig oder Verlauf aus bis zu 4 Farben + Blasen (Anzahl, Größe, Tempo, Deckkraft, Farbe)
  - *Eigenes*: eigenes Bild/GIF/PNG/JPEG + optional Regen oder Blasen darüber
- **Govee-LED** direkt per Bluetooth LE: An/Aus, Helligkeit, Farbe, Presets, Szenen, Musikmodi, Status-Rücklesen.
  Modell wird am Namen erkannt (z. B. `Govee_H617E_774E`). Für RGBIC-Leisten wie die **H617E** (15 Segmente) gilt der geprüfte Bauplan:
  Write Without Response, Helligkeit 1–100, RGB in 8er-Stufen, nach jedem Verbinden kompletter Segment-Stand (ein Paket pro Farbe), danach nur Änderungen.
  - **Widget (kompakt):** Ein/Aus (0,7 s halten = Reset) · Helligkeit · 15-Segment-Leiste · Farbwähler
  - **Modus „Alle“:** Leiste antippen/ziehen = Anzahl leuchtender Segmente 0–15, Farbe färbt alle leuchtenden
  - **Modus „Einzeln“:** Farbe = Pinsel, Segment antippen/wischen = malen, halten = ausschalten
  - **Schnelleinstellungs-Kachel „Licht“** (An/Aus aus jeder App)
  - **Externe Befehle** (Tasker, NFC …): `lumen://power/on|off|toggle`, `lumen://reset`, `lumen://brightness/50`, `lumen://level/8`, `lumen://color/RRGGBB`
- **AirPods** (Protokoll nach [GreenPods](https://github.com/andrewkomkov/GreenPods)): Verbinden/Trennen, Akku L/R/Case + Laden + „im Ohr“,
  Geräuschkontrolle Aus / Transparenz / Adaptiv / ANC, Stärke des adaptiven Audios, Gesprächserkennung,
  automatische Ohrerkennung, ANC mit einem AirPod.

## Installation

1. APK laden: GitHub → *Releases* → **Room Launcher v2.5.4** (neueste) → `RoomLauncher.apk`.
2. Auf dem Tablet installieren („Unbekannte Quellen“ erlauben).
3. App öffnen, Bluetooth-/Standortberechtigung erlauben und den **Standort einschalten** – ohne ihn findet Android 7 keine Bluetooth-LE-Geräte (Licht, AirPods-Akku). Der Launcher zeigt oben einen Hinweis, falls etwas fehlt.
4. Home-Taste drücken → **Room Launcher** als Standard wählen („Immer“).
   Alternativ: Einstellungen → Apps → Standard-Apps → Startbildschirm.

## Govee einrichten

Zahnrad in der Licht-Karte → Gerät wählen (meist `ihoment_H6xxx_XXXX` oder `Govee_…`).
Die Govee-App auf dem Handy schließen – das Licht akzeptiert nur eine BLE-Verbindung.
Falls die Farbe nicht reagiert: anderen „Farb-Befehl“ probieren (02 / 0D / 0B / 15), je nach Modell.

## AirPods

Einmal in den Android-Bluetooth-Einstellungen koppeln, dann im Zahnrad auswählen.

- Akku, Laden, „im Ohr“ / „im Case“ und Modell: aus Apples BLE-Werbepaketen (Decoder nach GreenPods/CAPod) – funktioniert auf jedem Gerät.
  Die App lernt beim Verbinden das Modell der eigenen AirPods und ignoriert danach fremde AirPods in der Nähe.
- Verbinden per Knopf: nutzt die versteckte A2DP-`connect()`-API, klappt bis ca. Android 12 meist ohne Root.
- Modi & Einstellungen: Apple Accessory Protocol (AAP) über einen sicheren L2CAP-Kanal (PSM `0x1001`,
  Apple-Service-UUID `74ec2172-0bad-4d01-8f77-997b2be0722a`), Socket-Aufbau wie GreenPods.
  Android hat dafür keine offizielle API. Laut GreenPods nehmen die AirPods den Kanal ohne Root
  zuverlässig erst ab Android 16 QPR3 / 17 an. Unter Android 7 erkennt die App das, versucht es nicht endlos weiter
  und erklärt beim Tippen auf einen Modus, dass er am AirPods-Stiel gewechselt werden muss.
  Akku, Ohrerkennung und Verbinden funktionieren trotzdem.

## Bauen

GitHub Actions baut bei jedem Push automatisch (`.github/workflows/build-apk.yml`) und veröffentlicht
die Release `v<versionName>` aus `app/build.gradle.kts`.
Lokal: Android SDK installieren, dann `./gradlew assembleRelease`.

Die APK ist mit einem im Repo liegenden Schlüssel (`keystore/`) signiert, damit Updates sich drüber installieren lassen.
Nur für den privaten Gebrauch gedacht.

## Tests

`app/src/test/.../ProtocolTest.kt` prüft die Bluetooth-Pakete Byte für Byte gegen veröffentlichte Mitschnitte
(Govee: egold555/Govee-Reverse-Engineering, AirPods: GreenPods/LibrePods, OpenPods). Läuft bei jedem Build mit.

## Schriften

Nothings eigene Schriften (NDot, NType) sind nicht frei verfügbar. Verwendet werden freie Nachbauten unter der
SIL Open Font License 1.1 (Lizenztexte liegen in `app/src/main/assets/fonts/`):

- **Doto** – Punktmatrix-Schrift (Copyright The Doto Project Authors)
- **Space Grotesk** – Grotesk (Copyright The Space Grotesk Project Authors)
