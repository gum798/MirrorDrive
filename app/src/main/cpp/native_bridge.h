#pragma once
#include <jni.h>
#include "raop.h"
#include "dnssd.h"

struct MirrorContext {
    raop_t *raop = nullptr;
    dnssd_t *dnssd = nullptr;
    char device_id[18] = {0};   // "AA:BB:CC:DD:EE:FF"
    char pk_hex[65] = {0};      // 64 hex + NUL
    unsigned short port = 0;
};
extern MirrorContext g_ctx;

// Cached JavaVM (set in JNI_OnLoad) so native RTP threads can attach and call back.
extern JavaVM *g_vm;

// The Kotlin VideoRenderer sink: a global ref plus the cached onAccessUnit method id.
struct VideoSink {
    jobject obj = nullptr;      // global ref to the VideoRenderer
    jmethodID onAu = nullptr;   // void onAccessUnit(byte[] data, long ptsUs, boolean isConfig)
};
extern VideoSink g_video;
