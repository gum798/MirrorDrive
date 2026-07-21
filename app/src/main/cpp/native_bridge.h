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
