/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.matrix.resource.analyzer.utils;

import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Field;
import com.squareup.haha.perflib.HahaSpy;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.RootObj;
import com.squareup.haha.perflib.RootType;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.Type;
import com.tencent.matrix.resource.analyzer.model.ExcludedRefs;
import com.tencent.matrix.resource.analyzer.model.Exclusion;
import com.tencent.matrix.resource.analyzer.model.ReferenceChain;
import com.tencent.matrix.resource.analyzer.model.ReferenceNode;
import com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static com.squareup.haha.perflib.HahaHelper.extendsThread;
import static com.squareup.haha.perflib.HahaHelper.fieldToString;
import static com.squareup.haha.perflib.HahaHelper.isPrimitiveOrWrapperArray;
import static com.squareup.haha.perflib.HahaHelper.isPrimitiveWrapper;
import static com.squareup.haha.perflib.HahaHelper.threadName;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Holder.ARRAY;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Holder.CLASS;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Holder.OBJECT;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Holder.THREAD;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Type.ARRAY_ENTRY;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Type.INSTANCE_FIELD;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Type.LOCAL;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Type.STATIC_FIELD;

/**
 * This class is ported from LeakCanary.
 * <p>
 * Not thread safe.
 * <p>
 * Finds the shortest path from a reference to a gc root, ignoring excluded
 * refs first and then including the ones that are not "always ignorable" as needed if no path is
 * found.
 *
 * 这里的 shortest 是啥意思啊？
 * gc root -> a -> b -> c -> d
 * gc root -> a -> b -> a-> b -> c -> d  是这样的意思吗？
 */
public final class ShortestPathFinder {
    private static final String ANONYMOUS_CLASS_NAME_PATTERN = "^.+\\$\\d+$";

    private final ExcludedRefs excludedRefs;
    private final Queue<ReferenceNode> toVisitQueue;
    private final Queue<ReferenceNode> toVisitIfNoPathQueue;
    private final Set<Instance> toVisitSet;
    private final Set<Instance> toVisitIfNoPathSet;
    private final Set<Instance> visitedSet;
    private boolean canIgnoreStrings;

    public ShortestPathFinder(ExcludedRefs excludedRefs) {
        this.excludedRefs = excludedRefs;
        toVisitQueue = new LinkedList<>();
        toVisitIfNoPathQueue = new LinkedList<>();
        toVisitSet = new HashSet<>();
        toVisitIfNoPathSet = new HashSet<>();
        visitedSet = new HashSet<>();
    }

    public static final class Result {
        public final ReferenceNode referenceChainHead;
        public final boolean excludingKnown;

        Result(ReferenceNode referenceChainHead, boolean excludingKnown) {
            this.referenceChainHead = referenceChainHead;
            this.excludingKnown = excludingKnown;
        }

        public ReferenceChain buildReferenceChain() {
            List<ReferenceTraceElement> elements = new ArrayList<>();
            // We iterate from the leak to the GC root
            ReferenceNode node = new ReferenceNode(null,
                    null, referenceChainHead, null, null);
            // 不断的从 泄漏的对象 向上遍历，直到 gcRoots
            while (node != null) {
                ReferenceTraceElement element = buildReferenceTraceElement(node);
                if (element != null) {
                    elements.add(0, element);
                }
                node = node.parent;
            }
            // 就生成了一个引用链
            return new ReferenceChain(elements);
        }

        private ReferenceTraceElement buildReferenceTraceElement(ReferenceNode node) {
            if (node.parent == null) {
                // Ignore any root node.
                return null;
            }

            // 获取其 parent
            Instance holder = node.parent.instance;

            if (holder instanceof RootObj) {
                return null;
            }
            ReferenceTraceElement.Type type = node.referenceType;
            String referenceName = node.referenceName;

            ReferenceTraceElement.Holder holderType;
            String className;
            String extra = null;
            List<String> fields = describeFields(holder);

            className = getClassName(holder);

            // 获取 holder 的一些信息
            if (holder instanceof ClassObj) {
                holderType = CLASS;
            } else if (holder instanceof ArrayInstance) {
                holderType = ARRAY;
            } else {
                ClassObj classObj = holder.getClassObj();
                if (extendsThread(classObj)) {
                    holderType = THREAD;
                    String threadName = threadName(holder);
                    extra = "(named '" + threadName + "')";
                } else if (className.matches(ANONYMOUS_CLASS_NAME_PATTERN)) {
                    String parentClassName = classObj.getSuperClassObj().getClassName();
                    if (Object.class.getName().equals(parentClassName)) {
                        holderType = OBJECT;
                        try {
                            // This is an anonymous class implementing an interface. The API does not give access
                            // to the interfaces implemented by the class. We check if it's in the class path and
                            // use that instead.
                            Class<?> actualClass = Class.forName(classObj.getClassName());
                            Class<?>[] interfaces = actualClass.getInterfaces();
                            if (interfaces.length > 0) {
                                Class<?> implementedInterface = interfaces[0];
                                extra = "(anonymous implementation of " + implementedInterface.getName() + ")";
                            } else {
                                extra = "(anonymous subclass of java.lang.Object)";
                            }
                        } catch (ClassNotFoundException ignored) {
                            // Ignored.
                        }
                    } else {
                        holderType = OBJECT;
                        // Makes it easier to figure out which anonymous class we're looking at.
                        extra = "(anonymous subclass of " + parentClassName + ")";
                    }
                } else {
                    holderType = OBJECT;
                }
            }
            return new ReferenceTraceElement(referenceName, type, holderType,
                    className, extra, node.exclusion, fields);
        }

