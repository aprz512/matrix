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

package com.tencent.matrix.plugin.task

import com.android.build.gradle.internal.dsl.SigningConfig
import com.tencent.matrix.javalib.util.Log
import com.tencent.matrix.javalib.util.Util
import com.tencent.mm.arscutil.ArscUtil
import com.tencent.mm.arscutil.data.ResTable
import com.tencent.mm.arscutil.io.ArscReader
import com.tencent.mm.arscutil.io.ArscWriter
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.Pair

import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Created by jinqiuchen on 17/12/26.
 */

public class RemoveUnusedResourcesTask extends DefaultTask {

    private static final String TAG = "Matrix.MatrixPlugin.RemoveUnusedResourcesTask";

    public static final String BUILD_VARIANT = "build_variant";

    public Set<String> ignoreResources = new HashSet<>();

    RemoveUnusedResourcesTask() {
    }

    boolean ignoreResource(String name) {
        for (String path : ignoreResources) {
            if (name.matches(path)) {
                return true;
            }
        }
        return false;
    }

    String entryToResouceName(String entry) {
        String resourceName = "";
        if (!Util.isNullOrNil(entry)) {
            String typeName = entry.substring(4, entry.lastIndexOf('/'));
            String resName = entry.substring(entry.lastIndexOf('/') + 1, entry.indexOf('.'));
            if (!Util.isNullOrNil(typeName) && !Util.isNullOrNil(resName)) {
                int index = typeName.indexOf('-');
                if (index >= 0) {
                    typeName = typeName.substring(0, index);
                }
                resourceName = "R." + typeName + "." + resName;
            }
        }
        return resourceName;
    }

    /**
     *  @TaskAction 表示该Task要执行的动作,即在调用该Task时，该方法将被执行
     */
    @TaskAction
    void removeResources() {

        String variantName = this.inputs.properties.get(BUILD_VARIANT);
        Log.i(TAG, "variant %s, removeResources", variantName);

        project.extensions.android.applicationVariants.all { variant ->
            if (variant.name.equalsIgnoreCase(variantName)) {
                variant.outputs.forEach { output ->
                    String unsignedApkPath = output.outputFile.getAbsolutePath();
                    Log.i(RemoveUnusedResourcesTask.TAG, "original apk file %s", unsignedApkPath);
                    long startTime = System.currentTimeMillis();
                    removeUnusedResources(unsignedApkPath, project.getBuildDir().getAbsolutePath() + "/intermediates/symbols/${variant.name}/R.txt", variant.variantData.variantConfiguration.signingConfig);
                    Log.i(RemoveUnusedResourcesTask.TAG, "cost time %f s" , (System.currentTimeMillis() - startTime) / 1000.0f );
                }
            }
        }
    }

