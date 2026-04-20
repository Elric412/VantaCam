cmake_minimum_required(VERSION 3.22.1)
project(native_imaging_core)

add_library(native_imaging_core SHARED native_imaging_core.cpp)
target_compile_features(native_imaging_core PRIVATE cxx_std_20)

find_library(log-lib log)

target_link_libraries(native_imaging_core ${log-lib})
