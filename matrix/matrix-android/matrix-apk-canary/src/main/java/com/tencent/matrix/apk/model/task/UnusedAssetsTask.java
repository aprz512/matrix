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

package com.tencent.matrix.apk.model.task;


import com.google.common.collect.Ordering;
import com.google.gson.JsonArray;
import com.tencent.matrix.apk.model.exception.TaskExecuteException;
import com.tencent.matrix.apk.model.exception.TaskInitException;
import com.tencent.matrix.apk.model.job.JobConfig;
import com.tencent.matrix.apk.model.job.JobConstants;
import com.tencent.matrix.apk.model.result.TaskJsonResult;
import com.tencent.matrix.apk.model.result.TaskResult;
import com.tencent.matrix.apk.model.result.TaskResultFactory;
import com.tencent.matrix.apk.model.task.util.ApkConstants;
import com.tencent.matrix.apk.model.task.util.ApkUtil;
import com.tencent.matrix.javalib.util.Log;
import com.tencent.matrix.javalib.util.Util;

import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Created by jinqiuchen on 17/7/11.
 * 分析无用资源文件
 */

public class UnusedAssetsTask extends ApkTask {

    private static final String TAG = "Matrix.UnusedAssetsTask";

    private File inputFile;
    private final List<String> dexFileNameList;
    private final Set<String> ignoreSet;
    private final Set<String> assetsPathSet;
    private final Set<String> assetRefSet;

    public UnusedAssetsTask(JobConfig config, Map<String, String> params) {
        super(config, params);
        type = TaskFactory.TASK_TYPE_UNUSED_ASSETS;
        dexFileNameList = new ArrayList<>();
        ignoreSet = new HashSet<>();
        assetsPathSet = new HashSet<>();
        assetRefSet = new HashSet<>();
    }

    @Override
    public void init() throws TaskInitException {
        super.init();

        String inputPath = config.getUnzipPath();
        if (Util.isNullOrNil(inputPath)) {
            throw new TaskInitException(TAG + "---APK-UNZIP-PATH can not be null!");
        }
        inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            throw new TaskInitException(TAG + "---APK-UNZIP-PATH '" + inputPath + "' is not exist!");
        } else if (!inputFile.isDirectory()) {
            throw new TaskInitException(TAG + "---APK-UNZIP-PATH '" + inputPath + "' is not directory!");
        }
        if (params.containsKey(JobConstants.PARAM_IGNORE_ASSETS_LIST) && !Util.isNullOrNil(params.get(JobConstants.PARAM_IGNORE_ASSETS_LIST))) {
            String[] ignoreAssets = params.get(JobConstants.PARAM_IGNORE_ASSETS_LIST).split(",");
            Log.i(TAG, "ignore assets %d", ignoreAssets.length);
            for (String ignore : ignoreAssets) {
                ignoreSet.add(Util.globToRegexp(ignore));
            }
        }

