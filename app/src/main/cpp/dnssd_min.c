// dnssd_min.c — MirrorDrive: builds AirPlay/RAOP TXT directly (no Bonjour/avahi runtime).
// Advertisement is done in Kotlin/jmDNS; this exists so the native httpd GET /info handler
// returns correct features/TXT/name/hw_addr. Replaces Task 1's no-op dnssd_shim.c.
#include "dnssd.h"
#include "dnssdint.h"
#include "global.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

struct dnssd_s {
    char *name;
    unsigned char hw_addr[6];
    char pk_str[65];
    uint64_t features;
    unsigned char airplay_txt[512]; int airplay_txt_len;
    unsigned char raop_txt[512];    int raop_txt_len;
};

// Appends one DNS-SD TXT record (1 length byte + kv). Invariant: caller-supplied
// buf is 512 bytes; the fixed key set built in build_txt stays well under 512.
static void txt_add(unsigned char *buf, int *len, const char *kv) {
    int n = (int)strlen(kv);
    buf[(*len)++] = (unsigned char)n;
    memcpy(buf + *len, kv, n);
    *len += n;
}

static void build_txt(dnssd_t *d) {
    char devid[18];
    snprintf(devid, sizeof(devid), "%02x:%02x:%02x:%02x:%02x:%02x",
             d->hw_addr[0],d->hw_addr[1],d->hw_addr[2],d->hw_addr[3],d->hw_addr[4],d->hw_addr[5]);
    char kv[128];
    d->airplay_txt_len = 0;
    snprintf(kv,sizeof(kv),"deviceid=%s",devid);                    txt_add(d->airplay_txt,&d->airplay_txt_len,kv);
    snprintf(kv,sizeof(kv),"features=%s,%s",FEATURES_1,FEATURES_2); txt_add(d->airplay_txt,&d->airplay_txt_len,kv);
    txt_add(d->airplay_txt,&d->airplay_txt_len,"pw=false");
    txt_add(d->airplay_txt,&d->airplay_txt_len,"flags=0x4"); /* 0x4 = no-PIN pairing; 0x84's bit7 triggers iOS PIN pairing */
    txt_add(d->airplay_txt,&d->airplay_txt_len,"model=AppleTV3,2");
    snprintf(kv,sizeof(kv),"pk=%s",d->pk_str);                      txt_add(d->airplay_txt,&d->airplay_txt_len,kv);
    snprintf(kv,sizeof(kv),"pi=%s",AIRPLAY_PI);                     txt_add(d->airplay_txt,&d->airplay_txt_len,kv);
    snprintf(kv,sizeof(kv),"srcvers=%s",GLOBAL_VERSION);            txt_add(d->airplay_txt,&d->airplay_txt_len,kv);
    txt_add(d->airplay_txt,&d->airplay_txt_len,"vv=2");
    d->raop_txt_len = 0;
    txt_add(d->raop_txt,&d->raop_txt_len,"txtvers=1");
    txt_add(d->raop_txt,&d->raop_txt_len,"ch=2");
    txt_add(d->raop_txt,&d->raop_txt_len,"cn=0,1,2,3");
    txt_add(d->raop_txt,&d->raop_txt_len,"da=true");
    txt_add(d->raop_txt,&d->raop_txt_len,"et=0,3,5");
    txt_add(d->raop_txt,&d->raop_txt_len,"vv=2");
    snprintf(kv,sizeof(kv),"ft=%s,%s",FEATURES_1,FEATURES_2);       txt_add(d->raop_txt,&d->raop_txt_len,kv);
    txt_add(d->raop_txt,&d->raop_txt_len,"am=AppleTV3,2");
    txt_add(d->raop_txt,&d->raop_txt_len,"md=0,1,2");
    txt_add(d->raop_txt,&d->raop_txt_len,"rhd=5.6.0.0");
    txt_add(d->raop_txt,&d->raop_txt_len,"pw=false");
    snprintf(kv,sizeof(kv),"sf=%s",RAOP_SF);                        txt_add(d->raop_txt,&d->raop_txt_len,kv);
    txt_add(d->raop_txt,&d->raop_txt_len,"sr=44100");
    txt_add(d->raop_txt,&d->raop_txt_len,"ss=16");
    txt_add(d->raop_txt,&d->raop_txt_len,"sv=false");
    txt_add(d->raop_txt,&d->raop_txt_len,"tp=UDP");
    snprintf(kv,sizeof(kv),"vs=%s",GLOBAL_VERSION);                 txt_add(d->raop_txt,&d->raop_txt_len,kv);
    txt_add(d->raop_txt,&d->raop_txt_len,"vn=65537");
    snprintf(kv,sizeof(kv),"pk=%s",d->pk_str);                      txt_add(d->raop_txt,&d->raop_txt_len,kv);
}

dnssd_t *dnssd_init(const char *name, int name_len, const char *hw_addr, int hw_addr_len, int *error, unsigned char pin_pw) {
    (void)pin_pw;
    dnssd_t *d = (dnssd_t*)calloc(1, sizeof(struct dnssd_s));
    if (!d) { if (error) *error = -1; return NULL; }
    d->name = (char*)malloc(name_len + 1);
    memcpy(d->name, name, name_len); d->name[name_len] = 0;
    memcpy(d->hw_addr, hw_addr, hw_addr_len < 6 ? hw_addr_len : 6);
    d->features = 0x5A7FFEE6ULL;
    build_txt(d);
    if (error) *error = 0;
    return d;
}
void dnssd_destroy(dnssd_t *d) { if (d) { free(d->name); free(d); } }
int  dnssd_register_raop(dnssd_t *d, unsigned short port)    { (void)d;(void)port; return 0; }
int  dnssd_register_airplay(dnssd_t *d, unsigned short port) { (void)d;(void)port; return 0; }
void dnssd_unregister_raop(dnssd_t *d)    { (void)d; }
void dnssd_unregister_airplay(dnssd_t *d) { (void)d; }
const char *dnssd_get_raop_txt(dnssd_t *d, int *length)    { *length = d->raop_txt_len;    return (const char*)d->raop_txt; }
const char *dnssd_get_airplay_txt(dnssd_t *d, int *length) { *length = d->airplay_txt_len; return (const char*)d->airplay_txt; }
const char *dnssd_get_name(dnssd_t *d, int *length)    { *length = (int)strlen(d->name); return d->name; }
const char *dnssd_get_hw_addr(dnssd_t *d, int *length) { *length = 6; return (const char*)d->hw_addr; }
uint64_t dnssd_get_airplay_features(dnssd_t *d) { return d->features; }
void dnssd_set_pk(dnssd_t *d, char *pk_str) { strncpy(d->pk_str, pk_str, 64); d->pk_str[64]=0; build_txt(d); }
void dnssd_set_airplay_features(dnssd_t *d, int bit, int val) { if(val) d->features |= (1ULL<<bit); else d->features &= ~(1ULL<<bit); }
