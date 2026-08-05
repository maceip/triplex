cmake_minimum_required(VERSION 3.22.1)
project(triplex_telephony_host_tests LANGUAGES CXX)

enable_testing()

add_executable(transport_lifecycle_test transport_lifecycle_test.cpp)
target_compile_features(transport_lifecycle_test PRIVATE cxx_std_20)
target_compile_options(transport_lifecycle_test PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Werror
)
target_include_directories(transport_lifecycle_test PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../main/cpp"
)

if(NOT DEFINED TRIPLEX_EVIDENCE_OUTPUT)
    set(
        TRIPLEX_EVIDENCE_OUTPUT
        "${CMAKE_BINARY_DIR}/cpp-transport-validation.json"
    )
endif()

add_test(
    NAME triplex_transport_lifecycle
    COMMAND transport_lifecycle_test --output "${TRIPLEX_EVIDENCE_OUTPUT}"
)
