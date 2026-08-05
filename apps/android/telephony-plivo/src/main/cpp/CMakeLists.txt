cmake_minimum_required(VERSION 3.22.1)
project(triplex_telephony LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "telephony-plivo is an Android-only target")
endif()

get_filename_component(
    TRIPLEX_ANDROID_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../.."
    ABSOLUTE
)
set(
    TRIPLEX_NATIVE_STAGE
    "${TRIPLEX_ANDROID_ROOT}/.native-deps/install/${ANDROID_ABI}"
    CACHE PATH
    "Prepared PJSIP, Mbed TLS, and native-media artifacts"
)

if(NOT EXISTS "${TRIPLEX_NATIVE_STAGE}/manifest.sha256")
    message(FATAL_ERROR
        "Native stage is missing for ${ANDROID_ABI}. Run "
        "apps/android/scripts/prepare-native.sh ${ANDROID_ABI} first."
    )
endif()
if(NOT EXISTS "${TRIPLEX_NATIVE_STAGE}/lib/libtriplex_native_media.so")
    message(FATAL_ERROR "Pinned native-media shared library is missing")
endif()

file(GLOB TRIPLEX_PJSIP_ARCHIVES CONFIGURE_DEPENDS
    "${TRIPLEX_NATIVE_STAGE}/lib/lib*.a"
)
if(NOT TRIPLEX_PJSIP_ARCHIVES)
    message(FATAL_ERROR "No prepared PJSIP/Mbed TLS archives found")
endif()

add_library(triplex_native_media SHARED IMPORTED)
set_target_properties(triplex_native_media PROPERTIES
    IMPORTED_LOCATION "${TRIPLEX_NATIVE_STAGE}/lib/libtriplex_native_media.so"
)

add_library(triplex_telephony SHARED
    pjsip_engine.cpp
    rtp_tap_transport.cpp
    triplex_pjsip_jni.cpp
)
target_compile_features(triplex_telephony PRIVATE cxx_std_20)
target_compile_definitions(triplex_telephony PRIVATE
    PJ_AUTOCONF=1
    PJ_IS_BIG_ENDIAN=0
    PJ_IS_LITTLE_ENDIAN=1
)
target_include_directories(triplex_telephony PRIVATE
    "${TRIPLEX_NATIVE_STAGE}/include"
    "${TRIPLEX_ANDROID_ROOT}/native-media/include"
)
target_link_options(triplex_telephony PRIVATE
    # Android 15+ devices may use 16 KB memory pages.
    -Wl,-z,max-page-size=16384
)
target_link_libraries(triplex_telephony PRIVATE
    triplex_native_media
    "-Wl,--start-group"
    ${TRIPLEX_PJSIP_ARCHIVES}
    "-Wl,--end-group"
    android
    log
    m
    atomic
)
