// MirrorDrive: stub — real advertisement is done in Kotlin via jmDNS.
//
// UxPlay's lib/dnssd.c is excluded from this build: it pulls in Apple's <dns_sd.h>
// (absent on Android) and an autotools-generated config.h that is not vendored. That
// file defines the dnssd_* accessors, so raop.c / raop_handlers.h / http_handlers.h
// are left with undefined references to a handful of them when building the AirPlay
// /info response. These no-op stubs satisfy the linker and return empty/zero data.
//
// NOTE for later tasks: if the native httpd /info path is ever exercised, these must
// return real device values (name, hw_addr, TXT records, features). In the current
// design mDNS/advertisement and the /info metadata are owned by Kotlin (jmDNS), and
// Task 2's native code does not call raop_set_dnssd, so raop->dnssd stays NULL and
// these are effectively unused.
#include <stdint.h>
#include "dnssd.h"

// A valid, empty, NUL-terminated buffer so callers that treat the result as a C string
// (e.g. plist_new_string) or copy `length` bytes never dereference NULL.
static const char mirrordrive_dnssd_empty[1] = { 0 };

void dnssd_set_pk(dnssd_t *dnssd, char *pk_str) {
    (void) dnssd;
    (void) pk_str;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd, int *length) {
    (void) dnssd;
    if (length) *length = 0;
    return mirrordrive_dnssd_empty;
}

const char *dnssd_get_raop_txt(dnssd_t *dnssd, int *length) {
    (void) dnssd;
    if (length) *length = 0;
    return mirrordrive_dnssd_empty;
}

const char *dnssd_get_hw_addr(dnssd_t *dnssd, int *length) {
    (void) dnssd;
    if (length) *length = 0;
    return mirrordrive_dnssd_empty;
}

const char *dnssd_get_name(dnssd_t *dnssd, int *length) {
    (void) dnssd;
    if (length) *length = 0;
    return mirrordrive_dnssd_empty;
}

uint64_t dnssd_get_airplay_features(dnssd_t *dnssd) {
    (void) dnssd;
    return 0;
}
