# Room Launcher

Dunkler Tablet-Launcher im iOS-„Liquid Glass“-Stil für das Zimmer-Tablet (läuft ab **Android 7.1**, z. B. Galaxy Tab E mit LineageOS) – fest im Querformat,
mit Glas-Widgets für **Uhr** und **Licht**, deinen **Apps** und einem kleinen **AirPods-Abzeichen** oben.

**Download:** [neueste Version (Releases)](../../releases/latest) → `RoomLauncher.apk`

- **Home-Launcher**: Als Standard-Startbildschirm landet die Home-Taste immer hier. Zurück-Taste verlässt ihn nicht.
- **Launcherboard** (8×4-Raster, wie iOS): Widgets und Apps frei anordnen.
  **Leere Stelle** (oder App) gedrückt halten → Bearbeiten: alles wackelt weich, **ziehen** zum Verschieben, **„–“** entfernen, **„Größe“** (Klein/Mittel).
  **Widget** gedrückt halten → seine **große Ansicht** wächst aus dem Widget heraus.
- **Widgets**: Uhr · Licht (Govee) – klein oder mittel.
- **AirPods-Abzeichen** oben rechts: AirPods-Symbol, Name und „verbunden“ / „nicht verbunden“. Antippen = Verbinden/Trennen und Gerät wählen.
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
  - **Farbrad-Regler:** außen der Farbring (Warmweiß → Weiß → Regenbogen), innen der Helligkeits-Bogen (rastet alle 10 % spürbar ein),
    in der Mitte die leuchtende Kugel: tippen = an/aus, halten = Reset. Die Glas-Karte glüht in der Lichtfarbe.
  - **Leuchtende LED-Leiste** mit den echten 15 Segmentfarben
  - **Große Ansicht** (Widget gedrückt halten): Segmente, Farbtupfer, **Nacht-Timer** (30 min · 1 Std · 2 Std – dimmt in der letzten Minute sanft herunter, Mond-Ring + Restzeit im Widget), Szenen & Musik
  - **Modus „Alle“:** Leiste antippen/ziehen = Anzahl leuchtender Segmente 0–15, Farbe färbt alle leuchtenden
  - **Modus „Einzeln“:** Farbe = Pinsel, Segment antippen/wischen = malen, halten = ausschalten
  - **Schnelleinstellungs-Kachel „Licht“** (An/Aus aus jeder App)
  - **Externe Befehle** (Tasker, NFC …): `lumen://power/on|off|toggle`, `lumen://reset`, `lumen://brightness/50`, `lumen://level/8`, `lumen://color/RRGGBB`, `lumen://sleep/30`

## Installation

1. APK laden: GitHub → *Releases* → **Room Launcher v2.6.0** (neueste) → `RoomLauncher.apk`.
2. Auf dem Tablet installieren („Unbekannte Quellen“ erlauben).
3. App öffnen, Bluetooth-/Standortberechtigung erlauben und den **Standort einschalten** – ohne ihn findet Android 7 keine Bluetooth-LE-Geräte (für die Licht-Suche). Der Launcher zeigt oben einen Hinweis, falls etwas fehlt.
4. Home-Taste drücken → **Room Launcher** als Standard wählen („Immer“).
   Alternativ: Einstellungen → Apps → Standard-Apps → Startbildschirm.

## Govee einrichten

Licht-Widget gedrückt halten (oder Zahnrad) → MAC-Adresse eintragen oder Gerät suchen (meist `ihoment_H6xxx_XXXX` oder `Govee_…`).
Die Govee-App auf dem Handy schließen – das Licht akzeptiert nur eine BLE-Verbindung.
Falls die Farbe nicht reagiert: anderen „Farb-Befehl“ probieren (02 / 0D / 0B / 15), je nach Modell.

## AirPods

Einmal in den Android-Bluetooth-Einstellungen koppeln. Das Abzeichen oben zeigt Name und Verbindung;
antippen → Verbinden/Trennen (versteckte A2DP-`connect()`-API, klappt auf Android 7 ohne Root) oder anderes Gerät wählen.

## Bauen

GitHub Actions baut bei jedem Push automatisch (`.github/workflows/build-apk.yml`) und veröffentlicht
die Release `v<versionName>` aus `app/build.gradle.kts`.
Lokal: Android SDK installieren, dann `./gradlew assembleRelease`.

Die APK ist mit einem im Repo liegenden Schlüssel (`keystore/`) signiert, damit Updates sich drüber installieren lassen.
Nur für den privaten Gebrauch gedacht.

## Tests

`app/src/test/.../ProtocolTest.kt` prüft die Bluetooth-Pakete Byte für Byte gegen veröffentlichte Mitschnitte
(Govee: egold555/Govee-Reverse-Engineering, H617E-Bauplan). Läuft bei jedem Build mit.

## Schriften

Nothings eigene Schriften (NDot, NType) sind nicht frei verfügbar. Verwendet werden freie Nachbauten unter der
SIL Open Font License 1.1 (Lizenztexte liegen in `app/src/main/assets/fonts/`):

- **Doto** – Punktmatrix-Schrift (Copyright The Doto Project Authors)
- **Space Grotesk** – Grotesk (Copyright The Space Grotesk Project Authors)
