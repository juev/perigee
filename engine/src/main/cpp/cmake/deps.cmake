# Third-party libraries crengine links against. Versions and archives follow
# koreader-base (thirdparty/*/CMakeLists.txt), so the engine behaves like KOReader's.

include(ExternalProject)

# zlib comes with the NDK.
find_package(ZLIB REQUIRED)

# --- zstd
perigee_fetch(ZSTD
    https://github.com/facebook/zstd/releases/download/v1.5.7/zstd-1.5.7.tar.gz
    eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3 zstd-1.5.7)
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ZSTD_LEGACY_SUPPORT OFF CACHE BOOL "" FORCE)
set(ZSTD_MULTITHREAD_SUPPORT OFF CACHE BOOL "" FORCE)
add_subdirectory(${ZSTD_SOURCE_DIR}/build/cmake zstd EXCLUDE_FROM_ALL)

# --- libpng
perigee_fetch(PNG
    https://github.com/pnggroup/libpng/archive/refs/tags/v1.6.59.tar.gz
    2540302a1844ad2b2b501977abecfa850f265f97b78f065a712ab4074a89f5b5 libpng-1.6.59)
set(PNG_SHARED OFF CACHE BOOL "" FORCE)
set(PNG_STATIC ON CACHE BOOL "" FORCE)
set(PNG_TESTS OFF CACHE BOOL "" FORCE)
set(PNG_TOOLS OFF CACHE BOOL "" FORCE)
set(PNG_FRAMEWORK OFF CACHE BOOL "" FORCE)
set(SKIP_INSTALL_ALL ON CACHE BOOL "" FORCE)
add_subdirectory(${PNG_SOURCE_DIR} libpng EXCLUDE_FROM_ALL)

# --- libjpeg-turbo (does not support add_subdirectory, hence ExternalProject)
perigee_fetch(JPEG
    https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz
    6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e libjpeg-turbo-3.2.0)
set(JPEG_PREFIX ${CMAKE_BINARY_DIR}/jpeg-install)
# x86 SIMD needs nasm; NEON is plain C intrinsics.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(JPEG_SIMD ON)
else()
    set(JPEG_SIMD OFF)
endif()
ExternalProject_Add(jpeg_external
    SOURCE_DIR ${JPEG_SOURCE_DIR}
    CMAKE_GENERATOR ${CMAKE_GENERATOR}
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        "-DCMAKE_C_FLAGS=${PERIGEE_PREFIX_MAP}"
        "-DCMAKE_ASM_FLAGS=${PERIGEE_PREFIX_MAP}"
        -DCMAKE_INSTALL_PREFIX=${JPEG_PREFIX}
        -DCMAKE_INSTALL_LIBDIR=lib
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=OFF
        -DWITH_TESTS=OFF
        -DWITH_TOOLS=OFF
        -DWITH_SIMD=${JPEG_SIMD}
    BUILD_BYPRODUCTS ${JPEG_PREFIX}/lib/libjpeg.a
)
file(MAKE_DIRECTORY ${JPEG_PREFIX}/include)
add_library(jpeg STATIC IMPORTED GLOBAL)
set_target_properties(jpeg PROPERTIES
    IMPORTED_LOCATION ${JPEG_PREFIX}/lib/libjpeg.a
    INTERFACE_INCLUDE_DIRECTORIES ${JPEG_PREFIX}/include)
add_dependencies(jpeg jpeg_external)

# --- libwebp
perigee_fetch(WEBP
    https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz
    e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564 libwebp-1.6.0)
foreach(OPT ANIM_UTILS CWEBP DWEBP EXTRAS GIF2WEBP IMG2WEBP LIBWEBPMUX VWEBP WEBPINFO WEBPMUX)
    set(WEBP_BUILD_${OPT} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory(${WEBP_SOURCE_DIR} libwebp EXCLUDE_FROM_ALL)

# --- FreeType
perigee_fetch(FREETYPE
    https://github.com/freetype/freetype/archive/refs/tags/VER-2-14-3.tar.gz
    dc49de6b01a266eef4876a4dd34d9842c475d3e28ff2eff63bd2fb760ab56261 freetype-VER-2-14-3)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BROTLI ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "" FORCE)
set(FT_DISABLE_PNG ON CACHE BOOL "" FORCE)
set(FT_REQUIRE_ZLIB ON CACHE BOOL "" FORCE)
add_subdirectory(${FREETYPE_SOURCE_DIR} freetype EXCLUDE_FROM_ALL)

# --- HarfBuzz, built from its single-file amalgamation
perigee_fetch(HARFBUZZ
    https://github.com/harfbuzz/harfbuzz/releases/download/14.5.1/harfbuzz-14.5.1.tar.xz
    7e2fa4e8c7c98e8d8140671f5772542afaaa6acccfbd746506886b6d85f7f8d6 harfbuzz-14.5.1)
