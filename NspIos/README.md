# NspIos

iOS-App (SwiftUI) zum **Entpacken und Verwalten eigener NSP/NSZ-Dateien** direkt auf dem iPhone/iPad.
Die IPA wird automatisch per GitHub Actions gebaut – ganz ohne Mac.

## Funktionen

- **Importieren** von `.nsp` / `.nsz` aus der Dateien-App (auch iCloud, USB-Stick, SMB), per „Teilen → NspIos“ oder „Öffnen in …“
- **Entpacken** des PFS0-Containers (NCA, TIK, CERT, CNMT …) in einen eigenen Ordner
  - Streaming in 4-MB-Blöcken → auch sehr große Dateien ohne RAM-Probleme
  - Fortschrittsanzeige, Abbrechen, Warteschlange für mehrere Dateien
  - **Direkt entpacken** ohne vorherige Kopie (spart doppelten Speicher)
  - Speicherplatz-Prüfung vor dem Entpacken
  - Optional: NSP nach erfolgreichem Entpacken automatisch löschen
- **Inhalt anzeigen** einer NSP ohne sie zu entpacken
- **Ordner durchsuchen** (inkl. Unterordner)
- **In Dateien verschieben / kopieren** – einzelne Dateien, ganze Ordner oder alle entpackten Ordner auf einmal an einen beliebigen Ort in der Dateien-App
- **Löschen** per Wischgeste oder Kontextmenü (gedrückt halten)
- **Bereinigen**: Temp, Cache, Inbox, abgebrochene Reste und leere Ordner entfernen
- **Alles löschen**: nur NSPs, nur entpackte Ordner oder alles komplett
- Alle Daten sind zusätzlich in der Dateien-App unter **Auf meinem iPhone › NspIos** sichtbar (`NSP/` und `Entpackt/`)

> Hinweis: Die App entpackt nur den NSP-Container. Die enthaltenen `.nca`-Dateien bleiben verschlüsselt – eine Entschlüsselung ist nicht enthalten.

## IPA bauen

1. Änderungen in `NspIos/` pushen **oder** unter *Actions → Build NspIos IPA → Run workflow* manuell starten.
2. Nach dem Lauf unter dem Workflow-Run das Artifact **NspIos-ipa** herunterladen (ZIP mit `NspIos.ipa`).
3. Für einen Release mit angehängter IPA einen Tag pushen: `git tag nspios-v1.0.0 && git push origin nspios-v1.0.0`

## Installieren

Die IPA ist unsigniert. Installation z. B. mit
- **AltStore / SideStore** oder **Sideloadly** (signiert mit deiner Apple-ID), oder
- **TrollStore** (die IPA ist bereits mit `ldid` pseudo-signiert).

## Projektaufbau

```
NspIos/
├── project.yml            # XcodeGen-Spezifikation (Xcode-Projekt wird in CI erzeugt)
└── Sources/
    ├── NspIosApp.swift    # App-Einstieg, „Öffnen in …“
    ├── ContentView.swift  # Hauptbildschirm: NSPs, Entpackt, Speicher, Einstellungen
    ├── FolderView.swift   # Ordner-Browser + NSP-Inhaltsansicht
    ├── Components.swift   # Zeilen, Kontextmenüs, Fortschritt, Export-Picker
    ├── FileStore.swift    # Logik: Import, Warteschlange, Löschen, Bereinigen, Export
    ├── FS.swift           # Dateisystem-Hilfen
    └── PFS0.swift         # NSP/PFS0-Parser und Entpacker
```

Lokal mit Mac: `brew install xcodegen && cd NspIos && xcodegen generate && open NspIos.xcodeproj`
