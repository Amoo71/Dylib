package com.amoo.roomlauncher

import java.util.Locale

/**
 * Sprache der Oberfläche: Deutsch oder Englisch (Einstellungen → Allgemein).
 * Feste Texte werden über das Wörterbuch [EN] automatisch übersetzt (in label(), Knöpfen, Chips,
 * Hinweisen); zusammengesetzte Texte nutzen [tr] mit beiden Fassungen.
 */
object Lang {
    @Volatile var english = false

    val locale: Locale get() = if (english) Locale.ENGLISH else Locale.GERMANY

    fun init(prefs: Prefs) {
        english = when (prefs.language) {
            "en" -> true
            "de" -> false
            else -> Locale.getDefault().language != "de" // erste Wahl: Systemsprache
        }
    }

    /** Fester deutscher Text → im englischen Modus die Übersetzung (falls bekannt). */
    fun auto(de: String): String = if (english) EN[de] ?: de else de

    fun auto(de: CharSequence): CharSequence = if (english && de is String) EN[de] ?: de else de
}

/** Text in beiden Sprachen – für zusammengesetzte Texte. */
fun tr(de: String, en: String): String = if (Lang.english) en else de

private val EN: Map<String, String> = mapOf(
    // ---- Allgemein / Board
    "+ App" to "+ App",
    "+ Board" to "+ Board",
    "+ Dock" to "+ Dock",
    "+ Widget" to "+ Widget",
    "✓ Board" to "✓ Board",
    "✓ Dock" to "✓ Dock",
    "Fertig" to "Done",
    "Einstellungen" to "Settings",
    "Hintergrund" to "Background",
    "Widgets" to "Widgets",
    "Apps" to "Apps",
    "Dock" to "Dock",
    "Geräte" to "Devices",
    "Allgemein" to "General",
    "Verlassen" to "Exit",
    "Uhr" to "Clock",
    "Licht" to "Light",
    "AirPods" to "AirPods",
    "Klein" to "Small",
    "Mittel" to "Medium",
    "Größe" to "Size",
    "Glas: an" to "Glass: on",
    "Glas: aus" to "Glass: off",
    "Glas-Container" to "Glass container",
    "ohne Container – durchsichtig wie die Uhr" to "no container – transparent like the clock",
    "Auf deinem Board" to "On your board",
    "Noch keine Widgets." to "No widgets yet.",
    "Größe antippen, um das Widget aufs Board zu legen." to "Tap a size to put the widget on the board.",
    "Uhrzeit und Datum" to "Time and date",
    "Widget-Fehler" to "Widget error",
    "Board bearbeiten" to "Edit board",
    "Öffnen ›" to "Open ›",
    "Entfernen" to "Remove",
    "Ausgewählt" to "Selected",
    "Noch leer." to "Still empty.",
    "App suchen" to "Search apps",
    "Tippen = öffnen · gedrückt halten = aufs Board legen" to "Tap = open · hold = put on the board",
    "Dock anzeigen" to "Show dock",
    "Die Glas-Leiste unten mit deinen Lieblings-Apps." to "The glass bar at the bottom with your favorite apps.",
    "Apps im Dock (max. 10)" to "Apps in the dock (max. 10)",
    "Tipp: Dock gedrückt halten → Icons ziehen zum Sortieren, „–“ zum Entfernen. Neue Apps im Tab „Apps“ mit „+ Dock“." to
        "Tip: hold the dock → drag icons to sort, “–” to remove. Add apps in the “Apps” tab with “+ Dock”.",

    // ---- Darstellung / Allgemein
    "Darstellung" to "Appearance",
    "App-Icons" to "App icons",
    "Original" to "Original",
    "Glas" to "Glass",
    "Klar" to "Clear",
    "Flüssige Animationen (60 fps)" to "Smooth animations (60 fps)",
    "Aus = 30 fps, schont ein älteres Tablet." to "Off = 30 fps, easier on an older tablet.",
    "Bildschirm immer an" to "Keep screen on",
    "Das Tablet geht nicht in den Standby, solange der Launcher offen ist." to "The tablet doesn't go to sleep while the launcher is open.",
    "System" to "System",
    "Android-Einstellungen" to "Android settings",
    "Bluetooth" to "Bluetooth",
    "WLAN" to "Wi-Fi",
    "Sprache" to "Language",
    "Energiesparen" to "Power saving",
    "Hintergrund 20 fps, pausiert nach 2 min ohne Berührung – deutlich weniger CPU." to "Background at 20 fps, pauses after 2 min without touch – much less CPU.",

    // ---- Hintergrund
    "Thema" to "Theme",
    "Regen" to "Rain",
    "Schwarz" to "Black",
    "Farbig" to "Colored",
    "Eigenes" to "Custom",
    "Wind" to "Wind",
    "Stärke" to "Intensity",
    "Tempo" to "Speed",
    "Geschwindigkeit" to "Speed",
    "Blasen" to "Bubbles",
    "Anzahl" to "Count",
    "Deckkraft" to "Opacity",
    "Farbe" to "Color",
    "Farben" to "Colors",
    "Grau" to "Gray",
    "Weiß" to "White",
    "Keiner" to "None",
    "Abdunkeln" to "Dim",
    "Effekt darüber" to "Effect on top",
    "Verlaufswinkel" to "Gradient angle",
    "Farben – eine = einfarbig, mehrere = Verlauf (max. 4)" to "Colors – one = solid, several = gradient (max. 4)",
    "Eigenes Bild oder GIF" to "Your own image or GIF",
    "Bild oder GIF wählen" to "Choose image or GIF",
    "Bild/GIF auswählen" to "Select image/GIF",
    "Anderes Bild wählen" to "Choose another image",
    "Bild konnte nicht geladen werden" to "Couldn't load the image",
    "Liquid Glass" to "Liquid Glass",
    "Milchglas" to "Frosted glass",
    "Glas-Deckkraft" to "Glass opacity",
    "Niedrig = klares, sehr durchsichtiges Glas · hoch = stärker getönt." to "Low = clear, very transparent glass · high = more tinted.",
    "Widgets, Dock und Panels zeigen den Hintergrund verschwommen – wie bei iOS. Aus = klares Glas, spart Leistung." to
        "Widgets, dock and panels show the background blurred – like iOS. Off = clear glass, saves power.",

    // ---- Geräte / Diagnose
    "Govee-Licht" to "Govee light",
    "nicht eingerichtet" to "not set up",
    "Nicht eingerichtet" to "Not set up",
    "nicht gekoppelt" to "not paired",
    "nicht verbunden" to "not connected",
    "verbunden" to "connected",
    "Verbunden" to "Connected",
    "Sucht…" to "Searching…",
    "sucht…" to "searching…",
    "Diagnose" to "Diagnostics",
    "Alles neu verbinden" to "Reconnect everything",
    "Bluetooth neu starten" to "Restart Bluetooth",
    "Verbindungen werden neu aufgebaut" to "Reconnecting…",
    "Bluetooth startet neu …" to "Bluetooth is restarting …",
    "Bluetooth wird neu gestartet…" to "Restarting Bluetooth…",
    "Bitte Bluetooth manuell aus- und einschalten" to "Please turn Bluetooth off and on manually",
    "Keine Signale seit 3 min – Scan neu gestartet" to "No signals for 3 min – scan restarted",
    "noch keine" to "none yet",

    // ---- Hinweise / Rechte
    "Bluetooth ist aus" to "Bluetooth is off",
    "Einschalten" to "Turn on",
    "Erlauben" to "Allow",
    "Bluetooth-Berechtigung fehlt" to "Bluetooth permission missing",
    "Standort-Berechtigung fehlt – Android braucht sie für die Bluetooth-Suche" to "Location permission missing – Android needs it for Bluetooth scanning",
    "Standort ist aus – die Licht-Suche braucht ihn" to "Location is off – searching for the light needs it",
    "Standort ist aus – ohne ihn findet Android keine Bluetooth-Geräte." to "Location is off – without it Android finds no Bluetooth devices.",
    "Bitte unter „Berechtigungen“ den Standort erlauben" to "Please allow location under “Permissions”",

    // ---- Verlassen
    "Einmal kurz wechseln" to "Switch once",
    "Kein anderer Launcher installiert." to "No other launcher installed.",
    "Home-Taste führt danach wieder hierher" to "The home button brings you back here afterwards",
    "Dauerhaft zurück zum normalen Launcher" to "Back to the normal launcher for good",
    "Als Standard aufheben" to "Clear as default",
    "Startbildschirm-Einstellungen" to "Home screen settings",
    "Erledigt – jetzt Home drücken und den Launcher wählen" to "Done – now press Home and choose the launcher",
    "Deinstallieren" to "Uninstall",
    "Bildschirm-Recht entfernen" to "Remove screen permission",
    "Room Launcher darf für den Nacht-Timer den Bildschirm ausschalten. Vor dem Deinstallieren dieses Recht entfernen." to
        "Room Launcher may turn off the screen for the sleep timer. Remove this permission before uninstalling.",
    "Recht entfernt – die App kann jetzt deinstalliert werden" to "Permission removed – the app can be uninstalled now",

    // ---- Licht
    "Licht einrichten" to "Set up light",
    "Noch kein Licht verbunden." to "No light connected yet.",
    "Helligkeit" to "Brightness",
    "Alle" to "All",
    "Einzeln" to "Single",
    "Alle aktiven" to "All active",
    "Pinselfarbe" to "Brush color",
    "Segment" to "Segment",
    "Segmente" to "Segments",
    "von 15" to "of 15",
    "gelöscht" to "cleared",
    "Alle Segmente zurückgesetzt" to "All segments reset",
    "Szenen" to "Scenes",
    "Szenen & Musik" to "Scenes & music",
    "Musik (Mikrofon am Controller)" to "Music (mic on the controller)",
    "Nacht-Timer" to "Sleep timer",
    "Aus" to "Off",
    "1 Std" to "1 h",
    "2 Std" to "2 h",
    "30 min" to "30 min",
    "Am Ende dimmt das Licht in der letzten Minute sanft aus." to "At the end the light gently dims out over the last minute.",
    "Bildschirm ausschalten" to "Turn off screen",
    "Wenn der Timer abläuft" to "When the timer ends",
    "Offene Apps schließen" to "Close open apps",
    "Musik pausieren und alle Apps beenden" to "Pause music and close all apps",
    "Bildschirm-aus braucht einmal das Recht „Bildschirm sperren“ – hier tippen." to "Turning off the screen needs the “Lock screen” permission once – tap here.",
    "Damit der Nacht-Timer am Ende den Bildschirm ausschalten kann. Room Launcher nutzt nur „Bildschirm sperren“." to
        "So the sleep timer can turn off the screen at the end. Room Launcher only uses “Lock screen”.",
    "Bitte unter Einstellungen → Sicherheit → Geräteadministratoren erlauben" to "Please allow it under Settings → Security → Device administrators",
    "Neu verbinden" to "Reconnect",
    "Anderes Licht" to "Other light",
    "MAC-Adresse eingeben" to "Enter MAC address",
    "Verbinden" to "Connect",
    "Trennen" to "Disconnect",
    "Oder in der Nähe suchen" to "Or search nearby",
    "Govee-App auf dem Handy schließen – das Licht erlaubt nur eine Verbindung gleichzeitig." to
        "Close the Govee app on your phone – the light allows only one connection at a time.",
    "Suche Lichter…" to "Searching for lights…",
    "Gefundene Lichter" to "Lights found",
    "Ist das Licht an und in der Nähe?" to "Is the light on and nearby?",
    "Suchen" to "Search",
    "Farb-Befehl" to "Color command",
    "Standard (02)" to "Standard (02)",
    "Neuere H6xxx (0D)" to "Newer H6xxx (0D)",
    "Segmente (15)" to "Segments (15)",
    "Helligkeitsskala" to "Brightness scale",
    "Modell unbekannt: Reagiert die Farbe nicht, anderen Farb-Befehl probieren." to "Unknown model: if the color doesn't react, try another color command.",
    "Ungültige MAC-Adresse – Format AA:BB:CC:DD:EE:FF" to "Invalid MAC address – format AA:BB:CC:DD:EE:FF",
    "Ungültige Adresse" to "Invalid address",
    "Leiste antwortet nicht mehr – verbinde neu" to "Strip stopped responding – reconnecting",
    "Leiste nicht gefunden – ist sie an und die Govee-App geschlossen?" to "Strip not found – is it on and the Govee app closed?",
    "Gerät hat keinen Govee-Steuerkanal – falsches Gerät?" to "Device has no Govee control channel – wrong device?",
    "Senden klappt nicht – verbinde neu" to "Sending fails – reconnecting",
    "kein Signal von der Leiste – aus, zu weit weg oder mit der Govee-App am Handy verbunden?" to
        "no signal from the strip – off, too far away or connected to the Govee app on a phone?",
    "Signal: " to "Signal: ",
    // Szenen & Musikmodi
    "Sonnenaufgang" to "Sunrise",
    "Sonnenuntergang" to "Sunset",
    "Film" to "Movie",
    "Kerzenlicht" to "Candlelight",
    "Romantisch" to "Romantic",
    "Blinken" to "Blinking",
    "Schneeflocke" to "Snowflake",
    "Energisch" to "Energic",
    "Spektrum" to "Spectrum",
    "Rollen" to "Rolling",
    "Rhythmus" to "Rhythm",

    // ---- AirPlay
    "AirPlay" to "AirPlay",
    "nicht installiert" to "not installed",
    "bereit" to "ready",
    "spielt" to "playing",
    "aus" to "off",
    "Starten" to "Start",
    "Öffnen" to "Open",
    "Installieren" to "Install",
    "Stoppen" to "Stop",
    "Server stoppen" to "Stop server",
    "Download-Seite" to "Download page",
    "Server ist aus" to "Server is off",
    "Server ist aus – Starten öffnet die App." to "Server is off – Start opens the app.",
    "AirPlay Server von jqssun installieren" to "Install AirPlay Server by jqssun",
    "AirPlay-Empfänger" to "AirPlay receiver",
    "AirPlay Server" to "AirPlay Server",
    "Die App „AirPlay Server“ (jqssun) macht das Tablet zum AirPlay-Bildschirm und -Lautsprecher." to
        "The “AirPlay Server” app (jqssun) turns the tablet into an AirPlay display and speaker.",
    "Für Titel und Steuerung: Mitteilungszugriff erlauben (große Ansicht)." to "For title and controls: allow notification access (large view).",
    "Mitteilungszugriff erlauben" to "Allow notification access",
    "Bereit für AirPlay" to "Ready for AirPlay",
    "Nichts läuft" to "Nothing playing",

    // ---- AirPods
    "Keine AirPods gekoppelt" to "No AirPods paired",
    "Keine AirPods gekoppelt – bitte in den Bluetooth-Einstellungen koppeln" to "No AirPods paired – please pair them in the Bluetooth settings",
    "einmalig in den Bluetooth-Einstellungen koppeln" to "pair once in the Bluetooth settings",
    "Bitte über die Bluetooth-Einstellungen verbinden" to "Please connect via the Bluetooth settings",
    "Gerät auswählen" to "Choose device",
    "Gerät wählen" to "Choose device",
    "Keine gekoppelten Geräte gefunden." to "No paired devices found.",
    "Bluetooth-Einstellungen" to "Bluetooth settings",
    "Liste aktualisieren" to "Refresh list",
)
