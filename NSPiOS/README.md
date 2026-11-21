# NSPiOS

Schlanke iOS-App zum **Entpacken und Verwalten eigener NSP/NSZ-Dateien** – im Liquid-Glass-Design, immer im Dark Mode.
Die IPA wird automatisch per GitHub Actions gebaut, ganz ohne Mac.

**Voraussetzung:** iOS 26 oder neuer (für iOS 27 gebaut, nutzt die Liquid-Glass-APIs).

## Funktionen

- **Liquid Glass UI**: schwebende Tab-Bar, die beim Scrollen einklappt, Glas-Buttons, Glas-Fortschrittskarte, Mesh-Hintergrund, Haptik
- **Importieren** von `.nsp` / `.nsz` aus der Dateien-App (iCloud, USB, SMB), per Teilen oder „Öffnen in …“ (auf APFS als sofortiger Klon)
- **Ultraschnelles Entpacken**
  - POSIX `pread`/`write` mit einem festen 16-MiB-Puffer → keine Allokationen pro Block, konstanter RAM-Verbrauch
  - Live-Anzeige von Geschwindigkeit (MB/s) und Restzeit
  - Warteschlange, Abbrechen, läuft kurz im Hintergrund weiter, Bildschirm bleibt an
  - **Direkt entpacken** ohne vorherige Kopie (spart doppelten Speicher)
- **Stabil**: Speicherprüfung vorab, Entpacken in einen `.part`-Ordner und erst bei Erfolg umbenennen, Schutz gegen `../`-Dateinamen, keine halben Ordner nach Abbruch
- **Inhalt anzeigen** einer NSP ohne Entpacken
- **Ordner durchsuchen**, Suche in jeder Liste
- **In Dateien verschieben/kopieren**: einzelne Dateien, ganze Ordner oder alle auf einmal
- **Löschen** per Wischen oder Kontextmenü; **Bereinigen** (Temp, Cache, Reste, leere Ordner); **Alles löschen** (nur NSPs / nur Ordner / alles)
- Alle Daten sind auch in der Dateien-App unter **Auf meinem iPhone › NSPiOS** sichtbar (`NSP/` und `Entpackt/`)

> Die App entpackt nur den NSP-Container. Die enthaltenen `.nca`-Dateien bleiben verschlüsselt.

## IPA bauen

1. Änderungen in `NSPiOS/` pushen **oder** unter *Actions → Build NSPiOS IPA → Run workflow* starten.
2. Im Workflow-Run das Artifact **NSPiOS-ipa** herunterladen.
3. Für einen Release mit IPA: `git tag nspios-v2.0.0 && git push origin nspios-v2.0.0`

## Installieren

Die IPA ist unsigniert: **AltStore / SideStore**, **Sideloadly** oder **TrollStore** (bereits mit `ldid` pseudo-signiert).

## Aufbau

```
NSPiOS/
├── project.yml              # XcodeGen-Spezifikation (Projekt wird in CI erzeugt)
├── Resources/Assets.xcassets
└── Sources/
    ├── NSPiOSApp.swift      # App + Tab-Struktur, globale Sheets/Alerts
    ├── LibraryView.swift    # Tab „NSP“
    ├── ExtractedView.swift  # Tab „Entpackt“, Ordner-Browser, NSP-Inhalt
    ├── StorageView.swift    # Tab „Speicher“: Übersicht, Bereinigen, Löschen
    ├── Components.swift     # Zeilen, Aktionen, Fortschrittskarte, Statusanzeige
    ├── Theme.swift          # Farben, Mesh-Hintergrund, Glas-Helfer
    ├── FileStore.swift      # Logik: Import, Warteschlange, Löschen, Export
    ├── FS.swift             # Dateisystem-Hilfen
    └── PFS0.swift           # NSP/PFS0-Parser & schneller Entpacker
```
