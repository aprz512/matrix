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

package com.tencent.matrix.resource.analyzer;

import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassInstance.FieldValue;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.HahaHelper;
import com.squareup.haha.perflib.Heap;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.StackTrace;
import com.squareup.haha.perflib.analysis.ShortestDistanceVisitor;
import com.tencent.matrix.resource.analyzer.model.DuplicatedBitmapResult;
import com.tencent.matrix.resource.analyzer.model.DuplicatedBitmapResult.DuplicatedBitmapEntry;
import com.tencent.matrix.resource.analyzer.model.ExcludedBmps;
import com.tencent.matrix.resource.analyzer.model.HeapSnapshot;
import com.tencent.matrix.resource.analyzer.model.ReferenceChain;
import com.tencent.matrix.resource.analyzer.model.ReferenceNode;
import com.tencent.matrix.resource.analyzer.utils.AnalyzeUtil;
import com.tencent.matrix.resource.analyzer.utils.ShortestPathFinder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tencent.matrix.resource.analyzer.utils.ShortestPathFinder.Result;

/**
 * Created by tangyinsheng on 2017/6/6.
 * 分析内存中重复的 bitmap
 */

public class DuplicatedBitmapAnalyzer implements HeapSnapshotAnalyzer<DuplicatedBitmapResult> {
    private final int mMinBmpLeakSize;
    private final ExcludedBmps mExcludedBmps;
    private Field mMStackField = null;
    private Field mMLengthField = null;
    private Field mMValueOffsetField = null;

    public DuplicatedBitmapAnalyzer(int minBmpLeakSize, ExcludedBmps excludedBmps) {
        mMinBmpLeakSize = minBmpLeakSize;
        mExcludedBmps = excludedBmps;
    }

    @Override
    public DuplicatedBitmapResult analyze(HeapSnapshot heapSnapshot) {
        final long analysisStartNanoTime = System.nanoTime();

        try {
            final Snapshot snapshot = heapSnapshot.getSnapshot();
            new ShortestDistanceVisitor().doVisit(snapshot.getGCRoots());
            return findDuplicatedBitmap(analysisStartNanoTime, snapshot);
        } catch (Throwable e) {
            e.printStackTrace();
            return DuplicatedBitmapResult.failure(e, AnalyzeUtil.since(analysisStartNanoTime));
        }
    }