    void removeUnusedResources(String originalApk, String rTxtFile, SigningConfig signingConfig) {
        ZipOutputStream zipOutputStream = null;
        // 读取 gradle 插件配置信息
        boolean needSign = project.extensions.matrix.removeUnusedResources.needSign;
        boolean shrinkArsc = project.extensions.matrix.removeUnusedResources.shrinkArsc;
        String apksigner = project.extensions.matrix.removeUnusedResources.apksignerPath;
        if (needSign) {
            if (Util.isNullOrNil(apksigner)) {
                throw new GradleException("need sign apk but apksigner not found!");
            } else if (! new File(apksigner).exists()) {
                throw new GradleException( "need sign apk but apksigner " + apksigner + " was not exist!");
            } else if (signingConfig == null) {
                throw new GradleException("need sign apk but signingConfig not found!");
            }
        }
        try {
            try {
                File inputFile = new File(originalApk);
                Set<String> ignoreRes = project.extensions.matrix.removeUnusedResources.ignoreResources;
                // 加载配置的应该忽略的资源
                for (String res : ignoreRes) {
                    // 通配符转转正则表达式
                    // 配置的语法应该支持 * 啥的吧
                    ignoreResources.add(Util.globToRegexp(res));
                }
                // 加载配置的 未使用的资源，这个就是 ApkChecker 分析出来的资源
                Set<String> unusedResources = project.extensions.matrix.removeUnusedResources.unusedResources;
                Iterator<String> iterator = unusedResources.iterator();
                String res = null;
                while (iterator.hasNext()) {
                    res = iterator.next();
                    if (ignoreResource(res)) {
                        // 将需要忽略的资源从待删除集合中移除
                        iterator.remove();
                        Log.i(TAG, "ignore unused resources %s", res);
                    }
                }
                Log.i(TAG, "unused resources count:%d", unusedResources.size());

                // 移除资源后重新打包（签名）的路径
                String outputApk = inputFile.getParentFile().getAbsolutePath() + "/" + inputFile.getName().substring(0, inputFile.getName().indexOf('.')) + "_shrinked.apk";

                File outputFile = new File(outputApk);
                if (outputFile.exists()) {
                    Log.w(TAG, "output apk file %s is already exists! It will be deleted anyway!", outputApk);
                    outputFile.delete();
                    outputFile.createNewFile();
                }

                ZipFile zipInputFile = new ZipFile(inputFile);

                zipOutputStream = new ZipOutputStream(new FileOutputStream(outputFile));

                Map<String, Integer> resourceMap = new HashMap();
                Map<String, Pair<String, Integer>[]> styleableMap = new HashMap();
                File resTxtFile = new File(rTxtFile);
                readResourceTxtFile(resTxtFile, resourceMap, styleableMap);

                // 需要移除的资源集合 -> removeResources
                Map<String, Integer> removeResources = new HashMap<>();
                for (String resName : unusedResources) {
                    if (!ignoreResource(resName)) {
                        // 资源名称 -> 资源id
                        removeResources.put(resName, resourceMap.remove(resName));
                    }
                }


                for (ZipEntry zipEntry : zipInputFile.entries()) {
                    if (zipEntry.name.startsWith("res/")) {
                        // zipEntry.name --> res/mipmap-hdpi-v4/ic_launcher_round.png
                        // resourceName --> R.mipmap.ic_launcher_round.png
                        String resourceName = entryToResouceName(zipEntry.name);
                        if (!Util.isNullOrNil(resourceName)) {
                            // 如果有配置了 unusedResources，这里就不拷贝这个资源到新的 apk 里面了
                            if (removeResources.containsKey(resourceName)) {
                                Log.i(TAG, "remove unused resource %s", resourceName);
                                continue;
                            } else {
                                addZipEntry(zipOutputStream, zipEntry, zipInputFile);
                            }
                        } else {
                            addZipEntry(zipOutputStream, zipEntry, zipInputFile);
                        }
                    } else {
                        // 为啥 META-INF/ 下的文件也不拷贝
                        // 里面的几个签名文件可以不用管，因为后面会重新签名，但是还有别的文件呢
                        if (needSign && zipEntry.name.startsWith("META-INF/")) {
                            continue;
                        } else {
                            // shrinkArsc 需要精简 arsc 文件，这是大头
                            if (shrinkArsc && zipEntry.name.equalsIgnoreCase("resources.arsc") && unusedResources.size() > 0) {
                                File srcArscFile = new File(inputFile.getParentFile().getAbsolutePath() + "/resources.arsc");
                                File destArscFile = new File(inputFile.getParentFile().getAbsolutePath() + "/resources_shrinked.arsc");
                                if (srcArscFile.exists()) {
                                    srcArscFile.delete();
                                    srcArscFile.createNewFile();
                                }
                                // 将 zip 文件中的 .asrs 文件解压出来
                                unzipEntry(zipInputFile, zipEntry, srcArscFile);

                                // 分析 .arsc 文件，需要一张图配合看
                                // https://user-gold-cdn.xitu.io/2019/5/24/16ae9b85b2f4e918?imageView2/0/w/1280/h/960/format/webp/ignore-error/1
                                // 或者使用 010 打开看看（推荐）
                                ArscReader reader = new ArscReader(srcArscFile.getAbsolutePath());
                                ResTable resTable = reader.readResourceTable();
                                for (String resName : removeResources.keySet()) {
                                    ArscUtil.removeResource(resTable, removeResources.get(resName), resName);
                                }
                                // 重新生成 .arsc 文件
                                ArscWriter writer = new ArscWriter(destArscFile.getAbsolutePath());
                                writer.writeResTable(resTable);
                                Log.i(TAG, "shrink resources.arsc size %f KB", (srcArscFile.length() - destArscFile.length()) / 1024.0);
                                addZipEntry(zipOutputStream, zipEntry, destArscFile);
                            } else {
                                addZipEntry(zipOutputStream, zipEntry, zipInputFile);
                            }
                        }
                    }
                }

                if (zipOutputStream != null) {
                    zipOutputStream.close()
                }

                Log.i(TAG, "shrink apk size %f KB", (inputFile.length() - outputFile.length()) / 1024.0);
                if (needSign) {
                    // 调用 apksigner 程序，进行签名
                    Log.i(TAG, "resign apk...");
                    ProcessBuilder processBuilder = new ProcessBuilder();
                    processBuilder.command(apksigner, "sign", "-v",
                            "--ks", signingConfig.storeFile.getAbsolutePath(),
                            "--ks-pass", "pass:" + signingConfig.storePassword,
                            "--key-pass", "pass:" + signingConfig.keyPassword,
                            "--ks-key-alias", signingConfig.keyAlias,
                            outputFile.getAbsolutePath());
                    //Log.i(TAG, "%s", processBuilder.command());
                    Process process = processBuilder.start();
                    process.waitFor();
                    if (process.exitValue() != 0) {
                        throw new GradleException(process.getErrorStream().text);
                    }
                }
                String backApk = inputFile.getParentFile().getAbsolutePath() + "/" + inputFile.getName().substring(0, inputFile.getName().indexOf('.')) + "_back.apk";
                inputFile.renameTo(new File(backApk));
                outputFile.renameTo(new File(originalApk));

                //modify R.txt to delete the removed resources
                // 移除 styleable，只有完全没有使用到的才移除
                if (!removeResources.isEmpty()) {
                    Iterator<String> styleableItera =  styleableMap.keySet().iterator();
                    while (styleableItera.hasNext()) {
                        String styleable = styleableItera.next();
                        Pair<String, Integer>[] attrs = styleableMap.get(styleable);
                        int i = 0;
                        for (i = 0; i < attrs.length; i++) {
                            if (!removeResources.containsValue(attrs[i].right)) {
                                break
                            }
                        }
                        if (attrs.length > 0 && i == attrs.length) {
                            Log.i(TAG, "removed styleable " + styleable);
                            styleableItera.remove();
                        }
                    }
                    //Log.d(TAG, "styleable %s", styleableMap.keySet().size());
                    String newResTxtFile = resTxtFile.getParentFile().getAbsolutePath() + "/" + resTxtFile.getName().substring(0, resTxtFile.getName().indexOf('.')) + "_shrinked.txt";
                    shrinkResourceTxtFile(newResTxtFile, resourceMap, styleableMap);

                    //Other plugins such as "Tinker" may depend on the R.txt file, so we should not modify R.txt directly .
                    //new File(newResTxtFile).renameTo(resTxtFile);
                }

            } finally {
                if (zipOutputStream != null) {
                    zipOutputStream.close()
                }
            }
        } catch (Exception e) {
            Log.printErrStackTrace(TAG, e, "remove unused resources occur error!")
        }
    }

