#ifndef NSPIOS_PYTHON_BRIDGE_H
#define NSPIOS_PYTHON_BRIDGE_H

#include <stddef.h>

/// Startet den eingebetteten Python-Interpreter (einmalig). 0 = ok, sonst Fehlertext in `err`.
int nspios_py_init(const char *home, const char *const *paths, int count, char *err, size_t errlen);

/// Führt Python-Code mit GIL aus. 0 = ok, -1 = Fehler (Traceback geht nach stderr).
int nspios_py_run(const char *code);

#endif