add_library(harfbuzz STATIC ${HARFBUZZ_SOURCE_DIR}/src/harfbuzz.cc)
target_compile_features(harfbuzz PRIVATE cxx_std_17)
target_compile_options(harfbuzz PRIVATE -fno-exceptions -fno-rtti -fno-threadsafe-statics)
target_compile_definitions(harfbuzz PRIVATE HAVE_FREETYPE=1 HAVE_PTHREAD=1 HB_NO_PAINT HB_NO_COLOR HB_NO_META HB_NO_STYLE)
target_include_directories(harfbuzz PUBLIC ${HARFBUZZ_SOURCE_DIR}/src)
target_link_libraries(harfbuzz PUBLIC freetype)

# --- FriBidi (meson/autotools upstream; the release archive ships generated tables)
perigee_fetch(FRIBIDI
    https://github.com/fribidi/fribidi/releases/download/v1.0.17/fribidi-1.0.17.tar.xz
    6949dcde27d41cebad1fd741fcafc36d55a1020d2d872d4a6eb3914caabbada2 fribidi-1.0.17)
set(FRIBIDI_GEN ${CMAKE_BINARY_DIR}/fribidi-gen)
set(PACKAGE fribidi)
set(PACKAGE_NAME "GNU FriBidi")
set(PACKAGE_BUGREPORT "https://github.com/fribidi/fribidi/issues/new")
set(FRIBIDI_VERSION 1.0.17)
set(FRIBIDI_MAJOR_VERSION 1)
set(FRIBIDI_MINOR_VERSION 0)
set(FRIBIDI_MICRO_VERSION 17)
set(FRIBIDI_INTERFACE_VERSION 4)
set(SIZEOF_INT 4)
set(FRIBIDI_MSVC_BUILD_PLACEHOLDER "#undef FRIBIDI_BUILT_WITH_MSVC")
set(configure_input "generated by CMake")
configure_file(${FRIBIDI_SOURCE_DIR}/lib/fribidi-config.h.in ${FRIBIDI_GEN}/fribidi-config.h @ONLY)
file(WRITE ${FRIBIDI_GEN}/config.h
"#define HAVE_STRINGIZE 1\n#define HAVE_STDLIB_H 1\n#define HAVE_STRING_H 1\n#define HAVE_MEMORY_H 1\n#define HAVE_STRINGS_H 1\n#define HAVE_MEMSET 1\n#define HAVE_MEMMOVE 1\n#define HAVE_STRDUP 1\n#define STDC_HEADERS 1\n")
set(FRIBIDI_SRCS)
foreach(F fribidi fribidi-arabic fribidi-bidi fribidi-bidi-types fribidi-char-sets
        fribidi-char-sets-cap-rtl fribidi-char-sets-cp1255 fribidi-char-sets-cp1256
        fribidi-char-sets-iso8859-6 fribidi-char-sets-iso8859-8 fribidi-char-sets-utf8
        fribidi-deprecated fribidi-joining fribidi-joining-types fribidi-mirroring
        fribidi-brackets fribidi-run fribidi-shape)
    list(APPEND FRIBIDI_SRCS ${FRIBIDI_SOURCE_DIR}/lib/${F}.c)
endforeach()
add_library(fribidi STATIC ${FRIBIDI_SRCS})
target_compile_definitions(fribidi PRIVATE HAVE_CONFIG_H PUBLIC FRIBIDI_LIB_STATIC)
target_include_directories(fribidi PUBLIC ${FRIBIDI_SOURCE_DIR}/lib ${FRIBIDI_GEN})

# --- libunibreak
perigee_fetch(UNIBREAK
    https://github.com/adah1972/libunibreak/releases/download/libunibreak_7_0/libunibreak-7.0.tar.gz
    8c9a6e121736cd0d5c890ae3ae96f3f4010a19aa040f1dbded833a62a87717d3 libunibreak-7.0)
set(UNIBREAK_SRCS)
foreach(F unibreakbase unibreakdef linebreak linebreakdata linebreakdef eastasianwidthdef emojidef graphemebreak wordbreak)
    list(APPEND UNIBREAK_SRCS ${UNIBREAK_SOURCE_DIR}/src/${F}.c)
endforeach()
add_library(unibreak STATIC ${UNIBREAK_SRCS})
target_include_directories(unibreak PUBLIC ${UNIBREAK_SOURCE_DIR}/src)

# --- utf8proc
perigee_fetch(UTF8PROC
    https://github.com/JuliaStrings/utf8proc/archive/refs/tags/v2.12.0.tar.gz
    f564011d38b2888d583d510b08e69ffa15aa117155db1b9b49ef1dfe1fa25111 utf8proc-2.12.0)
add_library(utf8proc STATIC ${UTF8PROC_SOURCE_DIR}/utf8proc.c)
target_compile_definitions(utf8proc PUBLIC UTF8PROC_STATIC)
target_include_directories(utf8proc PUBLIC ${UTF8PROC_SOURCE_DIR})

# --- xxHash
perigee_fetch(XXHASH
    https://github.com/Cyan4973/xxHash/archive/refs/tags/v0.8.4.tar.gz
    5738270935e7c3d38a79b3adf7c9692566ce7895a25f67de43ad52ab504acd32 xxHash-0.8.4)
add_library(xxhash STATIC ${XXHASH_SOURCE_DIR}/xxhash.c)
target_include_directories(xxhash PUBLIC ${XXHASH_SOURCE_DIR})