        // 收集 dex 文件
        File[] files = inputFile.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(ApkConstants.DEX_FILE_SUFFIX)) {
                    dexFileNameList.add(file.getName());
                }
            }
        }
    }

    // 收集 asset 目录下文件
    private void findAssetsFile(File dir) throws IOException {
        if (dir != null && dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            for (File file : files) {
                if (file.isDirectory()) {
                    findAssetsFile(file);
                } else {
                    Log.d(TAG, "find asset file %s", file.getAbsolutePath());
                    assetsPathSet.add(file.getAbsolutePath());
                }
            }
        }
    }

    private void decodeCode() throws IOException {
        for (String dexFileName : dexFileNameList) {
            // 使用 apktool 加载 dex 文件
            DexBackedDexFile dexFile = DexFileFactory.loadDexFile(new File(inputFile, dexFileName), Opcodes.forApi(15));

            BaksmaliOptions options = new BaksmaliOptions();
            // class 类按自然排序
            List<? extends ClassDef> classDefs = Ordering.natural().sortedCopy(dexFile.getClasses());

            for (ClassDef classDef : classDefs) {
                // 从 ClassDef 中获取该 class 对应的 smali 代码
                String[] lines = ApkUtil.disassembleClass(classDef, options);
                if (lines != null) {
                    // 分析 smali 代码
                    readSmaliLines(lines);
                }
            }

        }
    }

    private void readSmaliLines(String[] lines) {
        if (lines == null) {
            return;
        }
        for (String line : lines) {
            line = line.trim();
            // 找 常量字符串，因为使用 assets 中的资源文件，方式是很单一的，需要制定文件的名字
            // 所以遍历 smali 代码，找到所有使用常量字符串的地方
            // const-string 的语法：
            // const-string v0, "LOG"        # 将v0寄存器赋值为字符串常量"LOG"
            // 这算是一种简单的分析方式
            if (!Util.isNullOrNil(line) && line.startsWith("const-string")) {
                String[] columns = line.split(",");
                if (columns.length == 2) {
                    // 获取 , 后面的
                    String assetFileName = columns[1].trim();
                    // 去除双引号
                    assetFileName = assetFileName.substring(1, assetFileName.length() - 1);
                    if (!Util.isNullOrNil(assetFileName)) {
                        // 遍历之前收集的所有资源文件的路径
                        for (String path : assetsPathSet) {
                            // 如果包含该文件
                            if (assetFileName.endsWith(path)) {
                                // 存放到 assetRefSet 里面
                                assetRefSet.add(path);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean ignoreAsset(String name) {
        for (String pattern : ignoreSet) {
            Log.d(TAG, "pattern %s", pattern);
            if (name.matches(pattern)) {
                return true;
            }
        }
        return false;
    }

    // 收集相对路径，里面去除了配置的忽略文件
    // 因为写忽略配置文件，肯定是相对 assets 目录的
    // --ignoreAssets
    // 最终结果： assetRefSet 里面存放的是被忽略的
    //  assetsPathSet 里面存放的是 asset 目录下的所有文件
    private void generateAssetsSet(String rootPath) {
        HashSet<String> relativeAssetsSet = new HashSet<String>();
        for (String path : assetsPathSet) {
            int index = path.indexOf(rootPath);
            if (index >= 0) {
                String relativePath = path.substring(index + rootPath.length() + 1);
                Log.d(TAG, "assets %s", relativePath);
                relativeAssetsSet.add(relativePath);
                if (ignoreAsset(relativePath)) {
                    Log.d(TAG, "ignore assets %s", relativePath);
                    assetRefSet.add(relativePath);
                }
            }
        }
        assetsPathSet.clear();
        assetsPathSet.addAll(relativeAssetsSet);
    }


    @Override
    public TaskResult call() throws TaskExecuteException {
        try {
            TaskResult taskResult = TaskResultFactory.factory(type, TaskResultFactory.TASK_RESULT_TYPE_JSON, config);
            long startTime = System.currentTimeMillis();
            File assetDir = new File(inputFile, ApkConstants.ASSETS_DIR_NAME);
            findAssetsFile(assetDir);
            generateAssetsSet(assetDir.getAbsolutePath());
            Log.i(TAG, "find all assets count: %d", assetsPathSet.size());
            decodeCode();
            // assetRefSet 里面存放的是 配置的忽略的资源 + smali 中引用到的资源
            Log.i(TAG, "find reference assets count: %d", assetRefSet.size());
            assetsPathSet.removeAll(assetRefSet);
            JsonArray jsonArray = new JsonArray();
            for (String name : assetsPathSet) {
                jsonArray.add(name);
            }
            ((TaskJsonResult) taskResult).add("unused-assets", jsonArray);
            taskResult.setStartTime(startTime);
            taskResult.setEndTime(System.currentTimeMillis());
            return taskResult;
        } catch (Exception e) {
            throw new TaskExecuteException(e.getMessage(), e);
        }
    }
}
