/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2018 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//
// jni interfaces
// posix io hook
// Created by liyongjie on 2017/6/7.
//

#include <jni.h>
#include <cstddef>
#include <cstring>
#include <android/log.h>
#include <assert.h>
#include <xhook.h>
#include <string>
#include <algorithm>
#include "comm/io_canary_utils.h"
#include "core/io_canary.h"

namespace iocanary {

    static const char* const kTag = "IOCanary.JNI";

    static int (*original_open) (const char *pathname, int flags, mode_t mode);
    static int (*original_open64) (const char *pathname, int flags, mode_t mode);
    static ssize_t (*original_read) (int fd, void *buf, size_t size);
    static ssize_t (*original_read_chk) (int fd, void* buf, size_t count, size_t buf_size);
    static ssize_t (*original_write) (int fd, const void *buf, size_t size);
    static ssize_t (*original_write_chk) (int fd, const void* buf, size_t count, size_t buf_size);
    static int (*original_close) (int fd);

    static bool kInitSuc = false;
    static JavaVM *kJvm;

    static jclass kJavaBridgeClass;
    static jmethodID kMethodIDOnIssuePublish;

    static jclass kJavaContextClass;
    static jmethodID kMethodIDGetJavaContext;
    static jfieldID kFieldIDStack;
    static jfieldID kFieldIDThreadName;

    static jclass kIssueClass;
    static jmethodID kMethodIDIssueConstruct;

    static jclass kListClass;
    static jmethodID kMethodIDListConstruct;
    static jmethodID kMethodIDListAdd;

    const static char* TARGET_MODULES[] = {
        "libopenjdkjvm.so",
        "libjavacore.so",
        "libopenjdk.so"
    };
    const static size_t TARGET_MODULE_COUNT = sizeof(TARGET_MODULES) / sizeof(char*);

    extern "C" {

        /**
         * tool method to get JNIEnv;
         * ensure called after JNI_OnLoad
         * @return nullable
         */
        static JNIEnv* getJNIEnv() {
            //ensure kJvm init
            assert(kJvm != NULL);

            JNIEnv* env = NULL;
            int ret = kJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (ret != JNI_OK){
                __android_log_print(ANDROID_LOG_ERROR, kTag, "getJNIEnv !JNI_OK: %d", ret);
            }

            return env;
        }

        char *jstringToChars(JNIEnv *env, jstring jstr) {
            if (jstr == nullptr) {
                return nullptr;
            }

            jboolean isCopy = JNI_FALSE;
            const char *str = env->GetStringUTFChars(jstr, &isCopy);
            char *ret = strdup(str);
            env->ReleaseStringUTFChars(jstr, str);
            return ret;
        }

        // 处理 issue
        void OnIssuePublish(const std::vector<Issue>& published_issues) {
            if (!kInitSuc) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "OnIssuePublish kInitSuc false");
                return;
            }

            // 测试过：这里的 env 有值，但是main线程没有
            JNIEnv* env;
            bool attached = false;
            // 在线程里面，获取 env 的值，需要调用 AttachCurrentThread 才行
            // 这里方法执行后， env 的值是 0
            jint j_ret = kJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (j_ret == JNI_EDETACHED) {
                // 你的native代码建立自己的线程（比如建立线程监听），并在合适的时候回调 Java 代码，
                // 我们没有办法直接获得 JNIEnv，获取它的实例需要把你的线程 Attach到JavaVM上去，
                // 调用的方法是 JavaVM::AttachCurrentThread
                jint jAttachRet = kJvm->AttachCurrentThread(&env, nullptr);
                if (jAttachRet != JNI_OK) {
                    __android_log_print(ANDROID_LOG_ERROR, kTag, "onIssuePublish AttachCurrentThread !JNI_OK");
                    return;
                } else {
                    attached = true;
                }
            } else if (j_ret != JNI_OK || env == NULL) {
                return;
            }

            // 检查有没有异常发生
            // 这里就不是很懂了，这个检查的范围是多大啊
            // 是整个线程？？？
            jthrowable exp = env->ExceptionOccurred();
            if (exp != NULL) {
                __android_log_print(ANDROID_LOG_INFO, kTag, "checkCanCallbackToJava ExceptionOccurred, return false");
                // 调用ExceptionDescribe输出了一段描述性信息
                env->ExceptionDescribe();
                return;
            }

