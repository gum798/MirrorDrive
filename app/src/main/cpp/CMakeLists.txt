cmake_minimum_required(VERSION 3.22.1)
project(mirrordrive C CXX)

set(PREBUILT ${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI})

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES
    IMPORTED_LOCATION ${PREBUILT}/lib/libcrypto.a
    INTERFACE_INCLUDE_DIRECTORIES ${PREBUILT}/include)
add_library(plist STATIC IMPORTED)
set_target_properties(plist PROPERTIES
    IMPORTED_LOCATION ${PREBUILT}/lib/libplist-2.0.a
    INTERFACE_INCLUDE_DIRECTORIES ${PREBUILT}/include)
add_library(fdkaac STATIC IMPORTED)
set_target_properties(fdkaac PROPERTIES
    IMPORTED_LOCATION ${PREBUILT}/lib/libfdk-aac.a
    INTERFACE_INCLUDE_DIRECTORIES ${PREBUILT}/include)

set(UX ${CMAKE_SOURCE_DIR}/uxplay/lib)
file(GLOB UX_SRC       ${UX}/*.c)
file(GLOB LLHTTP_SRC   ${UX}/llhttp/*.c)
file(GLOB PLAYFAIR_SRC ${UX}/playfair/*.c)

# MirrorDrive: v1.73.6 has no lib/mdnsd/ dir; only llhttp/ and playfair/ subdirs exist.
# dnssd.c pulls in Apple's <dns_sd.h> (absent on Android) and an autotools-generated
# config.h (not vendored). It is the ONLY file including config.h, so excluding it
# removes both problems. Real mDNS advertisement is done in Kotlin via jmDNS (later task).
list(FILTER UX_SRC EXCLUDE REGEX "dnssd\\.c$")

add_library(mirrordrive SHARED
    native_bridge.cpp
    dnssd_shim.c
    ${UX_SRC} ${LLHTTP_SRC} ${PLAYFAIR_SRC})

target_include_directories(mirrordrive PRIVATE ${UX} ${UX}/llhttp ${UX}/playfair)

target_compile_definitions(mirrordrive PRIVATE
    TARGET_POSIX STANDALONE
    __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS _REENTRANT
    _LARGEFILE64_SOURCE _FILE_OFFSET_BITS=64
    OPENSSL_API_COMPAT=0x10101000L
    # libplist 2.6.0 satisfies both >=2.1.0 and >=2.3.0, so define BOTH gates (the
    # reference uxplay/lib/CMakeLists.txt sets them independently). PLIST_210 selects
    # the modern plist_get_data_ptr() API in http_handlers.h; without it the code falls
    # to a legacy branch that misuses the void-returning plist_get_data_val().
    PLIST_210 PLIST_230)

# MirrorDrive: --gc-sections alone (spike 3a) could NOT resolve the excluded dnssd.c
# symbols: the accessors (dnssd_get_name/hw_addr/*_txt/features, dnssd_set_pk) are
# reachable from the retained AirPlay request handlers, so they are GC roots, not dead
# code. dnssd_shim.c (spike 3b, added to the sources above) provides the no-op stubs
# that actually resolve the link. These section flags are kept as a harmless size
# optimization (they let the linker drop any genuinely unused code paths).
target_compile_options(mirrordrive PRIVATE -ffunction-sections -fdata-sections)
target_link_options(mirrordrive PRIVATE -Wl,--gc-sections)

find_library(log-lib log)
target_link_libraries(mirrordrive crypto plist fdkaac android mediandk ${log-lib})
