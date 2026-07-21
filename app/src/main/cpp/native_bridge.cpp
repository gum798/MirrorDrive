#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mirrordrive_NativeBridge_nativeVersion(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF("mirrordrive-native-0.1");
}
