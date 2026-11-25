# Native isochronous USB transfer engine — see usb_native_streamer.cpp for the
# honesty note on verification status (no NDK toolchain or hardware was
# available to build/test this; it's written from documented Linux usbdevfs
# kernel ABI, not verified end-to-end).
cmake_minimum_required(VERSION 3.22.1)
project("bitperfectusb_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    bitperfectusb_native
    SHARED
    usb_native_streamer.cpp
)

find_library(log-lib log)

target_link_libraries(
    bitperfectusb_native
    ${log-lib}
    c++_shared
)

# Android 15+ phones may use 16 KB memory pages. A library whose segments are only 4 KB-aligned is reported as
# "not 16 KB compatible" and the system warns about the app. This makes the alignment explicit regardless of
# which NDK version builds it. (build.gradle.kts also sets ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES for NDK r27.)
target_link_options(bitperfectusb_native PRIVATE "-Wl,-z,max-page-size=16384")

target_compile_options(bitperfectusb_native PRIVATE -Wall -Wextra -O2)
