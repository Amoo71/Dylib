// Room Launcher: ersetzt die Rechte-Prüfung aus jqssun/android-airplay-server (GPL-3.0).
// "Lokales Netzwerk" ist erst ab einer künftigen Android-Version ein eigenes Recht – auf Android 7 immer erlaubt.
package io.github.jqssun.airplay

import android.content.Context

fun Context.hasLocalNetwork(): Boolean = true
