# Room Launcher

Dunkler Tablet-Launcher im iOS-„Liquid Glass“-Stil für das Zimmer-Tablet (läuft ab **Android 7.1**, z. B. Galaxy Tab E mit LineageOS) – fest im Querformat,
mit Widgets für **Uhr** und **Licht**, deinen **Apps** und zwei Mini-Widgets oben (**AirPods** links, **Licht** rechts).

**Download:** [neueste Version (Releases)](../../releases/latest) → `RoomLauncher.apk`

- **Home-Launcher**: Als Standard-Startbildschirm landet die Home-Taste immer hier. Zurück-Taste verlässt ihn nicht.
- **Launcherboard** (8×4-Raster): Widgets und Apps bleiben **genau dort, wo du sie loslässt** – Lücken bleiben Lücken.
  Ist der Platz belegt, landet das Element im nächsten freien Feld; ein Schatten zeigt beim Ziehen die Landestelle.
  **Leere Stelle** (oder App) gedrückt halten → Bearbeiten: alles wackelt weich, **ziehen** zum Verschieben, **„–“** entfernen, **„Größe“** (Klein/Mittel).
  **Widget** gedrückt halten → seine **große Ansicht** wächst aus dem Widget heraus.
- **Widgets**: Uhr · Licht (Govee) · AirPods · AirPlay (eingebauter Empfänger) – klein oder mittel. Pro Widget abschaltbarer **Glas-Container** (im Bearbeiten-Modus „Glas: an/aus“ unten links am Widget oder unter Einstellungen → Widgets) – ohne ist es durchsichtig wie die Uhr.
  Die Uhr zeigt klein und mittig den **Akku des Tablets** (beim Laden mit Blitz, unter 15 % rot).
- **Mini-Widgets oben**: links **AirPods** (Symbol, Name, verbunden/nicht verbunden), rechts **Licht**
  (leuchtender Farbpunkt, an/aus mit Helligkeit, Mond + Restzeit beim Nacht-Timer, kleiner Power-Knopf). Antippen = große Ansicht.
- **Dock** unten (Liquid Glass): an/aus, Apps hinzufügen, per Gedrückthalten sortieren/entfernen.
- **Einstellungen** (Zahnrad unten rechts): Hintergrund, Widgets, Apps, Dock, Geräte, Allgemein (inkl. Launcher verlassen).
- **Liquid Glass mit Milchglas**: Widgets, Dock, Icons und Panels zeigen den animierten Hintergrund verschwommen – Glas-Deckkraft per Regler einstellbar.
- **Nothing-OS-Look**: Punktmatrix-Schrift für Uhr, Titel und Zahlen, klare Grotesk für Text.
- **Selbstreparatur**: hängender Bluetooth-Scan wird neu gestartet, ein stummes Licht neu verbunden, bei wiederholten Bluetooth-Fehlern startet die App Bluetooth neu. Live-Diagnose unter Einstellungen → Geräte.
- **App-Icons** im Stil Original, Glas-Kachel oder Klar (einfarbig auf Glas).
- **Sprache**: Deutsch oder Englisch (Einstellungen → Allgemein; anfangs die Systemsprache).
- **Sparsam im Leerlauf**: Board und Dock liegen als fertige GPU-Ebene über dem Hintergrund; *Energiesparen* (Standard an)
  zeichnet den Hintergrund mit 20 fps und pausiert ihn nach 2 min ohne Berührung. Licht-Ring auf dem Board bewegt sich nur
  bei Berührung, die Uhr aktualisiert sich einmal pro Minute. Ist die Lampe nicht erreichbar, wartet der Bluetooth-Chip
  selbst auf sie (autoConnect) – keine Such- oder Verbindungsschleife; kurz gefiltert gesucht wird höchstens alle 10 min.
- **Hintergründe**:
  - *Regen*: dünne Tropfen mit Wind-Böen (Stärke, Wind, Tempo einstellbar)
  - *Schwarz*: schwarz mit weichen grauen schwebenden Blasen
  - *Farbig*: einfarbig oder Verlauf aus bis zu 4 Farben + Blasen (Anzahl, Größe, Tempo, Deckkraft, Farbe)
  - *Eigenes*: eigenes Bild/GIF/PNG/JPEG + optional Regen oder Blasen darüber
- **Govee-LED** direkt per Bluetooth LE: An/Aus, Helligkeit, Farbe, Presets, Szenen, Musikmodi, Status-Rücklesen.
  Modell wird am Namen erkannt (z. B. `Govee_H617E_774E`). Für RGBIC-Leisten wie die **H617E** (15 Segmente) gilt der geprüfte Bauplan:
  Write Without Response, Helligkeit 1–100, RGB in 8er-Stufen, nach jedem Verbinden kompletter Segment-Stand (ein Paket pro Farbe), danach nur Änderungen.
  - **Lumen-Oberfläche** (nach der iOS-Vorlage „Lumen Remote“): **3D-LED-Ring** mit 15 Segmenten auf geneigter Ebene
    (Perspektive, 4 Lagen mit Glow und Glanz), schwebt und schwingt sanft.
    Mitte: Power-Knopf (tippen = an/aus, 0,7 s halten = Reset). Ring wischen = Anzahl (Modus „Alle“) bzw. Segment wählen
    („Einzeln“), tippen = setzen/malen, halten = Segment löschen. Dazu HSV-**Farbrad**, **Glow-Helligkeitsleiste**, Modus-Chip
    und ein dunkler Hintergrund in der gewählten Farbe. Schrift: Nunito (abgerundet).
  - **Widget klein** = nur der Ring · **mittel** = Ring + Statuszeile + Farbrad + Helligkeit · **große Ansicht** (Widget gedrückt halten)
    zusätzlich Nacht-Timer, Szenen & Musik.
  - **Nacht-Timer** 30 min · 1 Std · 2 Std: Licht dimmt in der letzten Minute sanft aus, danach (einstellbar) **Bildschirm aus**
    und **offene Apps schließen** (Musik pausiert). Fürs Bildschirm-Ausschalten einmalig das Recht „Bildschirm sperren“ erlauben
    (vor dem Deinstallieren unter Einstellungen → Verlassen wieder entfernen).
  - **Schnelleinstellungs-Kachel „Licht“** (An/Aus aus jeder App)
  - **Externe Befehle** (Tasker, NFC …): `lumen://power/on|off|toggle`, `lumen://reset`, `lumen://brightness/50`, `lumen://level/8`, `lumen://color/RRGGBB`, `lumen://sleep/30`

