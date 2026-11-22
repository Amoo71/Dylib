/* zstd-Entpacker als eine Übersetzungseinheit (wie zstds build/single_file_libs/zstddeclib-in.c).
 * Die Quellen lädt scripts/fetch_deps.sh nach Vendor/zstd; der Pfad "lib/..." wird hier direkt eingebunden,
 * damit keine andere zstd.h aus dem SDK oder den Suchpfaden dazwischenkommt. */
#define DEBUGLEVEL 0
#define MEM_MODULE
#undef  XXH_NAMESPACE
#define XXH_NAMESPACE ZSTD_
#undef  XXH_PRIVATE_API
#define XXH_PRIVATE_API
#undef  XXH_INLINE_ALL
#define XXH_INLINE_ALL
#define ZSTD_LEGACY_SUPPORT 0
#define ZSTD_TRACE 0
#define ZSTD_DISABLE_ASM 1
#define ZSTD_STATIC_LINKING_ONLY

#include "../Vendor/zstd/lib/zstd.h"

#define ZSTD_DEPS_NEED_MALLOC
#include "../Vendor/zstd/lib/common/zstd_deps.h"

#include "../Vendor/zstd/lib/common/debug.c"
#include "../Vendor/zstd/lib/common/entropy_common.c"
#include "../Vendor/zstd/lib/common/error_private.c"
#include "../Vendor/zstd/lib/common/fse_decompress.c"
#include "../Vendor/zstd/lib/common/zstd_common.c"

#include "../Vendor/zstd/lib/decompress/huf_decompress.c"
#include "../Vendor/zstd/lib/decompress/zstd_ddict.c"
#include "../Vendor/zstd/lib/decompress/zstd_decompress.c"
#include "../Vendor/zstd/lib/decompress/zstd_decompress_block.c"
