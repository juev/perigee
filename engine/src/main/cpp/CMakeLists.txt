cmake_minimum_required(VERSION 3.22)
project(perigee_engine LANGUAGES C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

get_filename_component(PERIGEE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(CRENGINE_DIR "${PERIGEE_ROOT}/third_party/crengine")

include(cmake/fetch.cmake)
include(cmake/deps.cmake)
include(cmake/crengine.cmake)

add_library(perigee_engine SHARED jni/engine_jni.cpp)
target_compile_features(perigee_engine PRIVATE cxx_std_17)
# Optimised in debug builds too: every page drawn passes each of its pixels through this code.
target_compile_options(perigee_engine PRIVATE -O2)
target_link_libraries(perigee_engine PRIVATE crengine log jnigraphics)
# 16 KB page size support, required by Google Play for target API 35+.
target_link_options(perigee_engine PRIVATE "-Wl,-z,max-page-size=16384")