## Installation

1. APK laden: GitHub → *Releases* → **Room Launcher v3.0.0** (neueste) → `RoomLauncher.apk`.
2. Auf dem Tablet installieren („Unbekannte Quellen“ erlauben).
3. App öffnen, Bluetooth-/Standortberechtigung erlauben und den **Standort einschalten** – ohne ihn findet Android 7 keine Bluetooth-LE-Geräte (für die Licht-Suche). Der Launcher zeigt oben einen Hinweis, falls etwas fehlt.
4. Home-Taste drücken → **Room Launcher** als Standard wählen („Immer“).
   Alternativ: Einstellungen → Apps → Standard-Apps → Startbildschirm.

## Govee einrichten

Licht-Widget gedrückt halten (oder Zahnrad) → MAC-Adresse eintragen oder Gerät suchen (meist `ihoment_H6xxx_XXXX` oder `Govee_…`).
Die Govee-App auf dem Handy schließen – das Licht akzeptiert nur eine BLE-Verbindung.
Falls die Farbe nicht reagiert: anderen „Farb-Befehl“ probieren (02 / 0D / 0B / 15), je nach Modell.

## AirPlay (eingebaut)

Room Launcher ist selbst ein **AirPlay-2-Empfänger** – keine Zusatz-App nötig. Der Empfänger stammt aus
[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (UxPlay, GPL-3.0) und ist fest eingebaut:

- **Bildschirmspiegelung** von iPhone/iPad/Mac (H.264/H.265 per Hardware-Decoder), **AirPlay-Video** (HLS, z. B. YouTube)
  mit Pause/±10 s, **Musik** (AAC/ALAC) mit Titel, Interpret, Cover und Zurück/Pause/Weiter.
- Vollbild öffnet sich bei einer Verbindung von selbst – auch aus dem Standby – und schließt sich danach wieder.
- Mini-Widget oben links, Widget klein/mittel und große Ansicht: Status, läuft gerade, Ein/Aus, Ansehen,
  Einstellungen (Name im WLAN, immer eingeschaltet, Vollbild bei Verbindung, PIN, Auflösung, Bildrate, H.265).
- Im Leerlauf wartet der Server nur auf Verbindungen (praktisch keine CPU).

Gebaut wird nur für 32-Bit-ARM (Galaxy Tab E). Tablet und iPhone/Mac müssen im selben WLAN sein;
geschützte Inhalte (z. B. Apple TV+) gehen nicht.

## AirPods

Einmal in den Android-Bluetooth-Einstellungen koppeln. Das Mini-Widget oben links und das AirPods-Widget (klein/mittel) zeigen Name und Verbindung;
antippen → Verbinden/Trennen (versteckte A2DP-`connect()`-API, klappt auf Android 7 ohne Root) oder anderes Gerät wählen.

## Bauen

GitHub Actions baut bei jedem Push automatisch (`.github/workflows/build-apk.yml`) und veröffentlicht
die Release `v<versionName>` aus `app/build.gradle.kts`.
Lokal: Android SDK installieren, `git submodule update --init --recursive --depth 1`, dann `./gradlew assembleRelease`
(NDK und CMake lädt Gradle selbst).

Die APK ist mit einem im Repo liegenden Schlüssel (`keystore/`) signiert, damit Updates sich drüber installieren lassen.
Nur für den privaten Gebrauch gedacht.

## Tests

`app/src/test/.../ProtocolTest.kt` prüft die Bluetooth-Pakete Byte für Byte gegen veröffentlichte Mitschnitte
(Govee: egold555/Govee-Reverse-Engineering, H617E-Bauplan). `GridTest.kt` prüft das freie Raster. Läuft bei jedem Build mit.

## Schriften

Nothings eigene Schriften (NDot, NType) sind nicht frei verfügbar. Verwendet werden freie Nachbauten unter der
SIL Open Font License 1.1 (Lizenztexte liegen in `app/src/main/assets/fonts/`):

- **Doto** – Punktmatrix-Schrift (Copyright The Doto Project Authors)
- **Space Grotesk** – Grotesk (Copyright The Space Grotesk Project Authors)
- **Nunito** – abgerundete Schrift der Licht-Oberfläche (Copyright The Nunito Project Authors)

## Lizenz

Room Launcher enthält den AirPlay-Empfänger aus [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)
(Paket `io.github.jqssun.airplay`, nativer Code unter `app/src/main/cpp`) sowie UxPlay, libplist, OpenSSL und FFmpeg als
Untermodule. Deshalb steht das gesamte Projekt unter der **GNU General Public License v3.0** (siehe `LICENSE`).
