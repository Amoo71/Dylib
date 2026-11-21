"""Selbsttest der Engine: Python, capstone, lz4 und SPIRV-Cross im Prozess."""
import struct, sys


def _minimal_compute_spirv():
    """Kleinster gültiger SPIR-V-Compute-Shader (void main() {})."""
    def op(code, *words):
        return [((len(words) + 1) << 16) | code, *words]
    main_word = struct.unpack('<I', b'main')[0]
    words = [0x07230203, 0x00010000, 0, 5, 0]
    words += op(17, 1)                      # OpCapability Shader
    words += op(14, 0, 1)                   # OpMemoryModel Logical GLSL450
    words += op(15, 5, 1, main_word, 0)     # OpEntryPoint GLCompute %1 "main"
    words += op(16, 1, 17, 1, 1, 1)         # OpExecutionMode %1 LocalSize 1 1 1
    words += op(19, 2)                      # %2 = OpTypeVoid
    words += op(33, 3, 2)                   # %3 = OpTypeFunction %2
    words += op(54, 2, 1, 0, 3)             # %1 = OpFunction %2 None %3
    words += op(248, 4)                     # %4 = OpLabel
    words += op(253)                        # OpReturn
    words += op(56)                         # OpFunctionEnd
    return struct.pack(f'<{len(words)}I', *words)


def run(_args=None):
    result = {'python': sys.version.split()[0], 'platform': sys.platform}

    import capstone
    md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
    code = bytes.fromhex('1f2003d5' 'e0031faa' 'c0035fd6')   # nop; mov x0, xzr; ret
    insns = [f'{i.mnemonic} {i.op_str}'.strip() for i in md.disasm(code, 0)]
    assert insns == ['nop', 'mov x0, xzr', 'ret'], insns
    result['capstone'] = '.'.join(map(str, capstone.cs_version()[:2]))

    import lz4.block
    data = b'NSPiOS ' * 4096
    packed = lz4.block.compress(data, store_size=False)
    assert lz4.block.decompress(packed, uncompressed_size=len(data)) == data
    result['lz4'] = f'{len(data)} -> {len(packed)} B'

    from nspios_engine import native
    msl = native.spirv_to_msl(_minimal_compute_spirv())
    assert 'kernel void main0' in msl, msl[:200]
    result['spirv_cross'] = 'ok'
    return result
