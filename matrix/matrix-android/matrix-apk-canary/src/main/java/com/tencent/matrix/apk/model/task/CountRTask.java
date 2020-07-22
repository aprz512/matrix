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

import com.android.dexdeps.ClassRef;
import com.android.dexdeps.DexData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tencent.matrix.apk.model.task.util.ApkConstants;
import com.tencent.matrix.apk.model.exception.TaskExecuteException;
import com.tencent.matrix.apk.model.exception.TaskInitException;
import com.tencent.matrix.apk.model.job.JobConfig;
import com.tencent.matrix.apk.model.result.TaskJsonResult;
import com.tencent.matrix.apk.model.result.TaskResult;
import com.tencent.matrix.apk.model.result.TaskResultFactory;
import com.tencent.matrix.apk.model.task.util.ApkUtil;
import com.tencent.matrix.javalib.util.Util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.tencent.matrix.apk.model.task.TaskFactory.TASK_TYPE_COUNT_R_CLASS;

/**
 * Created by jinqiuchen on 17/6/22.
 */

public class CountRTask extends ApkTask {

    private static final String TAG = "Matrix.CountRTask";

    private File inputFile;
    private final List<String> dexFileNameList;
    private final List<RandomAccessFile> dexFileList;
    private final Map<String, Integer> classesMap;

    public CountRTask(JobConfig config, Map<String, String> params) {
        super(config, params);
        type = TASK_TYPE_COUNT_R_CLASS;
        dexFileNameList = new ArrayList<>();
        dexFileList = new ArrayList<>();
        classesMap = new HashMap<>();
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
        }
        if (!inputFile.isDirectory()) {
            throw new TaskInitException(TAG + "---APK-UNZIP-PATH '" + inputPath + "' is not directory!");
        }

        File[] files = inputFile.listFiles();
        try {
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(ApkConstants.DEX_FILE_SUFFIX)) {
                        dexFileNameList.add(file.getName());
                        RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
                        dexFileList.add(randomAccessFile);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            throw new TaskInitException(e.getMessage(), e);
        }

    }

    private String getOuterClassName(String className) {
        if (!Util.isNullOrNil(className)) {
            int index = className.indexOf('$');
            if (index >= 0) {
                className = className.substring(0, index);
            }
        }
        return className;
    }

    @Override
    public TaskResult call() throws TaskExecuteException {
        try {
            TaskResult taskResult = TaskResultFactory.factory(type, TaskResultFactory.TASK_RESULT_TYPE_JSON, config);
            long startTime = System.currentTimeMillis();
            Map<String, String> classProguardMap = config.getProguardClassMap();
            for (RandomAccessFile dexFile : dexFileList) {
                DexData dexData = new DexData(dexFile);
                dexData.load();
                dexFile.close();
                ClassRef[] defClassRefs = dexData.getInternalReferences();
                // 遍历dex中的类
                for (ClassRef classRef : defClassRefs) {
                    // 获取类名
                    String className = ApkUtil.getNormalClassName(classRef.getName());
                    if (classProguardMap.containsKey(className)) {
                        // 根据混淆文件，获取未混淆类名
                        className = classProguardMap.get(className);
                    }
                    // 去除内部类的后半部分
                    // com.example.sample.R$styleable -> com.example.sample.R
                    String pureClassName = getOuterClassName(className);
                    // 一个光 R 是个什么鬼？？？
                    // 连包名都没有，java可以运行吗？
                    if (pureClassName.endsWith(".R") || "R".equals(pureClassName)) {
                        // 获取该类的字段长度
                        if (!classesMap.containsKey(pureClassName)) {
                            classesMap.put(pureClassName, classRef.getFieldArray().length);
                        } else {
                            // 累加内部类
                            // com.example.sample.R$string
                            // com.example.sample.R$layout
                            classesMap.put(pureClassName, classesMap.get(pureClassName) + classRef.getFieldArray().length);
                        }
                    }
                }
            }

            JsonArray jsonArray = new JsonArray();
            long totalSize = 0;
            Map<String, String> proguardClassMap = config.getProguardClassMap();
            for (Map.Entry<String, Integer> entry : classesMap.entrySet()) {
                JsonObject jsonObject = new JsonObject();
                if (proguardClassMap.containsKey(entry.getKey())) {
                    jsonObject.addProperty("name", proguardClassMap.get(entry.getKey()));
                } else {
                    jsonObject.addProperty("name", entry.getKey());
                }
                jsonObject.addProperty("field-count", entry.getValue());
                totalSize += entry.getValue();
                jsonArray.add(jsonObject);
            }
            ((TaskJsonResult) taskResult).add("R-count", jsonArray.size());
            ((TaskJsonResult) taskResult).add("Field-counts", totalSize);

            ((TaskJsonResult) taskResult).add("R-classes", jsonArray);
            taskResult.setStartTime(startTime);
            taskResult.setEndTime(System.currentTimeMillis());
            return taskResult;
        } catch (Exception e) {
            throw new TaskExecuteException(e.getMessage(), e);
        }
    }
}
