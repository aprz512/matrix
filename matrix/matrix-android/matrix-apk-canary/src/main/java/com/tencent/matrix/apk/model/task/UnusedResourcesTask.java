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
import com.tencent.matrix.apk.model.task.util.ApkConstants;
import com.tencent.matrix.apk.model.exception.TaskExecuteException;
import com.tencent.matrix.apk.model.exception.TaskInitException;
import com.tencent.matrix.apk.model.job.JobConfig;
import com.tencent.matrix.apk.model.job.JobConstants;
import com.tencent.matrix.apk.model.result.TaskJsonResult;
import com.tencent.matrix.apk.model.result.TaskResult;
import com.tencent.matrix.apk.model.result.TaskResultFactory;
import com.tencent.matrix.apk.model.task.util.ApkResourceDecoder;
import com.tencent.matrix.apk.model.task.util.ApkUtil;
import com.tencent.matrix.javalib.util.FileUtil;
import com.tencent.matrix.javalib.util.Log;
import com.tencent.matrix.javalib.util.Util;

import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import brut.androlib.AndrolibException;


/**
 * Created by jinqiuchen on 17/7/11.
 */

public class UnusedResourcesTask extends ApkTask {

    private static final String TAG = "Matrix.UnusedResourcesTask";

    private File inputFile;
    private File resourceTxt;
    private File mappingTxt;
    private File resMappingTxt;
    private final List<String> dexFileNameList;

    // key 混淆后
    // value 混淆前
    private final Map<String, String> rclassProguardMap;

    // key  资源 id -- 0x7f020001
    // value 资源名
    private final Map<String, String> resourceDefMap;

    // key 资源名
    // value attr 的资源id
    private final Map<String, Set<String>> styleableMap;
    private final Set<String> resourceRefSet;
    private final Set<String> unusedResSet;
    private final Set<String> ignoreSet;
    private final Map<String, Set<String>> nonValueReferences;
    private Stack<String> visitPath;

    public UnusedResourcesTask(JobConfig config, Map<String, String> params) {
        super(config, params);
        type = TaskFactory.TASK_TYPE_UNUSED_RESOURCES;
        dexFileNameList = new ArrayList<>();
        ignoreSet = new HashSet<>();
        rclassProguardMap = new HashMap<>();
        resourceDefMap = new HashMap<>();
        styleableMap = new HashMap<>();
        resourceRefSet = new HashSet<>();
        unusedResSet = new HashSet<>();
        nonValueReferences = new HashMap<>();
        visitPath = new Stack<String>();
    }

    @Override
    public void init() throws TaskInitException {
        super.init();

        String inputPath = config.getUnzipPath();
        if (Util.isNullOrNil(inputPath)) {
            throw new TaskInitException(TAG + "---APK-UNZIP-PATH can not be null!");
        }
        if (!params.containsKey(JobConstants.PARAM_R_TXT) || Util.isNullOrNil(params.get(JobConstants.PARAM_R_TXT))) {
            throw new TaskInitException(TAG + "---The File 'R.txt' can not be null!");
        }
        resourceTxt = new File(params.get(JobConstants.PARAM_R_TXT));
        if (!FileUtil.isLegalFile(resourceTxt)) {
            throw new TaskInitException(TAG + "---The Resource declarations file 'R.txt' is not legal!");
        }
        inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            throw new TaskInitException(TAG + "---APK-UNZIP-PATH '" + inputPath + "' is not exist!");
        } else if (!inputFile.isDirectory()) {
            throw new TaskInitException(TAG + "---APK-UNZIP-PATH '" + inputPath + "' is not directory!");
        }
        if (!Util.isNullOrNil(config.getMappingFilePath())) {
            mappingTxt = new File(config.getMappingFilePath());
            if (!FileUtil.isLegalFile(mappingTxt)) {
                throw new TaskInitException(TAG + "---The Proguard mapping file 'mapping.txt' is not legal!");
            }
        }
        if (params.containsKey(JobConstants.PARAM_IGNORE_RESOURCES_LIST) && !Util.isNullOrNil(params.get(JobConstants.PARAM_IGNORE_RESOURCES_LIST))) {
            String[] ignoreRes = params.get(JobConstants.PARAM_IGNORE_RESOURCES_LIST).split(",");
            for (String ignore : ignoreRes) {
                ignoreSet.add(Util.globToRegexp(ignore));
            }
        }
        if (!Util.isNullOrNil(config.getResMappingFilePath())) {
            resMappingTxt = new File(config.getResMappingFilePath());
            if (!FileUtil.isLegalFile(resMappingTxt)) {
                throw new TaskInitException(TAG + "---The Resguard mapping file 'resguard-mapping.txt' is not legal!");
            }
        }

