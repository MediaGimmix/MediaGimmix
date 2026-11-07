cmake_minimum_required(VERSION 3.22.1)
project(nativeaudio CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(nativeaudio SHARED native_audio.cpp)

# Lets API 28 AAudio calls (usage, content type) sit behind __builtin_available with minSdk 26.
target_compile_definitions(nativeaudio PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_compile_options(nativeaudio PRIVATE -Wall -Werror=unguarded-availability)

target_link_libraries(nativeaudio aaudio OpenSLES log)
