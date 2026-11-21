# NSPiOS

Schlanke iOS-App zum **Entpacken und Verwalten eigener NSP/NSZ- und XCI/XCZ-Dateien** – im Liquid-Glass-Design.

- **Themes:** Dunkel, Hell, eigene Farbe, Regen (animiert, mit einstellbarem Wind)
- **Sprache:** Englisch (Standard) oder Deutsch – in den Einstellungen
- **Live-Sync:** Löschen/Verschieben (auch über die Dateien-App) ist sofort in allen Tabs sichtbar
Die IPA wird automatisch per GitHub Actions gebaut, ganz ohne Mac.

**Voraussetzung:** iOS 26 oder neuer (für iOS 27 gebaut, nutzt die Liquid-Glass-APIs).

## Funktionen

- **Liquid Glass UI**: schwebende Tab-Bar, die beim Scrollen einklappt, Glas-Buttons, Glas-Fortschrittskarte, Mesh-Hintergrund, Haptik
- **Importieren** von `.nsp` / `.nsz` / `.xci` / `.xcz` aus der Dateien-App (iCloud, USB, SMB), per Teilen oder „Öffnen in …“ (auf APFS als sofortiger Klon)
- **Ultraschnelles Entpacken**
  - POSIX `pread`/`write` mit einem festen 16-MiB-Puffer → keine Allokationen pro Block, konstanter RAM-Verbrauch
  - Live-Anzeige von Geschwindigkeit (MB/s) und Restzeit
  - Warteschlange, Abbrechen, läuft kurz im Hintergrund weiter, Bildschirm bleibt an
  - **Direkt entpacken** ohne vorherige Kopie (spart doppelten Speicher)
- **Stabil**: Speicherprüfung vorab, Entpacken in einen `.part`-Ordner und erst bei Erfolg umbenennen, Schutz gegen `../`-Dateinamen, keine halben Ordner nach Abbruch
- **XCI-Unterstützung**: alle Partitionen (`update`, `normal`, `secure`, `logo`) in Unterordnern – oder per Schalter nur die Spieldaten (`secure`) direkt in den Ordner. Auch Dumps mit vorangestellter Key-Area werden erkannt.
- **RomFS & ExeFS** (optional): mit deiner eigenen `prod.keys` werden die NCAs entschlüsselt und als Ordner `romfs/`, `exefs/`, `control/` (DLC: `romfs_<TitleID>`) entpackt – inkl. Titlekey-Tickets, NCA2/NCA3, hardwarebeschleunigtes AES-CTR. Keys bleiben privat im App-Container und sind **nicht** im Repo. Update-NCAs (BKTR) und NCZ werden übersprungen (mit Hinweis).
- **Inhalt anzeigen** einer NSP/XCI ohne Entpacken (XCI nach Partition gruppiert)
- **Ordner durchsuchen**, Suche in jeder Liste
- **In Dateien verschieben/kopieren**: einzelne Dateien, ganze Ordner oder alle auf einmal
- **Löschen** per Wischen oder Kontextmenü; **Bereinigen** (Temp, Cache, Reste, leere Ordner); **Alles löschen** (nur NSPs / nur Ordner / alles)
- **In-App-Updates direkt aus Git**: Jeder Push baut automatisch eine neue Version (Build-Nummer = Actions-Run) und veröffentlicht sie als Release `nspios-b<build>`. Die App prüft beim Öffnen (max. alle 3 h) oder per Knopf und installiert:
  - **LiveContainer** → echtes Selbst-Update: IPA laden, entpacken, eigene App-Dateien ersetzen, neu starten – kein Neu-Signieren nötig
  - **TrollStore** → ersetzt die App direkt (`apple-magnifier://install?url=…`)
  - **SideStore / AltStore** → Übergabe zum Neu-Signieren und Ersetzen
  - sonst IPA laden und über „Öffnen in …“ weitergeben
  - optional **vollautomatisch installieren**, „Was ist neu“ = Commit-Nachricht, die letzten 5 Releases bleiben erhalten
- Alle Daten sind auch in der Dateien-App unter **Auf meinem iPhone › NSPiOS** sichtbar (`NSP/` und `Entpackt/`)

> Die App entpackt nur die Container (PFS0/HFS0). Die enthaltenen `.nca`-Dateien bleiben verschlüsselt.

## IPA bauen

1. Änderungen in `NSPiOS/` pushen **oder** unter *Actions → Build NSPiOS IPA → Run workflow* starten.
2. Die IPA landet automatisch als Release `nspios-b<build>` (und als Artifact **NSPiOS-ipa**).
3. Bereits installierte Apps finden das Update selbst (Einstellungen › Updates).

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
    ├── SettingsView.swift   # Einstellungen: Speicher, Darstellung, Sprache, RomFS, Updates
    ├── UpdateManager.swift  # GitHub-Releases prüfen, Download, Übergabe an TrollStore/SideStore/AltStore
    ├── UpdateViews.swift    # Update-Sheet
    ├── Components.swift     # Zeilen, Aktionen, Fortschrittskarte, Statusanzeige
    ├── Theme.swift          # Themes (Dunkel/Hell/Farbe/Regen), Sprache, Hintergründe
    ├── FileStore.swift      # Logik: Import, Warteschlange, Löschen, Export
    ├── FS.swift             # Dateisystem-Hilfen
    └── Package.swift        # NSP (PFS0) + XCI (HFS0) Parser & schneller Entpacker
```