            // 创建 ArrayList
            jobject j_issues = env->NewObject(kListClass, kMethodIDListConstruct);

            // 构建 IOIssue 对象
            for (const auto& issue : published_issues) {
                jint type = issue.type_;
                jstring path = env->NewStringUTF(issue.file_io_info_.path_.c_str());
                jlong file_size = issue.file_io_info_.file_size_;
                jint op_cnt = issue.file_io_info_.op_cnt_;
                jlong buffer_size = issue.file_io_info_.buffer_size_;
                jlong op_cost_time = issue.file_io_info_.rw_cost_us_/1000;
                jint op_type = issue.file_io_info_.op_type_;
                jlong op_size = issue.file_io_info_.op_size_;
                jstring thread_name = env->NewStringUTF(issue.file_io_info_.java_context_.thread_name_.c_str());
                jstring stack = env->NewStringUTF(issue.stack.c_str());
                jint repeat_read_cnt = issue.repeat_read_cnt_;

                jobject issue_obj = env->NewObject(kIssueClass, kMethodIDIssueConstruct, type, path, file_size, op_cnt, buffer_size,
                                                   op_cost_time, op_type, op_size, thread_name, stack, repeat_read_cnt);

                // 调用 add 方法
                env->CallBooleanMethod(j_issues, kMethodIDListAdd, issue_obj);

                env->DeleteLocalRef(issue_obj);
                env->DeleteLocalRef(stack);
                env->DeleteLocalRef(thread_name);
                env->DeleteLocalRef(path);
            }

            // 调用 com.tencent.matrix.iocanary.core.IOCanaryJniBridge.onIssuePublish 方法
            env->CallStaticVoidMethod(kJavaBridgeClass, kMethodIDOnIssuePublish, j_issues);

            env->DeleteLocalRef(j_issues);

