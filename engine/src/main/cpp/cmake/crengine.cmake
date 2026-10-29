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

# Changes Perigee carries on top of crengine, as patches in ../patches. The files a patch touches are
# copied into the build tree and patched there, and those copies are compiled in place of the originals:
# the submodule stays as upstream has it. A patch may change source files only, not headers, so that
# every file of the engine sees the same declarations.
find_package(Git REQUIRED)
set(CRENGINE_PATCHED ${CMAKE_BINARY_DIR}/crengine-patched)
file(GLOB CRENGINE_PATCHES ${CMAKE_CURRENT_LIST_DIR}/../patches/*.patch)
list(SORT CRENGINE_PATCHES)
file(REMOVE_RECURSE ${CRENGINE_PATCHED})
foreach(PATCH ${CRENGINE_PATCHES})
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${PATCH})
    file(STRINGS ${PATCH} TARGETS REGEX "^\\+\\+\\+ b/")
    foreach(T ${TARGETS})
        string(REGEX REPLACE "^\\+\\+\\+ b/" "" F "${T}")
        if(NOT F MATCHES "\\.cpp$")
            message(FATAL_ERROR "${PATCH} changes ${F}: patches may change source files only")
        endif()
        if(NOT EXISTS ${CRENGINE_PATCHED}/${F})
            configure_file(${CRENGINE_DIR}/${F} ${CRENGINE_PATCHED}/${F} COPYONLY)
            list(TRANSFORM CRE_SRCS REPLACE "^${CRENGINE_DIR}/${F}$" "${CRENGINE_PATCHED}/${F}")
        endif()
    endforeach()
    # Outside any repository, git apply works as patch does; the build tree may be inside one.
    execute_process(
        COMMAND ${CMAKE_COMMAND} -E env GIT_CEILING_DIRECTORIES=${CMAKE_BINARY_DIR}
                ${GIT_EXECUTABLE} apply --whitespace=nowarn ${PATCH}
        WORKING_DIRECTORY ${CRENGINE_PATCHED}
        RESULT_VARIABLE PATCH_RESULT
        ERROR_VARIABLE PATCH_ERROR)
    if(NOT PATCH_RESULT EQUAL 0)
        message(FATAL_ERROR "${PATCH} does not apply to crengine: ${PATCH_ERROR}")
    endif()
endforeach()

add_library(crengine STATIC ${CRE_SRCS})
# A patched copy finds the headers next to the original ("../include/...") through this path.
target_include_directories(crengine PRIVATE ${CRENGINE_DIR}/crengine/src)
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
