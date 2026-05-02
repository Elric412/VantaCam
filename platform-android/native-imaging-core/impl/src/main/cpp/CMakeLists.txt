# ─────────────────────────────────────────────────────────────────────────────
#  LeicaCam Native Imaging Core
#  Builds two shared libs:
#    libnative_imaging_core.so   — the long-standing physics-grounded engine
#    libproxdr_engine.so         — ProXDR v3 (adaptive AI HDR pipeline, drop-in)
# ─────────────────────────────────────────────────────────────────────────────

cmake_minimum_required(VERSION 3.22.1)
project(native_imaging_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Common compiler flags ───────────────────────────────────────────────────────
if (CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_options(-O3 -ffast-math -ffunction-sections -fdata-sections
                        -fvisibility=hidden -fvisibility-inlines-hidden
                        -DNDEBUG)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        add_compile_options(-march=armv8-a+simd)
    elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
        add_compile_options(-mfpu=neon-vfpv4 -mfloat-abi=softfp)
    endif()
endif()

add_compile_options(-Wall -Wextra -Wno-unused-parameter -Wno-unused-variable
                    -Wno-sign-compare -Wno-unused-function)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

# ─── 1. Long-standing native_imaging_core library ────────────────────────────
add_library(native_imaging_core SHARED native_imaging_core.cpp)
target_compile_features(native_imaging_core PRIVATE cxx_std_20)
target_link_libraries(native_imaging_core ${log-lib})

# ─── 2. ProXDR v3 engine ─────────────────────────────────────────────────────
# The full v3 pipeline lives under cpp/proxdr/. This is dropped-in verbatim
# from the upstream "improved/" tarball; do not modify the .cpp/.h files.
# Forwarded into Kotlin via :imaging-pipeline:impl ProXdrV3Engine.kt.
set(PROXDR_ROOT  "${CMAKE_CURRENT_SOURCE_DIR}/proxdr")
set(PROXDR_SRCS
    ${PROXDR_ROOT}/src/scene_analyzer.cpp
    ${PROXDR_ROOT}/src/adaptive_scene.cpp
    ${PROXDR_ROOT}/src/fusion_lm.cpp
    ${PROXDR_ROOT}/src/dcg_hdr.cpp
    ${PROXDR_ROOT}/src/wavelet_nr.cpp
    ${PROXDR_ROOT}/src/highlight_recovery.cpp
    ${PROXDR_ROOT}/src/tone_lm.cpp
    ${PROXDR_ROOT}/src/color_lm.cpp
    ${PROXDR_ROOT}/src/gain_map.cpp
    ${PROXDR_ROOT}/src/neural_hooks.cpp
    ${PROXDR_ROOT}/src/pipeline.cpp
    ${PROXDR_ROOT}/jni/proxdr_jni.cpp
)

add_library(proxdr_engine SHARED ${PROXDR_SRCS})
# ProXDR is C++17 (its tar specifies cxx_std_17); pin it explicitly so the
# project-wide C++20 default doesn't change ABI for the dropped-in code.
target_compile_features(proxdr_engine PRIVATE cxx_std_17)

target_include_directories(proxdr_engine PUBLIC
    ${PROXDR_ROOT}/include
    ${PROXDR_ROOT}/src
)

target_link_libraries(proxdr_engine PRIVATE
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
)

# Strip release builds of ProXDR (target ~600-900 KB per ABI per docs)
if (CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET proxdr_engine POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:proxdr_engine>
        COMMENT "Stripping symbols for ProXDR v3 release build")
endif()
