cmake_minimum_required(VERSION 3.22)
project(hccr_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 使用 third_party 目录里的 ncnn Android 预编译(静态库)
set(NCNN_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../third_party/ncnn-android/${ANDROID_ABI})

if(NOT EXISTS ${NCNN_ROOT})
    message(FATAL_ERROR "ncnn-android 找不到: ${NCNN_ROOT}")
endif()

set(ncnn_DIR ${NCNN_ROOT}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(hccr_jni SHARED
    ${CMAKE_CURRENT_LIST_DIR}/hccr_jni.cc
    ${CMAKE_CURRENT_LIST_DIR}/preprocess.c
)
target_include_directories(hccr_jni PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_link_libraries(hccr_jni
    PRIVATE
        ncnn
        android
        log
)