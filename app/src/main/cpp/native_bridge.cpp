#include <jni.h>
#include <string>
#include "native_bridge.h"
#include <cstring>

MirrorContext g_ctx;

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mirrordrive_NativeBridge_nativeVersion(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF("mirrordrive-native-0.1");
}

// Minimal callbacks — real A/V wiring lands in Task 3/4. Stubs so raop_init succeeds.
static void cb_conn_init(void*) {}
static void cb_conn_destroy(void*) {}
static void cb_conn_reset(void*, int) {}
static void cb_video_process(void*, raop_ntp_t*, video_decode_struct*) {}
static void cb_audio_process(void*, raop_ntp_t*, audio_decode_struct*) {}
static void cb_audio_get_format(void*, unsigned char*, unsigned short*, bool*, bool*, uint64_t*) {}
static void cb_video_report_size(void*, float*, float*, float*, float*) {}
static int  cb_video_set_codec(void*, video_codec_t) { return 0; }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mirrordrive_NativeBridge_nativeInit(JNIEnv *env, jobject, jstring filesDir) {
    const char *dir = env->GetStringUTFChars(filesDir, nullptr);
    std::string keyfile = std::string(dir) + "/airplay_ed25519.key";
    env->ReleaseStringUTFChars(filesDir, dir);

    // Fixed synthetic device id (stable MAC-format string), consistent with HW below.
    std::strcpy(g_ctx.device_id, "48:5d:60:7c:ee:22");

    raop_callbacks_t cbs; std::memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init; cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.video_process = cb_video_process; cbs.audio_process = cb_audio_process;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.video_report_size = cb_video_report_size;
    cbs.video_set_codec = cb_video_set_codec;

    g_ctx.raop = raop_init(&cbs);
    if (!g_ctx.raop) return JNI_FALSE;
    int err = raop_init2(g_ctx.raop, 1 /*nohold*/, g_ctx.device_id, keyfile.c_str());
    if (err) return JNI_FALSE;

    unsigned short port = 0;
    if (raop_start_httpd(g_ctx.raop, &port)) return JNI_FALSE;
    g_ctx.port = raop_get_port(g_ctx.raop);

    // Extract Ed25519 public key hex for the TXT pk (accessor added to raop.c, Step 6).
    unsigned char pub[32];
    raop_get_public_key(g_ctx.raop, pub);
    static const char *H = "0123456789abcdef";
    for (int i = 0; i < 32; i++) { g_ctx.pk_hex[i*2]=H[pub[i]>>4]; g_ctx.pk_hex[i*2+1]=H[pub[i]&0xf]; }
    g_ctx.pk_hex[64]=0;

    // hw_addr is a raw 6-byte MAC (NOT the device_id string). Matches g_ctx.device_id.
    static const unsigned char HW[6] = {0x48,0x5D,0x60,0x7C,0xEE,0x22};
    int dnssd_err = 0;
    g_ctx.dnssd = dnssd_init("MirrorDrive", 11, (const char*)HW, 6, &dnssd_err, 0);
    if (g_ctx.dnssd) { dnssd_set_pk(g_ctx.dnssd, g_ctx.pk_hex); raop_set_dnssd(g_ctx.raop, g_ctx.dnssd); }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mirrordrive_NativeBridge_nativeGetPublicKeyHex(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_ctx.pk_hex);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mirrordrive_NativeBridge_nativeGetDeviceId(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_ctx.device_id);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mirrordrive_NativeBridge_nativeGetPort(JNIEnv *env, jobject) {
    return (jint)g_ctx.port;
}