        private List<String> describeFields(Instance instance) {
            List<String> fields = new ArrayList<>();

            if (instance instanceof ClassObj) {
                ClassObj classObj = (ClassObj) instance;
                for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
                    Field field = entry.getKey();
                    Object value = entry.getValue();
                    fields.add("static " + field.getName() + " = " + value);
                }
            } else if (instance instanceof ArrayInstance) {
                ArrayInstance arrayInstance = (ArrayInstance) instance;
                if (arrayInstance.getArrayType() == Type.OBJECT) {
                    Object[] values = arrayInstance.getValues();
                    for (int i = 0; i < values.length; i++) {
                        fields.add("[" + i + "] = " + values[i]);
                    }
                }
            } else {
                ClassObj classObj = instance.getClassObj();
                for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
                    fields.add("static " + fieldToString(entry));
                }
                ClassInstance classInstance = (ClassInstance) instance;
                for (ClassInstance.FieldValue field : classInstance.getValues()) {
                    fields.add(fieldToString(field));
                }
            }
            return fields;
        }

        private String getClassName(Instance instance) {
            String className;
            if (instance instanceof ClassObj) {
                ClassObj classObj = (ClassObj) instance;
                className = classObj.getClassName();
            } else if (instance instanceof ArrayInstance) {
                ArrayInstance arrayInstance = (ArrayInstance) instance;
                className = arrayInstance.getClassObj().getClassName();
            } else {
                ClassObj classObj = instance.getClassObj();
                className = classObj.getClassName();
            }
            return className;
        }
    }

    public Result findPath(Snapshot snapshot, Instance targetReference) {
        final List<Instance> targetRefList = new ArrayList<>();
        targetRefList.add(targetReference);
        final Map<Instance, Result> results = findPath(snapshot, targetRefList);
        if (results == null || results.isEmpty()) {
            return new Result(null, false);
        } else {
            return results.get(targetReference);
        }
    }

    public Map<Instance, Result> findPath(Snapshot snapshot, Collection<Instance> targetReferences) {
        final Map<Instance, Result> results = new HashMap<>();

        if (targetReferences.isEmpty()) {
            return results;
        }

        clearState();
        // 将 gcRoots 加入集合中，分为了两块，JAVA_LOCAL 与 其他
        enqueueGcRoots(snapshot);

        // 默认是忽略 String 的，除非要找的泄漏对象是 String 类型的
        canIgnoreStrings = true;
        for (Instance targetReference : targetReferences) {
            if (isString(targetReference)) {
                canIgnoreStrings = false;
                break;
            }
        }

        final Set<Instance> targetRefSet = new HashSet<>(targetReferences);

        while (!toVisitQueue.isEmpty() || !toVisitIfNoPathQueue.isEmpty()) {
            ReferenceNode node;
            // 从集合中取出一个，先 toVisitQueue，后 toVisitIfNoPathQueue
            if (!toVisitQueue.isEmpty()) {
                node = toVisitQueue.poll();
            } else {
                node = toVisitIfNoPathQueue.poll();
                if (node.exclusion == null) {
                    throw new IllegalStateException("Expected node to have an exclusion " + node);
                }
            }

            // 找到了，跳出循环
            // Termination
            if (targetRefSet.contains(node.instance)) {
                results.put(node.instance, new Result(node, node.exclusion != null));
                targetRefSet.remove(node.instance);
                if (targetRefSet.isEmpty()) {
                    break;
                }
            }

            // 该节点被 visit 过了，跳过，像图的广度遍历
            if (checkSeen(node)) {
                continue;
            }

            if (node.instance instanceof RootObj) {
                visitRootObj(node);
            } else if (node.instance instanceof ClassObj) {
                visitClassObj(node);
            } else if (node.instance instanceof ClassInstance) {
                visitClassInstance(node);
            } else if (node.instance instanceof ArrayInstance) {
                visitArrayInstance(node);
            } else {
                throw new IllegalStateException("Unexpected type for " + node.instance);
            }
        }
        return results;
    }

    private void clearState() {
        toVisitQueue.clear();
        toVisitIfNoPathQueue.clear();
        toVisitSet.clear();
        toVisitIfNoPathSet.clear();
        visitedSet.clear();
    }

    private void enqueueGcRoots(Snapshot snapshot) {
        for (RootObj rootObj : snapshot.getGCRoots()) {
            switch (rootObj.getRootType()) {
                case JAVA_LOCAL:
                    // Java棧幀中的局部變量
                    Instance thread = HahaSpy.allocatingThread(rootObj);
                    // 拿到线程名字
                    String threadName = threadName(thread);
                    // 如果线程在排除范围内，那么就不考虑
                    // 比如 main 线程，主线程堆栈一直在变化，所以局部变量不太可能长时间保存引用。
                    // 如果是真的泄漏，一定会有另外一条路径
                    Exclusion params = excludedRefs.threadNames.get(threadName);
                    if (params == null || !params.alwaysExclude) {
                        enqueue(params, null, rootObj, null, null);
                    }
                    break;
                case INTERNED_STRING:
                case DEBUGGER:
                case INVALID_TYPE:
                    // An object that is unreachable from any other root, but not a root itself.
                case UNREACHABLE:
                case UNKNOWN:
                    // An object that is in a queue, waiting for a finalizer to run.
                case FINALIZING:
                    break;
                case SYSTEM_CLASS:
                case VM_INTERNAL:
                    // A local variable in native code.
                case NATIVE_LOCAL:
                    // A global variable in native code.
                case NATIVE_STATIC:
                    // An object that was referenced from an active thread block.
                case THREAD_BLOCK:
                    // Everything that called the wait() or notify() methods, or that is synchronized.
                case BUSY_MONITOR:
                case NATIVE_MONITOR:
                case REFERENCE_CLEANUP:
                    // Input or output parameters in native code.
                case NATIVE_STACK:
                case JAVA_STATIC:
                    // 其他情况，直接入队列
                    enqueue(null, null, rootObj, null, null);
                    break;
                default:
                    throw new UnsupportedOperationException("Unknown root type:" + rootObj.getRootType());
            }
        }
    }

    private boolean checkSeen(ReferenceNode node) {
        return !visitedSet.add(node.instance);
    }

    /**
     * 如果是  root，那么将它引用的对象入队
     */
    private void visitRootObj(ReferenceNode node) {
        RootObj rootObj = (RootObj) node.instance;
        Instance child = rootObj.getReferredInstance();

        if (rootObj.getRootType() == RootType.JAVA_LOCAL) {
            Instance holder = HahaSpy.allocatingThread(rootObj);
            // We switch the parent node with the thread instance that holds
            // the local reference.
            Exclusion exclusion = null;
            if (node.exclusion != null) {
                exclusion = node.exclusion;
            }
            // 将父节点替换为Thread(GCRoot)，
            ReferenceNode parent = new ReferenceNode(null, holder, null, null, null);
            enqueue(exclusion, parent, child, "<Java Local>", LOCAL);
        } else {
            enqueue(null, node, child, null, null);
        }
    }

    /**
     * 如果是Class对象，那么需要将它的静态字段入队
     */
    private void visitClassObj(ReferenceNode node) {
        ClassObj classObj = (ClassObj) node.instance;
        Map<String, Exclusion> ignoredStaticFields =
                excludedRefs.staticFieldNameByClassName.get(classObj.getClassName());
        // 遍历静态字段
        for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
            Field field = entry.getKey();
            // 不是 object（ref），就忽略
            if (field.getType() != Type.OBJECT) {
                continue;
            }
            /*
            一个Instance的field大致有这些：
            $staticOverhead 不知道是啥，猜测是静态类的大小？？？

            参考：https://android.googlesource.com/platform/dalvik.git/+/android-4.2.2_r1/vm/hprof/HprofHeap.cpp
            The static field-name for the synthetic object generated to account for class Static overhead.
            #define STATIC_OVERHEAD_NAME    "$staticOverhead"

            04-25 10:20:46.793 D/LeakCanary: * Class com.xiao.memoryleakexample.app.App
            04-25 10:20:46.793 D/LeakCanary: |   static $staticOverhead = byte[24]@314667009 (0x12c17001)
            04-25 10:20:46.793 D/LeakCanary: |   static sActivities = java.util.ArrayList@315492800 (0x12ce09c0)
            04-25 10:20:46.793 D/LeakCanary: |   static serialVersionUID = -920324649544707127
            04-25 10:20:46.793 D/LeakCanary: |   static $change = null
             */
            String fieldName = field.getName();
            if ("$staticOverhead".equals(fieldName)) {
                continue;
            }
            Instance child = (Instance) entry.getValue();
            boolean visit = true;
            // 排除某些静态字段
            if (ignoredStaticFields != null) {
                Exclusion params = ignoredStaticFields.get(fieldName);
                if (params != null) {
                    visit = false;
                    // 看了下AndroidExcludedRefs，现在的静态字段里面都没有设置alwaysExclude
                    if (!params.alwaysExclude) {
                        enqueue(params, node, child, fieldName, STATIC_FIELD);
                    }
                }
            }
            if (visit) {
                enqueue(null, node, child, fieldName, STATIC_FIELD);
            }
        }
    }

    /**
     * 如果是普通对象，遍历该对象字段以及父类字段，然后入队
     */
    private void visitClassInstance(ReferenceNode node) {
        ClassInstance classInstance = (ClassInstance) node.instance;
        Map<String, Exclusion> ignoredFields = new LinkedHashMap<>();
        ClassObj superClassObj = classInstance.getClassObj();
        Exclusion classExclusion = null;
        // 遍历所有父类的字段，收集所有需要排除的字段
        while (superClassObj != null) {
            Exclusion params = excludedRefs.classNames.get(superClassObj.getClassName());
            if (params != null && (classExclusion == null || !classExclusion.alwaysExclude)) {
                // true overrides null or false.
                classExclusion = params;
            }
            Map<String, Exclusion> classIgnoredFields =
                    excludedRefs.fieldNameByClassName.get(superClassObj.getClassName());
            if (classIgnoredFields != null) {
                ignoredFields.putAll(classIgnoredFields);
            }
            superClassObj = superClassObj.getSuperClassObj();
        }

        if (classExclusion != null && classExclusion.alwaysExclude) {
            return;
        }

        for (ClassInstance.FieldValue fieldValue : classInstance.getValues()) {
            Exclusion fieldExclusion = classExclusion;
            Field field = fieldValue.getField();
            if (field.getType() != Type.OBJECT) {
                continue;
            }
            Instance child = (Instance) fieldValue.getValue();
            String fieldName = field.getName();
            Exclusion params = ignoredFields.get(fieldName);
            // class 上没有设置 alwaysExclude，但是 field 上设置了，就进行覆盖
            // If we found a field exclusion and it's stronger than a class exclusion
            if (params != null && (fieldExclusion == null || (params.alwaysExclude
                    && !fieldExclusion.alwaysExclude))) {
                fieldExclusion = params;
            }
            enqueue(fieldExclusion, node, child, fieldName, INSTANCE_FIELD);
        }
    }

    /**
     * 如果是对象数组，就将元素入队
     */
    private void visitArrayInstance(ReferenceNode node) {
        ArrayInstance arrayInstance = (ArrayInstance) node.instance;
        Type arrayType = arrayInstance.getArrayType();
        if (arrayType == Type.OBJECT) {
            Object[] values = arrayInstance.getValues();
            for (int i = 0; i < values.length; i++) {
                Instance child = (Instance) values[i];
                enqueue(null, node, child, "[" + i + "]", ARRAY_ENTRY);
            }
        }
    }

    private void enqueue(Exclusion exclusion, ReferenceNode parent, Instance child, String referenceName,
                         ReferenceTraceElement.Type referenceType) {
        if (child == null) {
            return;
        }
        // 跳过 原始类型，及其包装类，以及他们的数组，因为他们不引用对象
        if (isPrimitiveOrWrapperArray(child) || isPrimitiveWrapper(child)) {
            return;
        }
        // 已经在待访问集合中，跳过
        // Whether we want to visit now or later, we should skip if this is already to visit.
        if (toVisitSet.contains(child)) {
            return;
        }
        // enqueueGcRoots的逻辑中，只有某些线程（main等）为 gcRoots 的时候，exclusion 才不为 null
        boolean visitNow = exclusion == null;
        if (!visitNow && toVisitIfNoPathSet.contains(child)) {
            return;
        }
        // 设置了忽略 string，如果是 string 就跳过
        if (canIgnoreStrings && isString(child)) {
            return;
        }
        // 已经访问过了
        if (visitedSet.contains(child)) {
            return;
        }
        ReferenceNode childNode = new ReferenceNode(exclusion, child, parent, referenceName, referenceType);
        // 这里为毛要搞这么多集合啊
        // 将有 exclusion 与没有 exclusion 的分开，我可以理解，为啥搞了 queue，还要搞个 set
        // 是因为 contains 判断会快些吗？
        // 正常的 gcRoots
        if (visitNow) {
            toVisitSet.add(child);
            toVisitQueue.add(childNode);
        } else {
            // 有可能需要排除的 gcRoots
            toVisitIfNoPathSet.add(child);
            toVisitIfNoPathQueue.add(childNode);
        }
    }

    private static boolean isString(Instance instance) {
        return instance.getClassObj() != null && instance.getClassObj()
                .getClassName()
                .equals(String.class.getName());
    }
}