            if (attached) {
                // detach 之前 attach 的线程
                kJvm->DetachCurrentThread();
            }
        }

        static void DoProxyOpenLogic(const char *pathname, int flags, mode_t mode, int ret) {
            JNIEnv* env = NULL;
            kJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (env == NULL || !kInitSuc) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "ProxyOpen env null or kInitSuc:%d", kInitSuc);
            } else {
                // 获取 java 层的 JavaContext 对象
                jobject java_context_obj = env->CallStaticObjectMethod(kJavaBridgeClass, kMethodIDGetJavaContext);
                if (NULL == java_context_obj) {
                    return;
                }

                // 获取java层堆栈信息
                jstring j_stack = (jstring) env->GetObjectField(java_context_obj, kFieldIDStack);
                // 线程名
                jstring j_thread_name = (jstring) env->GetObjectField(java_context_obj, kFieldIDThreadName);

                char* thread_name = jstringToChars(env, j_thread_name);
                char* stack = jstringToChars(env, j_stack);
                // 创建 C++ 层的 JavaContex 对象
                JavaContext java_context(GetCurrentThreadId(), thread_name == NULL ? "" : thread_name, stack == NULL ? "" : stack);
                free(stack);
                free(thread_name);

                iocanary::IOCanary::Get().OnOpen(pathname, flags, mode, ret, java_context);

                env->DeleteLocalRef(java_context_obj);
                env->DeleteLocalRef(j_stack);
                env->DeleteLocalRef(j_thread_name);
            }
        }

        /**
         *  Proxy for open: callback to the java layer
         */
        //todo astrozhou 解决非主线程打开，主线程操作问题
        int ProxyOpen(const char *pathname, int flags, mode_t mode) {
            if(!IsMainThread()) {
                return original_open(pathname, flags, mode);
            }

            int ret = original_open(pathname, flags, mode);

            if (ret != -1) {
                DoProxyOpenLogic(pathname, flags, mode, ret);
            }

            return ret;
        }

        int ProxyOpen64(const char *pathname, int flags, mode_t mode) {
            if(!IsMainThread()) {
                return original_open64(pathname, flags, mode);
            }

            int ret = original_open64(pathname, flags, mode);

            if (ret != -1) {
                DoProxyOpenLogic(pathname, flags, mode, ret);
            }

            return ret;
        }

        /**
         *  Proxy for read: callback to the java layer
         */
        ssize_t ProxyRead(int fd, void *buf, size_t size) {
            if(!IsMainThread()) {
                return original_read(fd, buf, size);
            }

            int64_t start = GetTickCountMicros();

            size_t ret = original_read(fd, buf, size);

            long read_cost_us = GetTickCountMicros() - start;

            //__android_log_print(ANDROID_LOG_DEBUG, kTag, "ProxyRead fd:%d buf:%p size:%d ret:%d cost:%d", fd, buf, size, ret, read_cost_us);

            iocanary::IOCanary::Get().OnRead(fd, buf, size, ret, read_cost_us);

            return ret;
        }

        ssize_t ProxyReadChk(int fd, void* buf, size_t count, size_t buf_size) {
            if(!IsMainThread()) {
                return original_read_chk(fd, buf, count, buf_size);
            }

            int64_t start = GetTickCountMicros();

            ssize_t ret = original_read_chk(fd, buf, count, buf_size);

            long read_cost_us = GetTickCountMicros() - start;

            //__android_log_print(ANDROID_LOG_DEBUG, kTag, "ProxyRead fd:%d buf:%p size:%d ret:%d cost:%d", fd, buf, size, ret, read_cost_us);

            iocanary::IOCanary::Get().OnRead(fd, buf, count, ret, read_cost_us);

            return ret;
        }

        /**
         *  Proxy for write: callback to the java layer
         */
        ssize_t ProxyWrite(int fd, const void *buf, size_t size) {
            if(!IsMainThread()) {
                return original_write(fd, buf, size);
            }

            int64_t start = GetTickCountMicros();

            size_t ret = original_write(fd, buf, size);

            long write_cost_us = GetTickCountMicros() - start;

            //__android_log_print(ANDROID_LOG_DEBUG, kTag, "ProxyWrite fd:%d buf:%p size:%d ret:%d cost:%d", fd, buf, size, ret, write_cost_us);

            iocanary::IOCanary::Get().OnWrite(fd, buf, size, ret, write_cost_us);

            return ret;
        }

        ssize_t ProxyWriteChk(int fd, const void* buf, size_t count, size_t buf_size) {
            if(!IsMainThread()) {
                return original_write_chk(fd, buf, count, buf_size);
            }

            int64_t start = GetTickCountMicros();

            ssize_t ret = original_write_chk(fd, buf, count, buf_size);

            long write_cost_us = GetTickCountMicros() - start;

            //__android_log_print(ANDROID_LOG_DEBUG, kTag, "ProxyWrite fd:%d buf:%p size:%d ret:%d cost:%d", fd, buf, size, ret, write_cost_us);

            iocanary::IOCanary::Get().OnWrite(fd, buf, count, ret, write_cost_us);

            return ret;
        }

        /**
         *  Proxy for close: callback to the java layer
         */
        int ProxyClose(int fd) {
            if(!IsMainThread()) {
                return original_close(fd);
            }

            int ret = original_close(fd);

            //__android_log_print(ANDROID_LOG_DEBUG, kTag, "ProxyClose fd:%d ret:%d", fd, ret);
            iocanary::IOCanary::Get().OnClose(fd, ret);

            return ret;
        }

        // 这里JNIEXPORT和JNICALL都是JNI的关键字，表示此函数是要被JNI调用的。
        JNIEXPORT void JNICALL
        Java_com_tencent_matrix_iocanary_core_IOCanaryJniBridge_enableDetector(JNIEnv *env, jclass type, jint detector_type) {
            // int 与 enum 的转换，吊
            iocanary::IOCanary::Get().RegisterDetector(static_cast<DetectorType>(detector_type));
        }

        JNIEXPORT void JNICALL
        Java_com_tencent_matrix_iocanary_core_IOCanaryJniBridge_setConfig(JNIEnv *env, jclass type, jint key, jlong val) {
            iocanary::IOCanary::Get().SetConfig(static_cast<IOCanaryConfigKey>(key), val);
        }

        JNIEXPORT jboolean JNICALL
        Java_com_tencent_matrix_iocanary_core_IOCanaryJniBridge_doHook(JNIEnv *env, jclass type) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "doHook");

            for (int i = 0; i < TARGET_MODULE_COUNT; ++i) {
                const char* so_name = TARGET_MODULES[i];
                __android_log_print(ANDROID_LOG_INFO, kTag, "try to hook function in %s.", so_name);

                void* soinfo = xhook_elf_open(so_name);
                if (!soinfo) {
                    __android_log_print(ANDROID_LOG_WARN, kTag, "Failure to open %s, try next.", so_name);
                    continue;
                }

                // 从 .so 里面 找到 open 函数，将open函数的地址指向 ProxyOpen，原来的函数地址保存到 original_open
                // void** 相当于一个泛型
                xhook_hook_symbol(soinfo, "open", (void*)ProxyOpen, (void**)&original_open);
                xhook_hook_symbol(soinfo, "open64", (void*)ProxyOpen64, (void**)&original_open64);

                bool is_libjavacore = (strstr(so_name, "libjavacore.so") != nullptr);
                // hook libjavacore.so 中的，read 与 write 方法，如果失败了，就 hook __read_chk 与 __write_chk
                if (is_libjavacore) {
                    if (xhook_hook_symbol(soinfo, "read", (void*)ProxyRead, (void**)&original_read) != 0) {
                        __android_log_print(ANDROID_LOG_WARN, kTag, "doHook hook read failed, try __read_chk");
                        // 不同版本的 Android 系统实现有所不同，在 Android 7.0 之后，我们还需要替换下面这三个方法。
                        if (xhook_hook_symbol(soinfo, "__read_chk", (void*)ProxyReadChk, (void**)&original_read_chk) != 0) {
                            __android_log_print(ANDROID_LOG_WARN, kTag, "doHook hook failed: __read_chk");
                            xhook_elf_close(soinfo);
                            return JNI_FALSE;
                        }
                    }

                    if (xhook_hook_symbol(soinfo, "write", (void*)ProxyWrite, (void**)&original_write) != 0) {
                        __android_log_print(ANDROID_LOG_WARN, kTag, "doHook hook write failed, try __write_chk");
                        if (xhook_hook_symbol(soinfo, "__write_chk", (void*)ProxyWriteChk, (void**)&original_write_chk) != 0) {
                            __android_log_print(ANDROID_LOG_WARN, kTag, "doHook hook failed: __write_chk");
                            xhook_elf_close(soinfo);
                            return JNI_FALSE;
                        }
                    }
                }

                // hook close 方法
                xhook_hook_symbol(soinfo, "close", (void*)ProxyClose, (void**)&original_close);

                xhook_elf_close(soinfo);
            }

            __android_log_print(ANDROID_LOG_INFO, kTag, "doHook done.");
            return JNI_TRUE;
        }

        JNIEXPORT jboolean JNICALL
        Java_com_tencent_matrix_iocanary_core_IOCanaryJniBridge_doUnHook(JNIEnv *env, jclass type) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "doUnHook");
            for (int i = 0; i < TARGET_MODULE_COUNT; ++i) {
                const char* so_name = TARGET_MODULES[i];
                void* soinfo = xhook_elf_open(so_name);
                if (!soinfo) {
                    continue;
                }
                xhook_hook_symbol(soinfo, "open", (void*) original_open, nullptr);
                xhook_hook_symbol(soinfo, "open64", (void*) original_open64, nullptr);
                xhook_hook_symbol(soinfo, "read", (void*) original_read, nullptr);
                xhook_hook_symbol(soinfo, "write", (void*) original_write, nullptr);
                xhook_hook_symbol(soinfo, "__read_chk", (void*) original_read_chk, nullptr);
                xhook_hook_symbol(soinfo, "__write_chk", (void*) original_write_chk, nullptr);
                xhook_hook_symbol(soinfo, "close", (void*) original_close, nullptr);

                xhook_elf_close(soinfo);
            }
            return JNI_TRUE;
        }

        /*
         * 该方法里面主要是获取 一些 java class 及其字段
         */
        static bool InitJniEnv(JavaVM *vm) {
            kJvm = vm;
            JNIEnv* env = NULL;
            if (kJvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK){
                __android_log_print(ANDROID_LOG_ERROR, kTag, "InitJniEnv GetEnv !JNI_OK");
                return false;
            }

            jclass temp_cls = env->FindClass("com/tencent/matrix/iocanary/core/IOCanaryJniBridge");
            if (temp_cls == NULL)  {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "InitJniEnv kJavaBridgeClass NULL");
                return false;
            }
            // 临时引用转全局引用，是 IOCanaryJniBridge 的 class 对象
            kJavaBridgeClass = reinterpret_cast<jclass>(env->NewGlobalRef(temp_cls));

            jclass temp_java_context_cls = env->FindClass("com/tencent/matrix/iocanary/core/IOCanaryJniBridge$JavaContext");
            if (temp_java_context_cls == NULL)  {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "InitJniEnv kJavaBridgeClass NULL");
                return false;
            }
            // IOCanaryJniBridge$JavaContext
            kJavaContextClass = reinterpret_cast<jclass>(env->NewGlobalRef(temp_java_context_cls));
            // IOCanaryJniBridge$JavaContext # stack
            kFieldIDStack = env->GetFieldID(kJavaContextClass, "stack", "Ljava/lang/String;");
            // IOCanaryJniBridge$JavaContext # threadName
            kFieldIDThreadName = env->GetFieldID(kJavaContextClass, "threadName", "Ljava/lang/String;");
            if (kFieldIDStack == NULL || kFieldIDThreadName == NULL) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "InitJniEnv kJavaContextClass field NULL");
                return false;
            }

            // IOCanaryJniBridge # onIssuePublish
            kMethodIDOnIssuePublish = env->GetStaticMethodID(kJavaBridgeClass, "onIssuePublish", "(Ljava/util/ArrayList;)V");
            if (kMethodIDOnIssuePublish == NULL) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "InitJniEnv kMethodIDOnIssuePublish NULL");
                return false;
            }

            kMethodIDGetJavaContext = env->GetStaticMethodID(kJavaBridgeClass, "getJavaContext", "()Lcom/tencent/matrix/iocanary/core/IOCanaryJniBridge$JavaContext;");
            if (kMethodIDGetJavaContext == NULL) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "InitJniEnv kMethodIDGetJavaContext NULL");
                return false;
            }

            jclass temp_issue_cls = env->FindClass("com/tencent/matrix/iocanary/core/IOIssue");
            if (temp_issue_cls == NULL)  {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "InitJniEnv kIssueClass NULL");
                return false;
            }
            // IOIssue
            kIssueClass = reinterpret_cast<jclass>(env->NewGlobalRef(temp_issue_cls));

            // IOIssue # <init>
            kMethodIDIssueConstruct = env->GetMethodID(kIssueClass, "<init>", "(ILjava/lang/String;JIJJIJLjava/lang/String;Ljava/lang/String;I)V");
            if (kMethodIDIssueConstruct == NULL) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "InitJniEnv kMethodIDIssueConstruct NULL");
                return false;
            }

            jclass list_cls = env->FindClass("java/util/ArrayList");
            // ArrayList
            kListClass = reinterpret_cast<jclass>(env->NewGlobalRef(list_cls));
            // ArrayList # <init>
            kMethodIDListConstruct = env->GetMethodID(list_cls, "<init>", "()V");
            // ArrayList # add
            kMethodIDListAdd = env->GetMethodID(list_cls, "add", "(Ljava/lang/Object;)Z");

            return true;
        }

        JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved){
            __android_log_print(ANDROID_LOG_DEBUG, kTag, "JNI_OnLoad");
            kInitSuc = false;


            if (!InitJniEnv(vm)) {
                return -1;
            }

            iocanary::IOCanary::Get().SetIssuedCallback(OnIssuePublish);

            kInitSuc = true;
            __android_log_print(ANDROID_LOG_DEBUG, kTag, "JNI_OnLoad done");
            return JNI_VERSION_1_6;
        }

        JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
            __android_log_print(ANDROID_LOG_DEBUG, kTag, "JNI_OnUnload done");
            JNIEnv* env;
            kJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (env != NULL) {
                if (kIssueClass) {
                    env->DeleteGlobalRef(kIssueClass);
                }
                if (kJavaBridgeClass) {
                    env->DeleteGlobalRef(kJavaBridgeClass);
                }
                if (kListClass) {
                    env->DeleteGlobalRef(kListClass);
                }
            }
        }

    }
}
