#include <jni.h>
#include <string>
#include "native_bridge.h"
#include <cstring>
#include <cstdint>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "MirrorDrive", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MirrorDrive", __VA_ARGS__)

MirrorContext g_ctx;
JavaVM *g_vm = nullptr;
VideoSink g_video;
AudioSink g_audio;

// Cache the JavaVM so cb_video_process (which runs on a native RTP-mirror thread) can
// attach to the JVM and deliver access units to the Kotlin VideoRenderer.
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// Store a global ref to the VideoRenderer and cache its onAccessUnit method id.
extern "C" JNIEXPORT void JNICALL
Java_com_example_mirrordrive_NativeBridge_setVideoSink(JNIEnv *env, jobject, jobject sink) {
    if (g_video.obj) env->DeleteGlobalRef(g_video.obj);
    g_video.obj = env->NewGlobalRef(sink);
    jclass cls = env->GetObjectClass(sink);
    // void onAccessUnit(byte[] data, long ptsUs, boolean isConfig)
    g_video.onAu = env->GetMethodID(cls, "onAccessUnit", "([BJZ)V");
    if (!g_video.onAu) LOGE("setVideoSink: onAccessUnit method not found");
    else LOGI("setVideoSink: video sink registered");
}

// Store a global ref to the AudioRenderer and cache its onAudioFrame method id.
// Mirrors setVideoSink; cb_audio_process delivers compressed AAC-ELD frames here.
extern "C" JNIEXPORT void JNICALL
Java_com_example_mirrordrive_NativeBridge_setAudioSink(JNIEnv *env, jobject, jobject sink) {
    if (g_audio.obj) env->DeleteGlobalRef(g_audio.obj);
    g_audio.obj = env->NewGlobalRef(sink);
    jclass cls = env->GetObjectClass(sink);
    // void onAudioFrame(byte[] data, long ptsUs)
    g_audio.onFrame = env->GetMethodID(cls, "onAudioFrame", "([BJ)V");
    if (!g_audio.onFrame) LOGE("setAudioSink: onAudioFrame method not found");
    else LOGI("setAudioSink: audio sink registered");
}

