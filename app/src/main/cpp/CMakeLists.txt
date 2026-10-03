cmake_minimum_required(VERSION 3.22.1)
project(decoder C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(decoder SHARED decoder_jni.cpp)
target_include_directories(decoder PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/dr_libs)
target_compile_options(decoder PRIVATE -O2)
target_link_libraries(decoder log)
