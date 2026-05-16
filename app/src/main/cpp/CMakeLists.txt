cmake_minimum_required(VERSION 3.22.1)
project(gemma_llama)

# ── llama.cpp submodule ────────────────────────────────────────────────────────
# Expected at: app/src/main/cpp/llama.cpp/
# Clone with: git submodule add https://github.com/ggerganov/llama.cpp \
#             app/src/main/cpp/llama.cpp
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp submodule not found at ${LLAMA_CPP_DIR}\n"
        "Run: git submodule update --init --recursive")
endif()

# ── Vulkan ─────────────────────────────────────────────────────────────────────
# Android NDK bundles Vulkan headers; we only need to link vulkan at runtime.
# GGML_VULKAN=ON is passed from Gradle — no need to set it here again.
find_library(VULKAN_LIBRARY vulkan)
if(NOT VULKAN_LIBRARY)
    message(WARNING "Vulkan library not found — GPU acceleration will be disabled")
endif()

# ── Build llama.cpp ────────────────────────────────────────────────────────────
add_subdirectory(${LLAMA_CPP_DIR} llama.cpp_build)

# ── JNI wrapper ───────────────────────────────────────────────────────────────
add_library(gemma_jni SHARED llama_jni.cpp)

target_include_directories(gemma_jni PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

target_link_libraries(gemma_jni
    llama
    ggml
    ${LOG_LIB}
    ${ANDROID_LIB}
    $<$<BOOL:${VULKAN_LIBRARY}>:${VULKAN_LIBRARY}>
)

target_compile_options(gemma_jni PRIVATE
    -O3
    -ffast-math
    -march=armv8.2-a+fp16+dotprod  # NEON + dot-product for arm64
)
