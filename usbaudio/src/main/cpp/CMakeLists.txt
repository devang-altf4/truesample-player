cmake_minimum_required(VERSION 3.22.1)
project(usbaudio C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libusb)

# libusb stays a separate shared library so its LGPL-2.1 terms are easy to honour.
add_library(usb-1.0 SHARED
        ${LIBUSB_ROOT}/libusb/core.c
        ${LIBUSB_ROOT}/libusb/descriptor.c
        ${LIBUSB_ROOT}/libusb/hotplug.c
        ${LIBUSB_ROOT}/libusb/io.c
        ${LIBUSB_ROOT}/libusb/sync.c
        ${LIBUSB_ROOT}/libusb/strerror.c
        ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
        ${LIBUSB_ROOT}/libusb/os/events_posix.c
        ${LIBUSB_ROOT}/libusb/os/threads_posix.c
        ${LIBUSB_ROOT}/libusb/os/linux_netlink.c)
target_include_directories(usb-1.0
        PRIVATE ${LIBUSB_ROOT}/android ${LIBUSB_ROOT}/libusb/os
        PUBLIC ${LIBUSB_ROOT}/libusb)
target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread -Wno-everything)
target_link_libraries(usb-1.0 log)

# libsamplerate (BSD-2-Clause) for DACs that lack the source sample rate.
set(SRC_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libsamplerate)
add_library(samplerate STATIC
        ${SRC_ROOT}/src/samplerate.c
        ${SRC_ROOT}/src/src_sinc.c
        ${SRC_ROOT}/src/src_linear.c
        ${SRC_ROOT}/src/src_zoh.c)
target_include_directories(samplerate PUBLIC ${SRC_ROOT}/include)
target_compile_definitions(samplerate PRIVATE
        ENABLE_SINC_BEST_CONVERTER ENABLE_SINC_MEDIUM_CONVERTER ENABLE_SINC_FAST_CONVERTER
        HAVE_STDBOOL_H CPU_CLIPS_POSITIVE=0 CPU_CLIPS_NEGATIVE=0
        PACKAGE="libsamplerate" VERSION="0.2.2")
target_compile_options(samplerate PRIVATE -O3 -Wno-everything)
set_target_properties(samplerate PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(usbaudio SHARED
        jni_bridge.cpp
        UsbStreamer.cpp
        Log.cpp
        uac/UacDescriptors.cpp)
target_compile_options(usbaudio PRIVATE -Wall -Wextra)
target_link_libraries(usbaudio usb-1.0 samplerate log)
