package com.amoo.roomlauncher

import android.app.Activity
import android.os.Bundle

/**
 * Unsichtbare Activity für externe Befehle (z. B. aus Tasker, NFC-Tags, Automatisierungen):
 *   lumen://power/on · lumen://power/off · lumen://power/toggle · lumen://reset
 *   lumen://brightness/50 · lumen://level/8 · lumen://color/RRGGBB
 * Bis die Verbindung zum Licht steht, bleiben die Befehle in der Warteschlange.
 */
class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RoomApp
        if (BtPerms.granted(this)) app.startBluetooth()
        val uri = intent?.data
        if (uri == null || !app.govee.command(uri)) toast("Unbekannter Befehl: $uri")
        finish()
    }
}
