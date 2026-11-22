import Foundation

enum EngineError: LocalizedError {
    case unavailable(String)
    case python(String)

    var errorDescription: String? {
        switch self {
        case .unavailable(let message): return L("Engine unavailable: ", "Engine nicht verfügbar: ") + message
        case .python(let message): return message
        }
    }
}

/// Eingebetteter Python-Interpreter (CPython für iOS) für die On-Device-Werkzeuge.
/// Alle Aufrufe laufen nacheinander auf einer eigenen Queue.
final class PythonEngine: @unchecked Sendable {
    static let shared = PythonEngine()

    private let queue = DispatchQueue(label: "de.amoo71.nspios.python", qos: .userInitiated)
    private var started = false

    /// Pfad der nativen Bibliothek (capstone, SPIRV-Cross, lz4).
    static var nativeLibraryPath: String? {
        guard let frameworks = Bundle.main.privateFrameworksURL else { return nil }
        let path = frameworks.appendingPathComponent("NSPiOSNative.framework/NSPiOSNative").path
        return FileManager.default.fileExists(atPath: path) ? path : nil
    }

    private func start() throws {
        if started { return }
        guard let resources = Bundle.main.resourcePath else { throw EngineError.unavailable("bundle") }
        let home = resources + "/python"
        let libDir = home + "/lib"
        guard let version = (try? FileManager.default.contentsOfDirectory(atPath: libDir))?
            .first(where: { $0.hasPrefix("python3") }) else {
            throw EngineError.unavailable(L("Python standard library missing", "Python-Standardbibliothek fehlt"))
        }
        guard let native = Self.nativeLibraryPath else {
            throw EngineError.unavailable("NSPiOSNative.framework")
        }
        setenv("NSPIOS_NATIVE_LIB", native, 1)
        // nxnatives Shader-Übersetzer (NativeAOT) für NVN-Spiele
        if let frameworks = Bundle.main.privateFrameworksURL {
            let xlate = frameworks.appendingPathComponent("NSPiOSXlate.framework/NSPiOSXlate").path
            if FileManager.default.fileExists(atPath: xlate) { setenv("NSPIOS_XLATE_LIB", xlate, 1) }
        }
        setenv("TMPDIR", NSTemporaryDirectory(), 1)

        let paths = [
            "\(libDir)/\(version)",
            "\(libDir)/\(version)/lib-dynload",
            resources + "/py",
        ]
        let cPaths = paths.map { strdup($0) }
        defer { cPaths.forEach { free($0) } }
        var constPaths = cPaths.map { UnsafePointer($0) }
        let errorLength = 1024
        var errorBuffer = [CChar](repeating: 0, count: errorLength)

        let rc = constPaths.withUnsafeMutableBufferPointer { buffer in
            nspios_py_init(home, buffer.baseAddress, Int32(paths.count), &errorBuffer, errorLength)
        }
        guard rc == 0 else {
            throw EngineError.unavailable(String(cString: errorBuffer))
        }
        started = true
    }

    /// Ruft `module.function(args)` auf und liefert das (JSON-fähige) Ergebnis.
    func call(_ module: String, _ function: String, args: [String: Any] = [:]) async throws -> Any {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    continuation.resume(returning: try self.callSync(module, function, args: args))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private func callSync(_ module: String, _ function: String, args: [String: Any]) throws -> Any {
        try start()
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("nspios-py-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let argsURL = dir.appendingPathComponent("args.json")
        let outURL = dir.appendingPathComponent("result.json")
        try JSONSerialization.data(withJSONObject: args).write(to: argsURL)

        func literal(_ s: String) -> String {
            let data = (try? JSONSerialization.data(withJSONObject: [s], options: [.withoutEscapingSlashes])) ?? Data("[\"\"]".utf8)
            return String(decoding: data, as: UTF8.self)
        }

        // Fängt auch SystemExit (sys.exit in Werkzeugen), damit nie der ganze Prozess endet.
        let code = """
        import json as _j, traceback as _t, importlib as _il
        try:
            _a = _j.load(open(\(literal(argsURL.path))[0]))
            _m = _il.import_module(\(literal(module))[0])
            _r = {"ok": True, "result": getattr(_m, \(literal(function))[0])(_a)}
        except BaseException as _e:
            _r = {"ok": False, "error": f"{type(_e).__name__}: {_e}", "trace": _t.format_exc()}
        with open(\(literal(outURL.path))[0], "w") as _f:
            _j.dump(_r, _f, default=str)
        """
        let errorLength = 4096
        var errorBuffer = [CChar](repeating: 0, count: errorLength)
        let rc = nspios_py_run(code, &errorBuffer, errorLength)

        guard let data = try? Data(contentsOf: outURL),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            let detail = rc != 0 ? String(cString: errorBuffer) : ""
            throw EngineError.python(L("Python returned no result", "Python lieferte kein Ergebnis") + (detail.isEmpty ? "" : "\n\n" + detail))
        }
        if object["ok"] as? Bool == true {
            return object["result"] ?? NSNull()
        }
        let message = object["error"] as? String ?? "?"
        let trace = object["trace"] as? String ?? ""
        throw EngineError.python(message + (trace.isEmpty ? "" : "\n\n" + trace.suffix(1500)))
    }

    /// Selbsttest: Python, capstone, lz4, SPIRV-Cross.
    func selfTest() async throws -> [String: String] {
        let result = try await call("nspios_engine.selftest", "run")
        return (result as? [String: Any] ?? [:]).mapValues { "\($0)" }
    }
}