        File[] files = inputFile.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(ApkConstants.DEX_FILE_SUFFIX)) {
                    dexFileNameList.add(file.getName());
                }
            }
        }

    }

    private String parseResourceId(String resId) {
        if (!Util.isNullOrNil(resId) && resId.startsWith("0x")) {
            if (resId.length() == 10) {
                return resId;
            } else if (resId.length() < 10) {
                StringBuilder strBuilder = new StringBuilder(resId);
                for (int i = 0; i < 10 - resId.length(); i++) {
                    strBuilder.append('0');
                }
                return strBuilder.toString();
            }
        }
        return "";
    }

    private String parseResourceNameFromProguard(String entry) {
        if (!Util.isNullOrNil(entry)) {
            String[] columns = entry.split("->");
            if (columns.length == 2) {
                int index = columns[1].indexOf(':');
                if (index >= 0) {
                    final String className = ApkUtil.getNormalClassName(columns[0]);
                    final String fieldName = columns[1].substring(0, index);
                    if (!rclassProguardMap.isEmpty()) {
                        String resource = className.replace('$', '.') + "." + fieldName;
                        if (rclassProguardMap.containsKey(resource)) {
                            return rclassProguardMap.get(resource);
                        } else {
                            return "";
                        }
                    } else {
                        if (ApkUtil.isRClassName(ApkUtil.getPureClassName(className))) {
                            return (ApkUtil.getPureClassName(className) + "." + fieldName).replace('$', '.');
                        }
                    }
                }
            }
        }
        return "";
    }

    private void readResourceTxtFile() throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new FileReader(resourceTxt));
        String line = bufferedReader.readLine();
        try {
            while (line != null) {
                String[] columns = line.split(" ");
                if (columns.length >= 4) {
                    final String resourceName = "R." + columns[1] + "." + columns[2];
                    if (!columns[0].endsWith("[]") && columns[3].startsWith("0x")) {
                        if (columns[3].startsWith("0x01")) {
                            Log.d(TAG, "ignore system resource %s", resourceName);
                        } else {
                            final String resId = parseResourceId(columns[3]);
                            if (!Util.isNullOrNil(resId)) {
                                resourceDefMap.put(resId, resourceName);
                            }
                        }
                    } else {
                        Log.d(TAG, "ignore resource %s", resourceName);
                        if (columns[0].endsWith("[]") && columns.length > 5) {
                            Set<String> attrReferences = new HashSet<String>();
                            for (int i = 4; i < columns.length; i++) {
                                if (columns[i].endsWith(",")) {
                                    attrReferences.add(columns[i].substring(0, columns[i].length() - 1));
                                } else {
                                    attrReferences.add(columns[i]);
                                }
                            }
                            styleableMap.put(resourceName, attrReferences);
                        }
                    }
                }
                line = bufferedReader.readLine();
            }
        } finally {
            bufferedReader.close();
        }
    }

    private void readMappingTxtFile() throws IOException {
        // com.tencent.mm.R$string -> com.tencent.mm.R$l:
        //      int fade_in_property_anim -> aRW

        if (mappingTxt != null) {
            BufferedReader bufferedReader = new BufferedReader(new FileReader(mappingTxt));
            String line = bufferedReader.readLine();
            boolean readRField = false;
            String beforeClass = "", afterClass = "";
            try {
                while (line != null) {
                    if (!line.startsWith(" ")) {
                        String[] pair = line.split("->");
                        if (pair.length == 2) {
                            beforeClass = pair[0].trim();
                            afterClass = pair[1].trim();
                            afterClass = afterClass.substring(0, afterClass.length() - 1);
                            if (!Util.isNullOrNil(beforeClass) && !Util.isNullOrNil(afterClass) && ApkUtil.isRClassName(ApkUtil.getPureClassName(beforeClass))) {
                                Log.d(TAG, "before:%s,after:%s", beforeClass, afterClass);
                                readRField = true;
                            } else {
                                readRField = false;
                            }
                        } else {
                            readRField = false;
                        }
                    } else {
                        if (readRField) {
                            String[] entry = line.split("->");
                            if (entry.length == 2) {
                                String key = entry[0].trim();
                                String value = entry[1].trim();
                                if (!Util.isNullOrNil(key) && !Util.isNullOrNil(value)) {
                                    String[] field = key.split(" ");
                                    if (field.length == 2) {
                                        Log.d(TAG, "%s -> %s", afterClass.replace('$', '.') + "." + value, ApkUtil.getPureClassName(beforeClass).replace('$', '.') + "." + field[1]);
                                        rclassProguardMap.put(afterClass.replace('$', '.') + "." + value, ApkUtil.getPureClassName(beforeClass).replace('$', '.') + "." + field[1]);
                                    }
                                }
                            }
                        }
                    }
                    line = bufferedReader.readLine();
                }
            } finally {
                bufferedReader.close();
            }
        }
    }

    private void decodeCode() throws IOException {
        for (String dexFileName : dexFileNameList) {
            DexBackedDexFile dexFile = DexFileFactory.loadDexFile(new File(inputFile, dexFileName), Opcodes.forApi(15));

            BaksmaliOptions options = new BaksmaliOptions();
            List<? extends ClassDef> classDefs = Ordering.natural().sortedCopy(dexFile.getClasses());

            for (ClassDef classDef : classDefs) {
                String[] lines = ApkUtil.disassembleClass(classDef, options);
                if (lines != null) {
                    readSmaliLines(lines);
                }
            }

        }
    }

    /*

        1. const

        const v6, 0x7f0c0061

        2. sget

        // app 生成的是 static int 的，所以直接转换为了数值
        // 但是 lib 里面不是  final 的，所以会是引用的方式
        sget v6, Lcom/tencent/mm/R$string;->chatting_long_click_menu_revoke_msg:I
        sget v1, Lcom/tencent/mm/libmmui/R$id;->property_anim:I

        3. sput

        sput-object v0, Lcom/tencent/mm/plugin_welab_api/R$styleable;->ActionBar:[I   //define resource in R.java

        4. array-data

        :array_0
        .array-data 4
            0x7f0a0022
            0x7f0a0023
        .end array-data



    */

    private void readSmaliLines(String[] lines) {
        if (lines == null) {
            return;
        }
        boolean arrayData = false;
        for (String line : lines) {
            line = line.trim();
            if (!Util.isNullOrNil(line)) {
                // 直接使用资源id
                // 似乎没有考虑          // const-string v1, "activity_main" 这样的情况啊
                if (line.startsWith("const")) {
                    String[] columns = line.split(" ");
                    if (columns.length >= 3) {
                        final String resId = parseResourceId(columns[2].trim());
                        if (!Util.isNullOrNil(resId) && resourceDefMap.containsKey(resId)) {
                            resourceRefSet.add(resourceDefMap.get(resId));
                        }
                    }
                } else if (line.startsWith("sget")) {

                    // 使用自定义属性，反编译出来的是这样的
                    // sget-object v0, Lcom/example/sample/R$styleable;->FloatFrameView:[I
                    String[] columns = line.split(" ");
                    if (columns.length >= 3) {
                        final String resourceRef = parseResourceNameFromProguard(columns[2].trim());
                        if (!Util.isNullOrNil(resourceRef)) {
                            Log.d(TAG, "find resource reference %s", resourceRef);
                            if (styleableMap.containsKey(resourceRef)) {
                                //reference of R.styleable.XXX
                                // 添加 styleable 的各个属性
                                // 先从 resourceDefMap 中根据 id 值，获取名字
                                // 然后将名字存入 resourceRefSet
                                for (String attr : styleableMap.get(resourceRef)) {
                                    resourceRefSet.add(resourceDefMap.get(attr));
                                }
                            } else {
                                resourceRefSet.add(resourceRef);
                            }
                        }
                    }
                } else if (line.startsWith(".array-data 4")) {
                    arrayData = true;
                } else if (line.startsWith(".end array-data")) {
                    arrayData = false;
                } else {
                    if (arrayData) {
                        String[] columns = line.split(" ");
                        if (columns.length > 0) {
                            final String resId = parseResourceId(columns[0].trim());
                            if (!Util.isNullOrNil(resId) && resourceDefMap.containsKey(resId)) {
                                Log.d(TAG, "array field resource, %s", resId);
                                resourceRefSet.add(resourceDefMap.get(resId));
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * 啃到硬骨头了
     * 本来想不使用 appcompat 包里面的东西，资源好分析一些
     * 但是，不知道为啥，build/intermediates/symbols/xxx/R.txt 就生成不出来了
     * 所以就不深入研究 ApkResourceDecoder.decodeResourcesRef 这个里面到底搞了啥了
     *
     *
     */
    private void decodeResources() throws IOException, InterruptedException, AndrolibException, XmlPullParserException {
        File manifestFile = new File(inputFile, ApkConstants.MANIFEST_FILE_NAME);
        File arscFile = new File(inputFile, ApkConstants.ARSC_FILE_NAME);
        File resDir = new File(inputFile, ApkConstants.RESOURCE_DIR_NAME);
        if (!resDir.exists()) {
            resDir = new File(inputFile, ApkConstants.RESOURCE_DIR_PROGUARD_NAME);
        }

        Map<String, Set<String>> fileResMap = new HashMap<>();
        Set<String> valuesReferences = new HashSet<>();

        ApkResourceDecoder.decodeResourcesRef(manifestFile, arscFile, resDir, fileResMap, valuesReferences);

        Map<String, String> resguardMap = config.getResguardMap();

        // fileResMap 储存的是：
        // key 资源文件名字 --- R.drawable.abc_ic_voice_search_api_material
        // value 是该资源文件中引用的其他资源名字： R.android:color.white
        // R.drawable.abc_ic_voice_search_api_material 这个文件还引用了 attr 才对，怎么没有输出呢？
        // 打印出来发现 ?attr/xxxx 经过编译之后，变成了 ？xxx
        // 所以，就没有添加到集合中去，那么问题来了 XmlPullResourceRefDecoder 为啥要判断 /
        for (String resource : fileResMap.keySet()) {
            Set<String> result = new HashSet<>();
//            Log.e("-------", "resource = " + resource);
            for (String resName : fileResMap.get(resource)) {
//                Log.e("-------", "resName = " + resName);
                if (resguardMap.containsKey(resName)) {
                    result.add(resguardMap.get(resName));
                } else {
                    result.add(resName);
                }
            }
            if (resguardMap.containsKey(resource)) {
                nonValueReferences.put(resguardMap.get(resource), result);
            } else {
                nonValueReferences.put(resource, result);
            }
        }


        // values 有 n 个文件夹 ： values-pl values-v24 等等
        // 判断里面的 item 有没有使用 @ 或者 attr 方式的，有就添加进来
        for (String resource : valuesReferences) {
            if (resguardMap.containsKey(resource)) {
                resourceRefSet.add(resguardMap.get(resource));
            } else {
                resourceRefSet.add(resource);
            }
        }

        // resourceRefSet 现在储存的是 values 里面的引用的资源 + 代码中使用的资源
        for (String resource : resourceRefSet) {
            readChildReference(resource);
        }

        // 将需要忽略的资源添加到 resourceRefSet
        for (String resource : unusedResSet) {
            if (ignoreResource(resource)) {
                resourceRefSet.add(resource);
                ignoreChildResource(resource);
            }
        }
    }

    private boolean ignoreResource(String name) {
        for (String pattern : ignoreSet) {
            if (name.matches(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 深度遍历资源引用关系
     * 以代码里面的资源引用 + values 的资源引用为根，遍历资源
     * 非 values 的资源大都在代码中被使用，其余的也是互相引用，所以根的逻辑没问题
     * 比如：R.layout.activity_main，它肯定是在代码中被使用
     */
    private void readChildReference(String resource) throws IllegalStateException {
        if (nonValueReferences.containsKey(resource)) {
            visitPath.push(resource);
            // 获取该资源引用的其他资源
            Set<String> childReference = nonValueReferences.get(resource);
            // 资源有被引用则从 unusedResSet 里面移除
            // 验证一下，如果 a 引用 b，a没有用到，b会被发现吗？
            unusedResSet.removeAll(childReference);
            for (String reference : childReference) {
                if (!visitPath.contains(reference)) {
                    readChildReference(reference);
                } else {
                    visitPath.push(reference);
                    throw new IllegalStateException("Found resource cycle! " + visitPath.toString());
                }
            }
            visitPath.pop();
        }
    }

    private void ignoreChildResource(String resource) {
        if (nonValueReferences.containsKey(resource)) {
            Set<String> childReference = nonValueReferences.get(resource);
            resourceRefSet.addAll(childReference);
        }
    }


    @Override
    public TaskResult call() throws TaskExecuteException {
        try {
            TaskResult taskResult = TaskResultFactory.factory(type, TaskResultFactory.TASK_RESULT_TYPE_JSON, config);
            long startTime = System.currentTimeMillis();
            readMappingTxtFile();
            readResourceTxtFile();
            // 将所有资源标记未使用
            unusedResSet.addAll(resourceDefMap.values());
            Log.i(TAG, "find resource declarations %d items.", unusedResSet.size());
            decodeCode();
            Log.i(TAG, "find resource references in classes: %d items.", resourceRefSet.size());
            decodeResources();
            Log.i(TAG, "find resource references %d items.", resourceRefSet.size());
            unusedResSet.removeAll(resourceRefSet);
            Log.i(TAG, "find unused references %d items", unusedResSet.size());
            Log.d(TAG, "find unused references %s", unusedResSet.toString());
            JsonArray jsonArray = new JsonArray();
            for (String name : unusedResSet) {
                jsonArray.add(name);
            }
            ((TaskJsonResult) taskResult).add("unused-resources", jsonArray);
            taskResult.setStartTime(startTime);
            taskResult.setEndTime(System.currentTimeMillis());
            return taskResult;
        } catch (Exception e) {
            throw new TaskExecuteException(e.getMessage(), e);
        }
    }
}
