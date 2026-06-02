cmake_minimum_required(VERSION 3.22)

project(llama_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT DEFINED LLAMA_CPP_SOURCE_DIR)
    message(FATAL_ERROR "LLAMA_CPP_SOURCE_DIR is required")
endif()

add_subdirectory(${LLAMA_CPP_SOURCE_DIR} ${CMAKE_BINARY_DIR}/llama-cpp-build)

add_library(llama-android SHARED llama-android.cpp)

target_include_directories(llama-android PRIVATE
        ${LLAMA_CPP_SOURCE_DIR}/include
        ${LLAMA_CPP_SOURCE_DIR}/ggml/include
        ${LLAMA_CPP_SOURCE_DIR}/ggml/src
        ${LLAMA_CPP_SOURCE_DIR}/src)

target_link_libraries(llama-android PRIVATE
        llama
        android
        log)

