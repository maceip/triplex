cmake_minimum_required(VERSION 3.22.1)
project(triplex-native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

get_filename_component(
    TRIPLEX_ANDROID_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../.."
    ABSOLUTE
)
set(
    TRIPLEX_NATIVE_STAGE
    "${TRIPLEX_ANDROID_ROOT}/.native-deps/install/${ANDROID_ABI}"
)

if(NOT EXISTS "${TRIPLEX_NATIVE_STAGE}/lib/libtriplex_native_media.so")
    message(FATAL_ERROR
        "Rust media runtime is missing for ${ANDROID_ABI}. Run "
        "apps/android/scripts/prepare-native.sh ${ANDROID_ABI} first."
    )
endif()

add_library(triplex_native_media SHARED IMPORTED)
set_target_properties(triplex_native_media PROPERTIES
    IMPORTED_LOCATION "${TRIPLEX_NATIVE_STAGE}/lib/libtriplex_native_media.so"
)

add_library(triplex-native SHARED
    native_runtime.cpp
)

target_include_directories(triplex-native PRIVATE
    "${TRIPLEX_ANDROID_ROOT}/native-media/include"
)

target_compile_options(triplex-native PRIVATE
    -Wall
    -Werror
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)

target_link_options(triplex-native PRIVATE
    -Wl,--gc-sections
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/version.map
    -Wl,--no-undefined
    # Android 15+ devices may use 16 KB memory pages.
    -Wl,-z,max-page-size=16384
)

target_link_libraries(triplex-native
    triplex_native_media
    android
    log
)
