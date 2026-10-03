cmake_minimum_required(VERSION 3.22.1)
project(uac_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(uac_tests
        uac_tests.cpp
        ../../main/cpp/uac/UacDescriptors.cpp)
# Trap on undefined behaviour without needing the sanitizer runtime on the phone.
target_compile_options(uac_tests PRIVATE -Wall -Wextra -fsanitize=undefined -fsanitize-trap=undefined)
target_link_options(uac_tests PRIVATE -fsanitize=undefined -fsanitize-trap=undefined)
