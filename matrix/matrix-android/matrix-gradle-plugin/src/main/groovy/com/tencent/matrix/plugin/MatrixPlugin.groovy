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

package com.tencent.matrix.plugin


import com.tencent.matrix.javalib.util.Log
import com.tencent.matrix.javalib.util.Util
import com.tencent.matrix.plugin.extension.MatrixDelUnusedResConfiguration
import com.tencent.matrix.plugin.extension.MatrixExtension
import com.tencent.matrix.plugin.task.RemoveUnusedResourcesTask
import com.tencent.matrix.trace.extension.MatrixTraceExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Created by zhangshaowen on 17/6/16.
 */
class MatrixPlugin implements Plugin<Project> {
    private static final String TAG = "Matrix.MatrixPlugin"

    @Override
    void apply(Project project) {
        // 创建 Extension
        project.extensions.create("matrix", MatrixExtension)
        project.matrix.extensions.create("trace", MatrixTraceExtension)
        project.matrix.extensions.create("removeUnusedResources", MatrixDelUnusedResConfiguration)
        if (!project.plugins.hasPlugin('com.android.application')) {
            throw new GradleException('Matrix Plugin, Android Application plugin required')
        }

        // project.afterEvaluate算是一个回调，它表示所有的模块都已经配置完了，可以准备执行task了。这个时机可以 Hook 我们想要的 transform。
        project.afterEvaluate {
            def android = project.extensions.android
            def configuration = project.matrix
            android.applicationVariants.all { variant ->

                if (configuration.trace.enable) {
                    // 获取 Extension 的值
                    // trace 处理
                    com.tencent.matrix.trace.transform.MatrixTraceTransform.inject(project, configuration.trace, variant.getVariantData().getScope())
                }

                if (configuration.removeUnusedResources.enable) {
                    if (Util.isNullOrNil(configuration.removeUnusedResources.variant) || variant.name.equalsIgnoreCase(configuration.removeUnusedResources.variant)) {
                        Log.i(TAG, "removeUnusedResources %s", configuration.removeUnusedResources)
                        RemoveUnusedResourcesTask removeUnusedResourcesTask = project.tasks.create("remove" + variant.name.capitalize() + "UnusedResources", RemoveUnusedResourcesTask)
                        // 给 task 注册一个键值对，task里面可以获取到
                        removeUnusedResourcesTask.inputs.property(RemoveUnusedResourcesTask.BUILD_VARIANT, variant.name)
                        // 添加任务到 project
                        project.tasks.add(removeUnusedResourcesTask)
                        // 创建任务的依赖关系
                        // 可以输出一下打包时运行的各个任务
                        // package 后面是 assemble，assemble 类型是 task，空 task，是一个锚点
                        // package 类型是 PackageApplication，看源码是打包 apk
                        // 所以是在打包完成之后做的无用资源处理
                        removeUnusedResourcesTask.dependsOn variant.packageApplication
                        variant.assemble.dependsOn removeUnusedResourcesTask
                    }
                }

            }
        }
    }
}