    private DuplicatedBitmapResult findDuplicatedBitmap(long analysisStartNanoTime, Snapshot snapshot) {
        final ClassObj bitmapClass = snapshot.findClass("android.graphics.Bitmap");
        if (bitmapClass == null) {
            return DuplicatedBitmapResult.noDuplicatedBitmap(AnalyzeUtil.since(analysisStartNanoTime));
        }

        final Map<ArrayInstance, Instance> byteArrayToBitmapMap = new HashMap<>();
        final Set<ArrayInstance> byteArrays = new HashSet<>();

        final List<Instance> reachableInstances = new ArrayList<>();
        for (Heap heap : snapshot.getHeaps()) {
            /*
            heap 分为如下几个：
            default heap：当系统未指定堆时。
            image heap：系统启动映像，包含启动期间预加载的类。此处的分配保证绝不会移动或消失。
            zygote heap：写时复制堆，其中的应用进程是从 Android 系统中派生的。
            app heap：您的应用在其中分配内存的主堆。
            JNI heap：显示 Java 原生接口 (JNI) 引用被分配和释放到什么位置的堆。

            参考文档：https://developer.android.com/studio/profile/memory-profiler?hl=zh-cn
             */
            if (!"default".equals(heap.getName()) && !"app".equals(heap.getName())) {
                continue;
            }

            // 获取 heap 中的 bitmap 实例
            final List<Instance> bitmapInstances = bitmapClass.getHeapInstances(heap.getId());
            for (Instance bitmapInstance : bitmapInstances) {
                // 默认值是 Integer.MAX_VALUE，说明没有到 gc roots 的路径
                if (bitmapInstance.getDistanceToGcRoot() == Integer.MAX_VALUE) {
                    continue;
                }
                reachableInstances.add(bitmapInstance);
            }
            // 遍历那些可以到达 gc roots 的实例
            for (Instance bitmapInstance : reachableInstances) {
                // 获取 bitmap 的 mBuffer 字段
                ArrayInstance buffer = HahaHelper.fieldValue(((ClassInstance) bitmapInstance).getValues(), "mBuffer");
                if (buffer != null) {
                    // sizeof(byte) * bufferLength -> bufferSize
                    final int bufferSize = buffer.getSize();
                    // 大小小于阈值，跳过
                    if (bufferSize < mMinBmpLeakSize) {
                        // Ignore tiny bmp leaks.
                        System.out.println(" + Skiped a bitmap with size: " + bufferSize);
                        continue;
                    }
                    // 为了避免 key 重复，就将 buffer clone 一份
                    // 什么情况下 bitmap 会使用同一个 buffer
                    if (byteArrayToBitmapMap.containsKey(buffer)) {
                        buffer = cloneArrayInstance(buffer);
                    }
                    byteArrayToBitmapMap.put(buffer, bitmapInstance);
                } else {
                    System.out.println(" + Skiped a no-data bitmap");
                }
            }
            byteArrays.addAll(byteArrayToBitmapMap.keySet());
        }

        if (byteArrays.size() <= 1) {
            return DuplicatedBitmapResult.noDuplicatedBitmap(AnalyzeUtil.since(analysisStartNanoTime));
        }

        final List<DuplicatedBitmapEntry> duplicatedBitmapEntries = new ArrayList<>();

        final List<Set<ArrayInstance>> commonPrefixSets = new ArrayList<>();
        final List<Set<ArrayInstance>> reducedPrefixSets = new ArrayList<>();
        commonPrefixSets.add(byteArrays);

        // Cache the values since instance.getValues() recreates the array on every invocation.
        final Map<ArrayInstance, Object[]> cachedValues = new HashMap<>();
        for (ArrayInstance instance : byteArrays) {
            cachedValues.put(instance, instance.getValues());
        }

        // 这里面一长段都是校验 mBuffer 是否相等的算法
        // 更纯粹的逻辑可以看这里：
        //https://android.googlesource.com/platform/tools/base/+/studio-master-dev/perflib/src/main/java/com/android/tools/perflib/heap/memoryanalyzer/DuplicatedBitmapAnalyzerTask.java
        //
        int columnIndex = 0;
        while (!commonPrefixSets.isEmpty()) {
            for (Set<ArrayInstance> commonPrefixArrays : commonPrefixSets) {
                Map<Object, Set<ArrayInstance>> entryClassifier = new HashMap<>(
                        commonPrefixArrays.size());

                // 将所有 mBuffer，按照 mBuffer[columnIndex] 的值进行分组
                // columnIndex 会递增
                // columnIndex = 0，就是按照 mBuffer[0] 的值将所有的 mBuffer 进行分组
                // 最外层的 while 循环再次进入时，commonPrefixArrays 就是按照 mBuffer[0] 分组好了的
                // 然后 columnIndex = 1，将 commonPrefixArrays 里面的 set 再细分，按照 mBuffer[1] 分组

                // arrayInstance 是 mBuffer
                for (ArrayInstance arrayInstance : commonPrefixArrays) {
                    // mBuffer 里面的第 columnIndex 个元素
                    final Object element = cachedValues.get(arrayInstance)[columnIndex];
                    if (entryClassifier.containsKey(element)) {
                        entryClassifier.get(element).add(arrayInstance);
                    } else {
                        Set<ArrayInstance> instanceSet = new HashSet<>();
                        instanceSet.add(arrayInstance);
                        entryClassifier.put(element, instanceSet);
                    }
                }

                for (Set<ArrayInstance> branch : entryClassifier.values()) {
                    if (branch.size() <= 1) {
                        // Unique branch, ignore it and it won't be counted towards duplication.
                        continue;
                    }

                    final Set<ArrayInstance> terminatedArrays = new HashSet<>();

                    // Move all ArrayInstance that we have hit the end of to the candidate result list.
                    for (ArrayInstance instance : branch) {
                        if (HahaHelper.getArrayInstanceLength(instance) == columnIndex + 1) {
                            terminatedArrays.add(instance);
                        }
                    }
                    // 移除已经遍历完成的 mBuffer
                    branch.removeAll(terminatedArrays);

                    // 遍历 terminatedArrays，里面都是已经遍历完成的 mBuffer
                    // 由于已经按照像素分组了，所以如果 size > 1，就说明是有重复的 mBuffer
                    // 这个算法还挺复杂了，直接计算 mBuffer 的 md5/hash 也行啊
                    // Exact duplicated arrays found.
                    if (terminatedArrays.size() > 1) {
                        byte[] rawBuffer = null;
                        int width = 0;
                        int height = 0;
                        final List<Instance> duplicateBitmaps = new ArrayList<>();
                        for (ArrayInstance terminatedArray : terminatedArrays) {
                            final Instance bmpInstance = byteArrayToBitmapMap.get(terminatedArray);
                            duplicateBitmaps.add(bmpInstance);
                            if (rawBuffer == null) {
                                final List<FieldValue> fieldValues = ((ClassInstance) bmpInstance).getValues();
                                width = HahaHelper.fieldValue(fieldValues, "mWidth");
                                height = HahaHelper.fieldValue(fieldValues, "mHeight");
                                final int byteArraySize = HahaHelper.getArrayInstanceLength(terminatedArray);
                                rawBuffer = HahaHelper.asRawByteArray(terminatedArray, 0, byteArraySize);
                            }
                        }

                        // 找到引用链
                        final Map<Instance, Result> results = new ShortestPathFinder(mExcludedBmps)
                                .findPath(snapshot, duplicateBitmaps);
                        final List<ReferenceChain> referenceChains = new ArrayList<>();
                        for (Result result : results.values()) {
                            if (result.excludingKnown) {
                                continue;
                            }
                            // 这里是往上寻找，直到 gcRoots，用于获取一些信息，为啥不直接使用 buildReferenceChain
                            ReferenceNode currRefChainNode = result.referenceChainHead;
                            while (currRefChainNode.parent != null) {
                                final ReferenceNode tempNode = currRefChainNode.parent;
                                if (tempNode.instance == null) {
                                    currRefChainNode = tempNode;
                                    continue;
                                }
                                final Heap heap = tempNode.instance.getHeap();
                                if (heap != null && !"app".equals(heap.getName())) {
                                    break;
                                } else {
                                    currRefChainNode = tempNode;
                                }
                            }
                            final Instance gcRootHolder = currRefChainNode.instance;
                            if (!(gcRootHolder instanceof ClassObj)) {
                                continue;
                            }
                            final String holderClassName = ((ClassObj) gcRootHolder).getClassName();
                            boolean isExcluded = false;
                            // 排除满足 AndroidExcludedBmpRefs 中设定好的规则的引用
                            for (ExcludedBmps.PatternInfo patternInfo : mExcludedBmps.mClassNamePatterns) {
                                if (!patternInfo.mForGCRootOnly) {
                                    continue;
                                }
                                if (patternInfo.mPattern.matcher(holderClassName).matches()) {
                                    System.out.println(" + Skipped a bitmap with gc root class: "
                                            + holderClassName + " by pattern: " + patternInfo.mPattern.toString());
                                    isExcluded = true;
                                    break;
                                }
                            }
                            if (!isExcluded) {
                                // build 引用链
                                referenceChains.add(result.buildReferenceChain());
                            }
                        }
                        if (referenceChains.size() > 1) {
                            duplicatedBitmapEntries.add(new DuplicatedBitmapEntry(width, height, rawBuffer, referenceChains));
                        }
                    }

                    // If there are ArrayInstances that have identical prefixes and haven't hit the
                    // end, add it back for the next iteration.
                    if (branch.size() > 1) {
                        reducedPrefixSets.add(branch);
                    }
                }
            }

            commonPrefixSets.clear();
            commonPrefixSets.addAll(reducedPrefixSets);
            reducedPrefixSets.clear();
            columnIndex++;
        }

        return DuplicatedBitmapResult.duplicatedBitmapDetected(duplicatedBitmapEntries, AnalyzeUtil.since(analysisStartNanoTime));
    }

    private ArrayInstance cloneArrayInstance(ArrayInstance orig) {
        try {
            if (mMStackField == null) {
                mMStackField = Instance.class.getDeclaredField("mStack");
                mMStackField.setAccessible(true);
            }
            final StackTrace stack = (StackTrace) mMStackField.get(orig);

            if (mMLengthField == null) {
                mMLengthField = ArrayInstance.class.getDeclaredField("mLength");
                mMLengthField.setAccessible(true);
            }
            final int length = mMLengthField.getInt(orig);

            if (mMValueOffsetField == null) {
                mMValueOffsetField = ArrayInstance.class.getDeclaredField("mValuesOffset");
                mMValueOffsetField.setAccessible(true);
            }
            final long valueOffset = mMValueOffsetField.getLong(orig);

            final ArrayInstance result = new ArrayInstance(orig.getId(), stack, orig.getArrayType(), length, valueOffset);
            result.setHeap(orig.getHeap());
            return result;
        } catch (Throwable thr) {
            thr.printStackTrace();
            return null;
        }
    }
}
