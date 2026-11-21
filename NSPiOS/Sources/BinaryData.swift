import Foundation

extension Data {
    func le16(_ offset: Int) -> UInt16 {
        UInt16(self[startIndex + offset]) | UInt16(self[startIndex + offset + 1]) << 8
    }

    func le32(_ offset: Int) -> UInt32 {
        var value: UInt32 = 0
        for i in 0..<4 {
            value |= UInt32(self[startIndex + offset + i]) << (8 * i)
        }
        return value
    }

    func le64(_ offset: Int) -> UInt64 {
        var value: UInt64 = 0
        for i in 0..<8 {
            value |= UInt64(self[startIndex + offset + i]) << (8 * i)
        }
        return value
    }

    /// Teilbereich mit neuem Startindex 0.
    func bytes(_ offset: Int, _ count: Int) -> Data {
        Data(self[(startIndex + offset)..<(startIndex + offset + count)])
    }

    init?(hex: String) {
        let chars = Array(hex.utf8)
        guard chars.count % 2 == 0, !chars.isEmpty else { return nil }
        var data = Data(capacity: chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let hi = Self.nibble(chars[i]), let lo = Self.nibble(chars[i + 1]) else { return nil }
            data.append(hi << 4 | lo)
            i += 2
        }
        self = data
    }

    private static func nibble(_ c: UInt8) -> UInt8? {
        switch c {
        case 48...57: return c - 48
        case 65...70: return c - 55
        case 97...102: return c - 87
        default: return nil
        }
    }

    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
