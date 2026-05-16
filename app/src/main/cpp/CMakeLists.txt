cmake_minimum_required(VERSION 3.16)
project("yuv_to_bitmap")

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

set(PREBUILT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libass/prebuilts-${ANDROID_ABI}/${ANDROID_ABI})

add_subdirectory(libyuv)

set(SOUNDSTRETCH OFF CACHE BOOL "Build soundstretch utility" FORCE)
set(SOUNDTOUCH_DLL ON CACHE BOOL "Build SoundTouch C wrapper" FORCE)
set(INTEGER_SAMPLES OFF CACHE BOOL "Use integer samples" FORCE)
add_subdirectory(soundtouch)

add_library(soundtouchjni SHARED soundtouch_tianscar_jni.cpp)
target_include_directories(soundtouchjni PRIVATE
    soundtouch/source/SoundTouchDLL
    soundtouch/include
)
target_link_libraries(soundtouchjni SoundTouchDLL android log)

add_library(ass_jni SHARED ass_renderer.cpp)

target_include_directories(ass_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libass/prebuilts-${ANDROID_ABI}/include)

add_library(prebuilt_ass STATIC IMPORTED)
set_target_properties(prebuilt_ass PROPERTIES IMPORTED_LOCATION ${PREBUILT_DIR}/libass.a)

add_library(prebuilt_freetype STATIC IMPORTED)
set_target_properties(prebuilt_freetype PROPERTIES IMPORTED_LOCATION ${PREBUILT_DIR}/libfreetype.a)

add_library(prebuilt_harfbuzz STATIC IMPORTED)
set_target_properties(prebuilt_harfbuzz PROPERTIES IMPORTED_LOCATION ${PREBUILT_DIR}/libharfbuzz.a)

add_library(prebuilt_fribidi STATIC IMPORTED)
set_target_properties(prebuilt_fribidi PROPERTIES IMPORTED_LOCATION ${PREBUILT_DIR}/libfribidi.a)

add_library(prebuilt_fontconfig STATIC IMPORTED)
set_target_properties(prebuilt_fontconfig PROPERTIES IMPORTED_LOCATION ${PREBUILT_DIR}/libfontconfig.a)

add_library(prebuilt_xml2 STATIC IMPORTED)
set_target_properties(prebuilt_xml2 PROPERTIES IMPORTED_LOCATION ${PREBUILT_DIR}/libxml2.a)

add_library(prebuilt_unibreak STATIC IMPORTED)
set_target_properties(prebuilt_unibreak PROPERTIES IMPORTED_LOCATION ${PREBUILT_DIR}/libunibreak.a)

target_link_libraries(ass_jni
    prebuilt_ass
    prebuilt_harfbuzz
    prebuilt_freetype
    prebuilt_fribidi
    prebuilt_fontconfig
    prebuilt_xml2
    prebuilt_unibreak
    GLESv3 EGL
    android log jnigraphics z m
)

add_library(yuv_to_bitmap SHARED yuv_to_bitmap.cpp)

target_include_directories(yuv_to_bitmap PRIVATE libyuv/include)
target_link_libraries(yuv_to_bitmap yuv android log jnigraphics)
