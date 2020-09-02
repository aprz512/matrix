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

#ifndef __LIB_SQLITELINT_LOADER_H__
#define __LIB_SQLITELINT_LOADER_H__

#include <jni.h>
#include "com_tencent_sqlitelint_util_SLog.h"
#include <vector>

//
// Created by liyongjie on 2017/2/16.
//

// 宏：提取重复代码，这里的重复代码是字面意义的重复代码
// 直接替换使用处的代码即可理解
// 最后面缺少括号，所以可以推断使用出肯定要跟着括号
// 所以这里是   声明了函数（第一行） 以及写了 函数的头部（最后一行）
// 中间是另外一个函数，它的代码会在 main 之前执行，作用调用 register_module_func 函数，其第二个参数是
// 第一行声明的函数
#define MODULE_INIT(name) \
	static int ModuleInit_##name(JavaVM *vm, JNIEnv *env); \
	static void __attribute__((constructor)) MODULE_INIT_##name() \
	{ \
		register_module_func(#name, ModuleInit_##name, 1); \
	} \
	static int ModuleInit_##name(JavaVM *vm, JNIEnv *env)

#define MODULE_FINI(name) \
	static int ModuleFini_##name(JavaVM *vm, JNIEnv *env); \
	static void __attribute__((constructor)) MODULE_FINI_##name() \
	{ \
		register_module_func(#name, ModuleFini_##name, 0); \
	} \
	static int ModuleFini_##name(JavaVM *vm, JNIEnv *env)


#ifdef __cplusplus
#define SQLITELINT_EXPORT extern "C"
#else
#define WECHAT_EXPORT
#endif

typedef int (*ModuleInitializer)(JavaVM *vm, JNIEnv *env);
SQLITELINT_EXPORT void register_module_func(const char *name, ModuleInitializer func, int init);


#endif
