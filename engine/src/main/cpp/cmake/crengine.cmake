# crengine (KOReader fork) as a static library. The source list and the feature
# switches mirror third_party/crengine/CMakeLists.txt; that file is not used
# directly because it resolves dependencies through pkg-config.

set(USE_ZLIB 1)
set(USE_ZSTD 1)
set(USE_FREETYPE 1)
set(USE_HARFBUZZ 1)
set(USE_FRIBIDI 1)
set(USE_LIBUNIBREAK 1)
set(USE_UTF8PROC 1)
set(USE_LIBPNG 1)
set(USE_LIBJPEG 1)
set(USE_LIBWEBP 1)
set(USE_GIF 1)
set(USE_MATHML 1)
set(USE_OWN_MEM_MAN 1)
set(HAVE_OFF64_T 1)
# Bundled in the crengine tree: antiword (GPL-2.0-or-later), chmlib (LGPL-2.1).
set(USE_CHMLIB 1)
set(USE_ANTIWORD 1)
# Not built yet: md4c (Markdown), LunaSVG (SVG images), SRELL (regex search).
set(USE_MD4C 0)
set(USE_LUNASVG 0)
set(USE_NANOSVG 0)
set(USE_STB_IMAGE 0)
set(USE_SRELL 0)
set(USE_FONTCONFIG 0)

set(CRENGINE_GEN ${CMAKE_BINARY_DIR}/crengine-gen)
configure_file(${CRENGINE_DIR}/crengine/include/crsetup.h.cmake ${CRENGINE_GEN}/crsetup.h @ONLY)

set(CRE_SRCS)
foreach(F
        qimagescale/qimagescale.cpp
        src/chmfmt.cpp src/cp_stats.cpp src/crtxtenc.cpp src/docxfmt.cpp src/drawutil.cpp
        src/epubfmt.cpp src/fb3fmt.cpp src/hist.cpp src/hyphman.cpp src/lstridmap.cpp
        src/lvdocview.cpp src/lvdrawbuf.cpp src/lvfnt.cpp src/lvfntman.cpp src/lvimg.cpp
        src/lvmemman.cpp src/lvopc.cpp src/lvpagesplitter.cpp src/lvrend.cpp src/lvstream.cpp
        src/lvstring.cpp src/lvstsheet.cpp src/lvstyles.cpp src/lvtextfm.cpp src/lvtinydom.cpp
        src/lvxml.cpp src/mathml.cpp src/mdfmt.cpp src/odtfmt.cpp src/odxutil.cpp src/pdbfmt.cpp
        src/props.cpp src/renderutil.cpp src/rtfimp.cpp src/textlang.cpp src/txtselector.cpp
        src/wordfmt.cpp)
    list(APPEND CRE_SRCS ${CRENGINE_DIR}/crengine/${F})
endforeach()

add_library(crengine STATIC ${CRE_SRCS})
target_compile_features(crengine PUBLIC cxx_std_17)
target_compile_options(crengine PRIVATE -ftabstop=4 -Wall)
target_compile_definitions(crengine PRIVATE $<$<CONFIG:Debug>:_DEBUG=1> $<$<CONFIG:Debug>:DEBUG=1>)
target_include_directories(crengine PUBLIC ${CRENGINE_DIR}/crengine/include ${CRENGINE_GEN})
# The bundled CMake files register lint targets through a helper of the upstream
# top-level project and derive PIC from BUILD_SHARED_LIBS.
function(add_lint_targets)
endfunction()
add_subdirectory(${CRENGINE_DIR}/thirdparty/antiword antiword EXCLUDE_FROM_ALL)
add_subdirectory(${CRENGINE_DIR}/thirdparty/chmlib chmlib EXCLUDE_FROM_ALL)
set_target_properties(antiword chmlib PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(crengine PRIVATE CR3_ANTIWORD_PATCH=1)
target_compile_definitions(antiword PRIVATE CR3_ANTIWORD_PATCH=1 $<$<CONFIG:Debug>:DEBUG=1>)

target_link_libraries(crengine PUBLIC
    antiword chmlib
    freetype harfbuzz fribidi unibreak utf8proc xxhash
    png_static jpeg webpdemux webpdecoder libzstd_static ZLIB::ZLIB)
