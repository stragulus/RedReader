cmake_minimum_required(VERSION 3.22.1)

# Native part of redreader-translate:
#  - bergamot-translator (Marian NMT inference, int8 GEMM via ruy on ARM / intgemm on x86)
#  - CLD2 language detection (Chromium table set, compiled in, no model files)
#
# Everything is built from the pinned submodules in third_party/.

project(redreader_translate C CXX)

set(THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party")

if(NOT EXISTS "${THIRD_PARTY_DIR}/bergamot-translator/3rd_party/marian-dev/CMakeLists.txt"
		OR NOT EXISTS "${THIRD_PARTY_DIR}/cld2/public/compact_lang_det.h")
	message(FATAL_ERROR
		"redreader-translate: native submodules are missing. Run: git submodule update --init --recursive")
endif()

# Always build optimised: Marian built at -O0 is unusably slow.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# Note: LTO was tried and only saved ~0.2% (16 KB), so it's not enabled.

# Reproducible-build friendly flags (F-Droid verifies builds).
add_compile_options(
	"-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=."
	-Wno-builtin-macro-redefined
	-ffunction-sections
	-fdata-sections
	-fvisibility=hidden
	$<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>
)
# Silence the (very many) warnings from third-party code.
add_compile_options(-w)

string(APPEND CMAKE_SHARED_LINKER_FLAGS
	" -Wl,--build-id=none -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--hash-style=gnu"
	" -Wl,-z,relro -Wl,-z,now -Wl,--sort-common"
	# 16 KiB page size support (required for Android 15+ devices with 16k pages)
	" -Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# bergamot-translator / marian configuration (mirrors the configuration used by the
# F-Droid "Offline Translator" app, which builds the same fork for Android)
# ---------------------------------------------------------------------------
set(GIT_SUBMODULE OFF CACHE BOOL "Don't touch submodules during the build" FORCE)
set(USE_WASM_COMPATIBLE_SOURCE OFF CACHE BOOL "" FORCE)
set(COMPILE_WASM OFF CACHE BOOL "" FORCE)
set(COMPILE_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(COMPILE_PYTHON OFF CACHE BOOL "" FORCE)
set(USE_THREADS OFF CACHE BOOL "" FORCE)
set(USE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(USE_MKL OFF CACHE BOOL "" FORCE)
set(USE_FBGEMM OFF CACHE BOOL "" FORCE)
set(USE_NCCL OFF CACHE BOOL "" FORCE)
set(USE_CUDNN OFF CACHE BOOL "" FORCE)
set(COMPILE_CUDA OFF CACHE BOOL "" FORCE)
set(USE_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(USE_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(ENABLE_CACHE_STATS OFF CACHE BOOL "" FORCE)
set(INTGEMM_DONT_BUILD_TESTS ON CACHE BOOL "" FORCE)
set(SSPLIT_COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(SSPLIT_USE_INTERNAL_PCRE2 ON CACHE BOOL "" FORCE)
set(AUTO_CPU_DETECT OFF CACHE BOOL "" FORCE)
# sentencepiece: we don't want protobuf from the system, nor the spm executables.
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)

# Float GEMM: ruy on all ABIs (there's no BLAS on Android). Int8 GEMM: ruy on ARM, intgemm on
# x86 (marian picks intgemm automatically on non-ARM; it dispatches SSSE3/AVX2/AVX512 at runtime).
set(USE_RUY ON CACHE BOOL "" FORCE)
set(USE_RUY_SGEMM ON CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
	set(BUILD_ARCH "armv8-a" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
	set(BUILD_ARCH "armv7-a" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
	# The x86_64 Android ABI guarantees up to SSE4.2 (but not AVX).
	set(BUILD_ARCH "x86-64" CACHE STRING "" FORCE)
endif()

add_subdirectory("${THIRD_PARTY_DIR}/bergamot-translator" bergamot-translator EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# CLD2 (Chromium table set: ~80 languages, ~1.5 MB of tables)
# ---------------------------------------------------------------------------
set(CLD2_DIR "${THIRD_PARTY_DIR}/cld2/internal")
add_library(cld2 STATIC
	${CLD2_DIR}/cldutil.cc
	${CLD2_DIR}/cldutil_shared.cc
	${CLD2_DIR}/compact_lang_det.cc
	${CLD2_DIR}/compact_lang_det_hint_code.cc
	${CLD2_DIR}/compact_lang_det_impl.cc
	${CLD2_DIR}/debug_empty.cc
	${CLD2_DIR}/fixunicodevalue.cc
	${CLD2_DIR}/generated_entities.cc
	${CLD2_DIR}/generated_language.cc
	${CLD2_DIR}/generated_ulscript.cc
	${CLD2_DIR}/getonescriptspan.cc
	${CLD2_DIR}/lang_script.cc
	${CLD2_DIR}/offsetmap.cc
	${CLD2_DIR}/scoreonescriptspan.cc
	${CLD2_DIR}/tote.cc
	${CLD2_DIR}/utf8statetable.cc
	${CLD2_DIR}/cld_generated_cjk_uni_prop_80.cc
	${CLD2_DIR}/cld2_generated_cjk_compatible.cc
	${CLD2_DIR}/cld_generated_cjk_delta_bi_4.cc
	${CLD2_DIR}/generated_distinct_bi_0.cc
	${CLD2_DIR}/cld2_generated_quadchrome_2.cc
	${CLD2_DIR}/cld2_generated_deltaoctachrome.cc
	${CLD2_DIR}/cld2_generated_distinctoctachrome.cc
	${CLD2_DIR}/cld_generated_score_quad_octa_2.cc
)
set_target_properties(cld2 PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(cld2 PRIVATE -Wno-narrowing)
target_include_directories(cld2 PUBLIC "${THIRD_PARTY_DIR}/cld2/public" "${CLD2_DIR}")

# ---------------------------------------------------------------------------
# JNI library
# ---------------------------------------------------------------------------
add_library(redreader_translate SHARED translate_jni.cpp)
target_link_libraries(redreader_translate PRIVATE bergamot-translator cld2 log)
set_target_properties(redreader_translate PROPERTIES LINK_FLAGS_RELEASE "-s")
