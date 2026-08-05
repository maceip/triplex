cmake_minimum_required(VERSION 3.22.1)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

project(triplex-native CXX)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -Wextra -Werror")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -Werror -ffunction-sections -fdata-sections -fvisibility=hidden -fno-exceptions -fno-rtti")

if(ANDROID)
    add_library(triplex-native SHARED
        src/main/cpp/native_runtime.cpp
    )
    
    target_include_directories(triplex-native PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/../native-media/src/main/cpp
    )
    
    target_compile_options(triplex-native PRIVATE
        -ffast-math
        -ftree-vectorize
    )
    
    target_link_options(triplex-native PRIVATE
        -Wl,--gc-sections
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/version.map
        -Wl,--fatal-warnings
        -Wl,--no-undefined
    )
    
    target_link_libraries(triplex-native
        android
        log
        OpenSLES
    )
    
    set_target_properties(triplex-native PROPERTIES
        INTERFACE_LINK_OPTIONS "-Wl,--as-needed"
    )
endif()
