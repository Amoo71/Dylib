import Foundation
import CommonCrypto

enum CryptoError: LocalizedError {
    case failed(Int32)

    var errorDescription: String? {
        switch self {
        case .failed(let status): return L("Crypto error (\(status)).", "Kryptografie-Fehler (\(status)).")
        }
    }
}

enum AES {
    /// AES-128-ECB (Key-Area, Titlekeys, XTS-Tweaks).
    static func ecb(_ data: Data, key: Data, decrypt: Bool) throws -> Data {
        var output = Data(count: data.count)
        var moved = 0
        let status = output.withUnsafeMutableBytes { out in
            data.withUnsafeBytes { input in
                key.withUnsafeBytes { k in
                    CCCrypt(
                        CCOperation(decrypt ? kCCDecrypt : kCCEncrypt),
                        CCAlgorithm(kCCAlgorithmAES),
                        CCOptions(kCCOptionECBMode),
                        k.baseAddress, key.count,
                        nil,
                        input.baseAddress, data.count,
                        out.baseAddress, data.count,
                        &moved
                    )
                }
            }
        }
        guard status == kCCSuccess else { throw CryptoError.failed(status) }
        return output
    }

    /// AES-128-XTS mit Nintendos Tweak (Sektornummer big-endian), wie im NCA-Header.
    static func xtsDecryptNintendo(_ data: Data, key: Data, sectorSize: Int = 0x200, firstSector: UInt64 = 0) throws -> Data {
        let dataKey = Data(key.prefix(16))
        let tweakKey = Data(key.suffix(16))
        var output = Data(capacity: data.count)
        var sector = firstSector
        var position = 0

        while position < data.count {
            var tweakBytes = [UInt8](repeating: 0, count: 16)
            var s = sector
            for i in stride(from: 15, through: 0, by: -1) {
                tweakBytes[i] = UInt8(s & 0xFF)
                s >>= 8
            }
            var t = [UInt8](try ecb(Data(tweakBytes), key: tweakKey, decrypt: false))

            let end = min(position + sectorSize, data.count)
            var blocks = [UInt8](data[(data.startIndex + position)..<(data.startIndex + end)])
            var tweaks = [UInt8]()
            tweaks.reserveCapacity(blocks.count)

            var b = 0
            while b + 16 <= blocks.count {
                for j in 0..<16 { blocks[b + j] ^= t[j] }
                tweaks.append(contentsOf: t)
                // T = T · α in GF(2^128)
                var carry: UInt8 = 0
                for j in 0..<16 {
                    let next = t[j] >> 7
                    t[j] = (t[j] << 1) | carry
                    carry = next
                }
                if carry != 0 { t[0] ^= 0x87 }
                b += 16
            }

            var plain = [UInt8](try ecb(Data(blocks[0..<b]), key: dataKey, decrypt: true))
            for j in 0..<plain.count { plain[j] ^= tweaks[j] }
            output.append(contentsOf: plain)

            position = end
            sector += 1
        }
        return output
    }
}

/// AES-128-CTR als Stream (hardwarebeschleunigt über CommonCrypto).
final class AESCTR {
    private let cryptor: CCCryptorRef

    init(key: Data, iv: [UInt8]) throws {
        var ref: CCCryptorRef?
        let status = key.withUnsafeBytes { k in
            iv.withUnsafeBytes { v in
                CCCryptorCreateWithMode(
                    CCOperation(kCCEncrypt),
                    CCMode(kCCModeCTR),
                    CCAlgorithm(kCCAlgorithmAES),
                    CCPadding(ccNoPadding),
                    v.baseAddress,
                    k.baseAddress, key.count,
                    nil, 0, 0,
                    CCModeOptions(kCCModeOptionCTR_BE),
                    &ref
                )
            }
        }
        guard status == kCCSuccess, let ref else { throw CryptoError.failed(status) }
        cryptor = ref
    }

    deinit {
        CCCryptorRelease(cryptor)
    }

    func process(_ input: UnsafeRawPointer, _ output: UnsafeMutableRawPointer, count: Int) throws {
        var moved = 0
        let status = CCCryptorUpdate(cryptor, input, count, output, count, &moved)
        guard status == kCCSuccess else { throw CryptoError.failed(status) }
    }

    func process(_ data: Data) throws -> Data {
        guard !data.isEmpty else { return data }
        var output = Data(count: data.count)
        try output.withUnsafeMutableBytes { out in
            try data.withUnsafeBytes { input in
                try process(input.baseAddress!, out.baseAddress!, count: data.count)
            }
        }
        return output
    }
}
