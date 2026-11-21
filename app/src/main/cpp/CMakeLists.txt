cmake_minimum_required(VERSION 3.18)
project(llamaport_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT DEFINED LLAMA_INCLUDE_DIR)
    message(FATAL_ERROR "LLAMA_INCLUDE_DIR is not set")
endif()
if (NOT DEFINED GGML_INCLUDE_DIR)
    message(FATAL_ERROR "GGML_INCLUDE_DIR is not set")
endif()
if (NOT DEFINED LLAMA_LIB)
    message(FATAL_ERROR "LLAMA_LIB is not set")
endif()
if (NOT DEFINED MTMD_INCLUDE_DIR)
    message(FATAL_ERROR "MTMD_INCLUDE_DIR is not set")
endif()
if (NOT DEFINED MTMD_LIB)
    message(FATAL_ERROR "MTMD_LIB is not set")
endif()
if (NOT DEFINED COMMON_INCLUDE_DIR)
    message(FATAL_ERROR "COMMON_INCLUDE_DIR is not set")
endif()
if (NOT DEFINED COMMON_LIB)
    message(FATAL_ERROR "COMMON_LIB is not set")
endif()

add_library(llamaport SHARED llama_jni.cpp)

target_include_directories(llamaport PRIVATE
    ${LLAMA_INCLUDE_DIR}
    ${GGML_INCLUDE_DIR}
    ${MTMD_INCLUDE_DIR}
    ${COMMON_INCLUDE_DIR}
)

target_compile_options(llamaport PRIVATE
    -O2
    -fvisibility=hidden
    -Wall
    -Wextra
)

target_link_libraries(llamaport PRIVATE
    ${LLAMA_LIB}
    ${MTMD_LIB}
    ${COMMON_LIB}
    log
)

if (DEFINED QUICKJS_DIR)
    add_library(quickjs-jni SHARED
        quickjs_jni.cpp
        ${QUICKJS_DIR}/cutils.c
        ${QUICKJS_DIR}/libregexp.c
        ${QUICKJS_DIR}/libunicode.c
        ${QUICKJS_DIR}/quickjs.c
        ${QUICKJS_DIR}/xsum.c
    )
    target_include_directories(quickjs-jni PRIVATE ${QUICKJS_DIR})
    target_compile_definitions(quickjs-jni PRIVATE _GNU_SOURCE)
    target_compile_options(quickjs-jni PRIVATE
        -O2
        -fvisibility=hidden
        -Wno-unused-parameter
        -Wno-sign-compare
        -Wno-implicit-fallthrough
        -Wno-missing-field-initializers
    )
    target_link_libraries(quickjs-jni PRIVATE log m)
endif()
