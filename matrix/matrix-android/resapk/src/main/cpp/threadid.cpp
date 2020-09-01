//
// Created by aprz512 on 2020/8/25.
//

#include <jni.h>
#include <unistd.h>
#include <android/log.h>
#include <thread>

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_resapk_Jni_getIds(JNIEnv *env, jclass clazz) {

    jintArray arr = env->NewIntArray(2);

    jint *ele = env->GetIntArrayElements(arr, 0);

    ele[0] = getpid();
    ele[1] = gettid();


//    env->SetIntArrayRegion(arr, 0, 2, ele);

    __android_log_print(ANDROID_LOG_DEBUG, "thread", "%d-%d", ele[0], ele[1]);

    env->ReleaseIntArrayElements(arr, ele, 0);
    return arr;
}

static JavaVM *jvm;

void Detect() {
    JNIEnv *env;
    __android_log_print(ANDROID_LOG_DEBUG, "thread---", "%d", env);
    jvm->GetEnv((void **) &env, JNI_VERSION_1_6);
    __android_log_print(ANDROID_LOG_DEBUG, "thread-------", "%d", env);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    jvm = vm;

    std::thread detect_thread(&Detect);
    detect_thread.detach();

    return JNI_VERSION_1_6;
}

