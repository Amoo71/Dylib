# Room Launcher

Dunkler Tablet-Launcher im iOS-„Liquid Glass“-Stil für das Zimmer-Tablet.

- **Home-Launcher**: Als Standard-Startbildschirm landet die Home-Taste immer hier. Zurück-Taste verlässt ihn nicht.
- **Exit**: Knopf unten rechts **gedrückt halten** → anderen Launcher öffnen / Standard ändern / Einstellungen.
- **Govee-LED** direkt per Bluetooth LE: An/Aus, Helligkeit, Farbe, Presets (ohne Cloud, ohne Govee-App).
- **AirPods**: Verbinden/Trennen, Akku (L/R/Case) aus BLE-Signalen, Geräuschkontrolle Aus/Transparenz/Adaptiv/ANC über Apples AAP-Protokoll.
- **Dock + alle Apps** (App gedrückt halten = zum Dock hinzufügen/entfernen).

## Installation

1. APK laden: GitHub → *Releases* → **room-launcher-latest** → `RoomLauncher.apk`
   (oder Actions → letzter Lauf → Artefakt `RoomLauncher-apk`).
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
- ANC/Transparenz: Apple Accessory Protocol über L2CAP-PSM `0x1001` (wie LibrePods/MagicPods).
  Android hat dafür keine offizielle API – manche Bluetooth-Stacks lassen den Kanal nicht zu;
  dann zeigt die Karte „Steuerkanal fehlgeschlagen“. Auf solchen Geräten geht es nur mit Root (LibrePods-Ansatz).

## Bauen

GitHub Actions baut bei jedem Push automatisch (`.github/workflows/room-launcher.yml`).
Lokal: Android SDK installieren, dann `./gradlew assembleRelease`.

Die APK ist mit einem im Repo liegenden Schlüssel (`keystore/`) signiert, damit Updates sich drüber installieren lassen.
Nur für den privaten Gebrauch gedacht.
