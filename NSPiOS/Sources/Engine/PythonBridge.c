#include "PythonBridge.h"
#include <Python.h>
#include <stdio.h>

static int g_ready = 0;

static int fail(PyStatus status, char *err, size_t errlen) {
    if (err && errlen) {
        snprintf(err, errlen, "%s%s%s",
                 status.func ? status.func : "",
                 status.func ? ": " : "",
                 status.err_msg ? status.err_msg : "Python-Fehler");
    }
    return -1;
}

int nspios_py_init(const char *home, const char *const *paths, int count, char *err, size_t errlen) {
    if (g_ready) return 0;

    PyPreConfig pre;
    PyPreConfig_InitIsolatedConfig(&pre);
    pre.utf8_mode = 1;
    PyStatus status = Py_PreInitialize(&pre);
    if (PyStatus_Exception(status)) return fail(status, err, errlen);

    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);
    config.buffered_stdio = 0;
    config.write_bytecode = 0;
    config.install_signal_handlers = 1;

    status = PyConfig_SetBytesString(&config, &config.home, home);
    if (PyStatus_Exception(status)) { PyConfig_Clear(&config); return fail(status, err, errlen); }

    config.module_search_paths_set = 1;
    for (int i = 0; i < count; i++) {
        wchar_t *path = Py_DecodeLocale(paths[i], NULL);
        if (!path) {
            PyConfig_Clear(&config);
            if (err && errlen) snprintf(err, errlen, "Pfad nicht lesbar: %s", paths[i]);
            return -1;
        }
        status = PyWideStringList_Append(&config.module_search_paths, path);
        PyMem_RawFree(path);
        if (PyStatus_Exception(status)) { PyConfig_Clear(&config); return fail(status, err, errlen); }
    }

    status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    if (PyStatus_Exception(status)) return fail(status, err, errlen);

    // GIL freigeben – jeder Aufruf holt ihn sich über PyGILState_Ensure.
    PyEval_SaveThread();
    g_ready = 1;
    return 0;
}

int nspios_py_run(const char *code) {
    if (!g_ready) return -1;
    PyGILState_STATE state = PyGILState_Ensure();
    int rc = PyRun_SimpleString(code);
    PyGILState_Release(state);
    return rc;
}