// Route UxPlay's internal logger to logcat so init failures are visible.
static void raop_log_cb(void *cls, int level, const char *msg) {
    (void)cls;
    __android_log_print(ANDROID_LOG_INFO, "MirrorDrive-raop", "[%d] %s", level, msg);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mirrordrive_NativeBridge_nativeVersion(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF("mirrordrive-native-0.1");
}

// ALL raop_callbacks_s fields must be non-NULL — UxPlay calls them without NULL checks,
// so any unset pointer crashes (SIGSEGV, PC=0) during connection setup. Real A/V wiring
// (video_process/audio_process/audio_get_format) lands in Task 3/4; the rest are stubs.
static void cb_conn_init(void*) {}
static void cb_conn_destroy(void*) {}
static void cb_conn_reset(void*, int) {}
static void cb_conn_teardown(void*, bool*, bool*) {}
static void cb_conn_feedback(void*) {}
// Real video bridge: hand each decoded Annex-B access unit to the Kotlin VideoRenderer.
// Runs on a native RTP-mirror thread (NOT a JVM thread), so it must attach to the JVM.
static void cb_video_process(void*, raop_ntp_t*, video_decode_struct *d) {
    if (!d || !d->data || d->data_len <= 0) return;

    // Debug: confirm frames arrive on the physical device (throttled to avoid log spam).
    static uint32_t frame_count = 0;
    if ((frame_count++ % 30) == 0) {
        LOGI("video frame: %d bytes, nal=%d, h265=%d", d->data_len, d->nal_count, d->is_h265);
    }

    if (d->is_h265) return;                       // H.264 only; H.265 is out of scope.
    if (!g_vm || !g_video.obj || !g_video.onAu) return;

    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    jbyteArray arr = env->NewByteArray(d->data_len);
    if (arr) {
        env->SetByteArrayRegion(arr, 0, d->data_len, (const jbyte*)d->data);
        // ntp_time_remote is already in nanoseconds here; convert to microseconds.
        jlong ptsUs = (jlong)(d->ntp_time_remote / 1000ULL);
        // isConfig=false: VideoRenderer self-detects SPS/PPS from the Annex-B stream.
        env->CallVoidMethod(g_video.obj, g_video.onAu, arr, ptsUs, JNI_FALSE);
        env->DeleteLocalRef(arr);
    }

    if (attached) g_vm->DetachCurrentThread();
}
// Real audio bridge: hand each compressed AAC-ELD access unit to the Kotlin AudioRenderer.
// Runs on a native RTP-audio thread (NOT a JVM thread), so it must attach to the JVM. The
// payload is freed by UxPlay right after this returns, so the copy must be synchronous.
static void cb_audio_process(void*, raop_ntp_t*, audio_decode_struct *d) {
    if (!d || !d->data || d->data_len <= 0) return;
    if (d->ct != 8) return;                       // AAC-ELD only in v1 (ct=2 ALAC out of scope).

    // Debug: confirm audio frames arrive on the physical device (throttled to avoid spam).
    static uint32_t audio_frame_count = 0;
    if ((audio_frame_count++ % 100) == 0) {
        LOGI("audio frame: %d bytes ct=%d", d->data_len, (int)d->ct);
    }

    if (!g_vm || !g_audio.obj || !g_audio.onFrame) return;

    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    jbyteArray arr = env->NewByteArray(d->data_len);
    if (arr) {
        env->SetByteArrayRegion(arr, 0, d->data_len, (const jbyte*)d->data);
        // ntp_time_remote is already in nanoseconds here; convert to microseconds.
        jlong ptsUs = (jlong)(d->ntp_time_remote / 1000ULL);
        env->CallVoidMethod(g_audio.obj, g_audio.onFrame, arr, ptsUs);
        env->DeleteLocalRef(arr);
    }

    if (attached) g_vm->DetachCurrentThread();
}
static void cb_video_pause(void*) {}
static void cb_video_resume(void*) {}
static void cb_video_flush(void*) {}
static void cb_audio_flush(void*) {}
static void cb_video_reset(void*, reset_type_t) {}
static double cb_audio_set_client_volume(void*) { return 0.0; }
static void cb_audio_set_volume(void*, float) {}
static void cb_audio_set_metadata(void*, const void*, int) {}
static void cb_audio_set_coverart(void*, const void*, int) {}
static void cb_audio_stop_coverart_rendering(void*) {}
static void cb_audio_remote_control_id(void*, const char*, const char*) {}
static void cb_audio_set_progress(void*, uint32_t*, uint32_t*, uint32_t*) {}
static void cb_audio_get_format(void*, unsigned char*, unsigned short*, bool*, bool*, uint64_t*) {}
static void cb_video_report_size(void*, float*, float*, float*, float*) {}
static void cb_mirror_video_running(void*, bool) {}
static void cb_report_client_request(void*, char*, char*, char*, bool *admit) { if (admit) *admit = true; }
static void cb_display_pin(void*, char*) {}
static void cb_register_client(void*, const char*, const char*, const char*) {}
static bool cb_check_register(void*, const char*) { return false; }
static const char* cb_passwd(void*, int *len) { if (len) *len = 0; return nullptr; }
static void cb_export_dacp(void*, const char*, const char*) {}
static int  cb_video_set_codec(void*, video_codec_t) { return 0; }
static void cb_on_video_play(void*, const char*, float) {}
static void cb_on_video_scrub(void*, float) {}
static void cb_on_video_rate(void*, float) {}
static void cb_on_video_stop(void*) {}
static void cb_on_video_acquire_playback_info(void*, playback_info_t*) {}
static float cb_on_video_playlist_remove(void*) { return 0.0f; }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mirrordrive_NativeBridge_nativeInit(JNIEnv *env, jobject, jstring filesDir) {
    const char *dir = env->GetStringUTFChars(filesDir, nullptr);
    std::string keyfile = std::string(dir) + "/airplay_ed25519.key";
    env->ReleaseStringUTFChars(filesDir, dir);

    // Fixed synthetic device id (stable MAC-format string), consistent with HW below.
    std::strcpy(g_ctx.device_id, "48:5d:60:7c:ee:22");

    raop_callbacks_t cbs; std::memset(&cbs, 0, sizeof(cbs));
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.conn_reset = cb_conn_reset;
    cbs.video_reset = cb_video_reset;
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_teardown = cb_conn_teardown;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_remote_control_id = cb_audio_remote_control_id;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.video_report_size = cb_video_report_size;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_set_codec = cb_video_set_codec;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;

    g_ctx.raop = raop_init(&cbs);
    if (!g_ctx.raop) { LOGE("raop_init returned NULL"); return JNI_FALSE; }
    raop_set_log_callback(g_ctx.raop, raop_log_cb, nullptr);
    raop_set_log_level(g_ctx.raop, 8 /*LOGGER_DEBUG_DATA*/);
    LOGI("raop_init OK; keyfile=%s device_id=%s", keyfile.c_str(), g_ctx.device_id);

    int err = raop_init2(g_ctx.raop, 1 /*nohold*/, g_ctx.device_id, keyfile.c_str());
    if (err) { LOGE("raop_init2 failed, err=%d", err); return JNI_FALSE; }
    LOGI("raop_init2 OK; starting httpd");

    unsigned short port = 0;
    // UxPlay httpd_start returns 1 on SUCCESS (0 = already running, -1 = socket error) — not 0.
    if (raop_start_httpd(g_ctx.raop, &port) != 1) { LOGE("raop_start_httpd failed"); return JNI_FALSE; }
    // raop_start_httpd writes the bound port into `port` but does NOT store it in raop.
    // Set it so raop_get_port() and the advertised SRV record report the real port, not 0.
    raop_set_port(g_ctx.raop, port);
    g_ctx.port = port;
    LOGI("httpd started, port=%d", port);

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

// NOTE: an fdk-aac AAC-ELD fallback decoder (aacDecoder_Open(TT_MP4_RAW,1) + aacDecoder_ConfigRaw
// + Fill/DecodeFrame) was implemented here but had to be removed: the Task 1 prebuilt
// libfdk-aac.a is NOT compiled with -fPIC, so referencing any of its symbols from this shared
// library breaks the arm64 link (R_AARCH64_ADR_PREL_PG_HI21 against fft_32/fft_16 etc). It only
// linked before because nothing referenced it (dead-stripped). Re-enabling the fallback requires
// recompiling fdk-aac with -fPIC. Until then the MediaCodec AAC-ELD path (AudioRenderer) is the
// sole decoder; the CMake fdkaac linkage is left in place (harmless while unreferenced).
