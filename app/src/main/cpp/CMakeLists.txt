cmake_minimum_required(VERSION 3.22.1)
project(decoder C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
# Audio-only FFmpeg (LGPL-2.1), built by scripts/build-ffmpeg.sh.
set(FFMPEG ${THIRD_PARTY}/ffmpeg/android/${ANDROID_ABI})
if(NOT EXISTS ${FFMPEG}/lib/libavcodec.a)
    message(FATAL_ERROR "FFmpeg for ${ANDROID_ABI} is missing: run scripts/build-ffmpeg.sh")
endif()

add_library(decoder SHARED decoder_jni.cpp)
target_include_directories(decoder PRIVATE ${THIRD_PARTY}/dr_libs ${FFMPEG}/include)
target_compile_options(decoder PRIVATE -O2)
target_link_libraries(decoder
        ${FFMPEG}/lib/libavformat.a
        ${FFMPEG}/lib/libavcodec.a
        ${FFMPEG}/lib/libswresample.a
        ${FFMPEG}/lib/libavutil.a
        log m)
# Only the decoder entry points are needed from the static FFmpeg archives.
target_link_options(decoder PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