    private void shrinkResourceTxtFile(String resourceTxt, HashMap<String, Integer> resourceMap, Map<String, Pair<String, Integer>[]> styleableMap) throws IOException {
        BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(resourceTxt));
        try {
            for (String res : resourceMap.keySet()) {
                StringBuilder strBuilder = new StringBuilder();
                strBuilder.append("int").append(" ")
                        .append(res.substring(2, res.indexOf('.', 2))).append(" ")
                        .append(res.substring(res.indexOf('.', 2) + 1)).append(" ")
                        .append("0x" + Integer.toHexString(resourceMap.get(res)));
                //Log.d(TAG, "write %s to R.txt", strBuilder.toString());
                bufferedWriter.writeLine(strBuilder.toString());
            }
            for (String styleable : styleableMap.keySet()) {
                StringBuilder strBuilder = new StringBuilder();
                Pair<String, Integer>[] styleableAttrs = styleableMap.get(styleable);
                strBuilder.append("int[]").append(" ")
                        .append("styleable").append(" ")
                        .append(styleable.substring(styleable.indexOf('.', 2) + 1)).append(" ")
                        .append("{ ");
                for (int i = 0; i < styleableAttrs.length; i++) {
                    if (i != styleableAttrs.length - 1) {
                        strBuilder.append("0x" + Integer.toHexString(styleableAttrs[i].right)).append(", ");
                    } else {
                        strBuilder.append("0x" + Integer.toHexString(styleableAttrs[i].right));
                    }
                }
                strBuilder.append(" }");
                //Log.d(TAG, "write %s to R.txt", strBuilder.toString());
                bufferedWriter.writeLine(strBuilder.toString());
                for (int i = 0; i < styleableAttrs.length; i++) {
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append("int").append(" ")
                            .append("styleable").append(" ")
                            .append(styleableAttrs[i].left).append(" ")
                            .append(i);
                    //Log.d(TAG, "write %s to R.txt", stringBuilder.toString());
                    bufferedWriter.writeLine(stringBuilder.toString());
                }
            }
        } finally {
            bufferedWriter.close();
        }
    }

    /**
     * 将 R.txt 中的符号表内存读到map里面
     * resourceMap 里面是 string id dimens 之类的东西 --> int attr layout_editor_absoluteY 0x7f0200c5 -->  {"R.attr.layout_editor_absoluteY":"0x7f0200c5"}
     * styleableMap 里面是 styleable   --> {R.styleable.ViewBackgroundHelper: [{R.styleable.ViewBackgroundHelper_android_background,0x010100d4}, ...]}
     */
    private void readResourceTxtFile(File resTxtFile, Map<String, Integer> resourceMap, Map<String, Pair<String, Integer>[]> styleableMap) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new FileReader(resTxtFile));
        String line = bufferedReader.readLine();
        boolean styleable = false;
        String styleableName = "";
        ArrayList<String> styleableAttrs = new ArrayList<>();
        try {
            while (line != null) {
                String[] columns = line.split(" ");

                // int attr layout_editor_absoluteY 0x7f0200c5
                // int id dimensions 0x7f070033
                // ...
                if (columns.length >= 4) {
                    final String resourceName = "R." + columns[1] + "." + columns[2];
                    if (!columns[0].endsWith("[]") && columns[3].startsWith("0x")) {
                        if (styleable) {
                            styleable = false;
                            styleableName = "";
                        }
                        final String resId = parseResourceId(columns[3]);
                        if (!Util.isNullOrNil(resId)) {
                            resourceMap.put(resourceName, Integer.decode(resId));
                        }
                    }
                    // int[] styleable ViewStubCompat { 0x010100d0, 0x010100f2, 0x010100f3 }
                    else if (columns[1].equals("styleable")) {
                        if (columns[0].endsWith("[]")) {
                            if (columns.length > 5) {
                                styleableAttrs.clear();
                                styleable = true;
                                styleableName = "R." + columns[1] + "." + columns[2];
                                for (int i = 4; i < columns.length - 1; i++) {
                                    if (columns[i].endsWith(",")) {
                                        styleableAttrs.add(columns[i].substring(0, columns[i].length() - 1));
                                    } else {
                                        styleableAttrs.add(columns[i]);
                                    }
                                }
                                styleableMap.put(styleableName, new Pair<String, Integer>[styleableAttrs.size()]);
                            }
                        }
                        // int styleable ViewBackgroundHelper_android_background 0
                        else {
                            if (styleable && !Util.isNullOrNil(styleableName)) {
                                final int index = Integer.parseInt(columns[3]);
                                final String name = "R." + columns[1] + "." + columns[2];
                                styleableMap.get(styleableName)[index] = new Pair<String, Integer>(name, Integer.decode(parseResourceId(styleableAttrs.get(index))));
                            }
                        }
                    } else {
                        if (styleable) {
                            styleable = false;
                            styleableName = "";
                        }
                    }
                }
                line = bufferedReader.readLine();
            }
        } finally {
            bufferedReader.close();
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

    byte[] readFileContent(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            final BufferedInputStream bufferedInput = new BufferedInputStream(inputStream);
            int len;
            byte[] buffer = new byte[4096];
            while ((len = bufferedInput.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
            bufferedInput.close();
        } finally {
            output.close();
        }
        return output.toByteArray();
    }

    void unzipEntry(ZipFile zipFile, ZipEntry zipEntry, File destFile) {
        BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(destFile));
        InputStream inputStream = zipFile.getInputStream(zipEntry);
        outputStream.write(readFileContent(inputStream));
        outputStream.close();
    }

    void addZipEntry(ZipOutputStream zipOutputStream, ZipEntry zipEntry, File file) {
        ZipEntry writeEntry = new ZipEntry(zipEntry.getName());
        InputStream inputStream = new FileInputStream(file);
        byte[] content = readFileContent(inputStream);
        if (zipEntry.getMethod() == ZipEntry.DEFLATED) {
            writeEntry.setMethod(ZipEntry.DEFLATED);
        } else {
            writeEntry.setMethod(ZipEntry.STORED);
            CRC32 crc32 = new CRC32();
            crc32.update(content);
            writeEntry.setCrc(crc32.getValue());
        }
        writeEntry.setSize(content.length);
        zipOutputStream.putNextEntry(writeEntry);
        zipOutputStream.write(content);
        zipOutputStream.flush();
        zipOutputStream.closeEntry();
    }

    void addZipEntry(ZipOutputStream zipOutputStream, ZipEntry zipEntry, ZipFile zipFile) throws IOException {
        ZipEntry writeEntry = new ZipEntry(zipEntry.getName());
        InputStream inputStream = zipFile.getInputStream(zipEntry);
        byte[] content = readFileContent(inputStream);
        if (zipEntry.getMethod() == ZipEntry.DEFLATED) {
            writeEntry.setMethod(ZipEntry.DEFLATED);
        } else {
            writeEntry.setMethod(ZipEntry.STORED);
            writeEntry.setCrc(zipEntry.getCrc().longValue());
            writeEntry.setSize(zipEntry.size);
        }
        zipOutputStream.putNextEntry(writeEntry);
        zipOutputStream.write(content);
        zipOutputStream.flush();
        zipOutputStream.closeEntry();
    }
}
