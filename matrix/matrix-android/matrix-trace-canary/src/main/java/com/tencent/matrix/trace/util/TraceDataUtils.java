package com.tencent.matrix.trace.util;

import android.util.Log;

import com.tencent.matrix.trace.constants.Constants;
import com.tencent.matrix.trace.core.AppMethodBeat;
import com.tencent.matrix.trace.items.MethodItem;
import com.tencent.matrix.util.MatrixLog;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

public class TraceDataUtils {

    private static final String TAG = "Matrix.TraceDataUtils";

    public interface IStructuredDataFilter {
        boolean isFilter(long during, int filterCount);

        int getFilterMaxCount();

        void fallback(List<MethodItem> stack, int size);
    }

    /**
     * METHOD_ID_DISPATCH 这个是入口函数，它会层层调用其他函数
     */
    public static void structuredDataToStack(long[] buffer, LinkedList<MethodItem> result, boolean isStrict, long endTime) {
        long lastInId = 0L;
        int depth = 0;
        LinkedList<Long> rawData = new LinkedList<>();
        boolean isBegin = !isStrict;

        // 遍历 buffer
        for (long trueId : buffer) {
            if (0 == trueId) {
                continue;
            }
            // 严格模式，需要找入口函数才能继续进行
            if (isStrict) {
                if (isIn(trueId) && AppMethodBeat.METHOD_ID_DISPATCH == getMethodId(trueId)) {
                    isBegin = true;
                }

                if (!isBegin) {
                    MatrixLog.d(TAG, "never begin! pass this method[%s]", getMethodId(trueId));
                    continue;
                }

            }
            // i 方法，则入栈
            if (isIn(trueId)) {
                lastInId = getMethodId(trueId);
                // 入口函数，深度为 0
                if (lastInId == AppMethodBeat.METHOD_ID_DISPATCH) {
                    depth = 0;
                }
                // 不是入口函数，深入累加
                depth++;
                rawData.push(trueId);
            }
            // o 方法，则出栈
            else {
                //
                int outMethodId = getMethodId(trueId);
                if (!rawData.isEmpty()) {
                    // 因为直接就出栈了，所以 o 前面肯定是与之对应的 i
                    long in = rawData.pop();
                    depth--;
                    int inMethodId;
                    LinkedList<Long> tmp = new LinkedList<>();
                    tmp.add(in);
                    //正常情况下是不会走到这个循环内部的
                    while ((inMethodId = getMethodId(in)) != outMethodId && !rawData.isEmpty()) {
                        MatrixLog.w(TAG, "pop inMethodId[%s] to continue match ouMethodId[%s]", inMethodId, outMethodId);
                        in = rawData.pop();
                        depth--;
                        tmp.add(in);
                    }

                    // 出现了异常，o 找不到匹配的 i
                    if (inMethodId != outMethodId && inMethodId == AppMethodBeat.METHOD_ID_DISPATCH) {
                        MatrixLog.e(TAG, "inMethodId[%s] != outMethodId[%s] throw this outMethodId!", inMethodId, outMethodId);
                        rawData.addAll(tmp);
                        depth += rawData.size();
                        continue;
                    }

                    long outTime = getTime(trueId);
                    long inTime = getTime(in);
                    // 计算时间
                    long during = outTime - inTime;
                    if (during < 0) {
                        MatrixLog.e(TAG, "[structuredDataToStack] trace during invalid:%d", during);
                        rawData.clear();
                        result.clear();
                        return;
                    }
                    // 构建一个 item 对象，里面有 id，耗时，深度
                    MethodItem methodItem = new MethodItem(outMethodId, (int) during, depth);
                    addMethodItem(result, methodItem);
                } else {
                    MatrixLog.w(TAG, "[structuredDataToStack] method[%s] not found in! ", outMethodId);
                }
            }
        }

        // ANR 情况下，可能有部分 o 方法没有来得及执行，直接使用 endTime 作为结束时间
        while (!rawData.isEmpty() && isStrict) {
            long trueId = rawData.pop();
            int methodId = getMethodId(trueId);
            boolean isIn = isIn(trueId);
            long inTime = getTime(trueId) + AppMethodBeat.getDiffTime();
            MatrixLog.w(TAG, "[structuredDataToStack] has never out method[%s], isIn:%s, inTime:%s, endTime:%s,rawData size:%s",
                    methodId, isIn, inTime, endTime, rawData.size());
            if (!isIn) {
                MatrixLog.e(TAG, "[structuredDataToStack] why has out Method[%s]? is wrong! ", methodId);
                continue;
            }
            MethodItem methodItem = new MethodItem(methodId, (int) (endTime - inTime), rawData.size());
            addMethodItem(result, methodItem);
        }

        // 这段操作没看懂啊，为啥要先转树，然后又转回来
        TreeNode root = new TreeNode(null, null);
        stackToTree(result, root);
        result.clear();
        treeToStack(root, result);
    }

    private static boolean isIn(long trueId) {
        return ((trueId >> 63) & 0x1) == 1;
    }

    private static long getTime(long trueId) {
        return trueId & 0x7FFFFFFFFFFL;
    }

    private static int getMethodId(long trueId) {
        return (int) ((trueId >> 43) & 0xFFFFFL);
    }

    /**
     * 一个方法被调用了多次，将方法信息合并
     * 需要该方法在同一层级
     */
    private static int addMethodItem(LinkedList<MethodItem> resultStack, MethodItem item) {
        if (AppMethodBeat.isDev) {
            Log.v(TAG, "method:" + item);
        }
        MethodItem last = null;
        if (!resultStack.isEmpty()) {
            last = resultStack.peek();
        }
        if (null != last && last.methodId == item.methodId && last.depth == item.depth && 0 != item.depth) {
            item.durTime = item.durTime == Constants.DEFAULT_ANR ? last.durTime : item.durTime;
            last.mergeMore(item.durTime);
            return last.durTime;
        } else {
            resultStack.push(item);
            return item.durTime;
        }
    }

    private static void rechange(TreeNode root) {
        if (root.children.isEmpty()) {
            return;
        }
        TreeNode[] nodes = new TreeNode[root.children.size()];
        root.children.toArray(nodes);
        root.children.clear();
        for (TreeNode node : nodes) {
            root.children.addFirst(node);
            rechange(node);
        }
    }

    // 又将树转链表了
    private static void treeToStack(TreeNode root, LinkedList<MethodItem> list) {

        for (int i = 0; i < root.children.size(); i++) {
            TreeNode node = root.children.get(i);
            list.add(node.item);
            if (!node.children.isEmpty()) {
                treeToStack(node, list);
            }
        }
    }


    /**
     * Structured the method stack as a tree Data structure
     *
     * 将链表转树
     */
    public static int stackToTree(LinkedList<MethodItem> resultStack, TreeNode root) {
        TreeNode lastNode = null;
        ListIterator<MethodItem> iterator = resultStack.listIterator(0);
        int count = 0;
        while (iterator.hasNext()) {
            TreeNode node = new TreeNode(iterator.next(), lastNode);
            count++;
            if (null == lastNode && node.depth() != 0) {
                MatrixLog.e(TAG, "[stackToTree] begin error! why the first node'depth is not 0!");
                return 0;
            }
            int depth = node.depth();
            if (lastNode == null || depth == 0) {
                root.add(node);
            }
            // 两个节点的深度相等的话
            else if (lastNode.depth() >= depth) {
                // 向上遍历找到 father
                while (null != lastNode && lastNode.depth() > depth) {
                    lastNode = lastNode.father;
                }
                // 将节点关系理顺
                // 该节点的 father 与同一深度最前面的节点的 father 是一样的
                if (lastNode != null && lastNode.father != null) {
                    node.father = lastNode.father;
                    lastNode.father.add(node);
                }
            } else {
                lastNode.add(node);
            }
            lastNode = node;
        }
        return count;
    }


    public static long stackToString(LinkedList<MethodItem> stack, StringBuilder reportBuilder, StringBuilder logcatBuilder) {
        logcatBuilder.append("|*\t\tTraceStack:").append("\n");
        logcatBuilder.append("|*\t\t\t[id count cost]").append("\n");
        Iterator<MethodItem> listIterator = stack.iterator();
        long stackCost = 0; // fix cost
        while (listIterator.hasNext()) {
            MethodItem item = listIterator.next();
            reportBuilder.append(item.toString()).append('\n');
            logcatBuilder.append("|*\t\t").append(item.print()).append('\n');

            if (stackCost < item.durTime) {
                stackCost = item.durTime;
            }
        }
        return stackCost;
    }


    public static int countTreeNode(TreeNode node) {
        int count = node.children.size();
        Iterator<TreeNode> iterator = node.children.iterator();
        while (iterator.hasNext()) {
            count += countTreeNode(iterator.next());
        }
        return count;
    }

    /**
     * it's the node for the stack tree
     */
    public static final class TreeNode {
        MethodItem item;
        TreeNode father;

        LinkedList<TreeNode> children = new LinkedList<>();

        TreeNode(MethodItem item, TreeNode father) {
            this.item = item;
            this.father = father;
        }

        private int depth() {
            return null == item ? 0 : item.depth;
        }

        private void add(TreeNode node) {
            children.addFirst(node);
        }

        private boolean isLeaf() {
            return children.isEmpty();
        }
    }

    public static void printTree(TreeNode root, StringBuilder print) {
        print.append("|*   TraceStack: ").append("\n");
        printTree(root, 0, print, "|*        ");
    }

    public static void printTree(TreeNode root, int depth, StringBuilder ss, String prefixStr) {

        StringBuilder empty = new StringBuilder(prefixStr);

        for (int i = 0; i <= depth; i++) {
            empty.append("    ");
        }
        for (int i = 0; i < root.children.size(); i++) {
            TreeNode node = root.children.get(i);
            ss.append(empty.toString()).append(node.item.methodId).append("[").append(node.item.durTime).append("]").append("\n");
            if (!node.children.isEmpty()) {
                printTree(node, depth + 1, ss, prefixStr);
            }
        }
    }

    // 将 stack 裁剪到 targetCount 大小
    public static void trimStack(List<MethodItem> stack, int targetCount, IStructuredDataFilter filter) {
        if (0 > targetCount) {
            stack.clear();
            return;
        }

        int filterCount = 1;
        int curStackSize = stack.size();
        while (curStackSize > targetCount) {
            // 从后往前遍历
            ListIterator<MethodItem> iterator = stack.listIterator(stack.size());
            while (iterator.hasPrevious()) {
                MethodItem item = iterator.previous();
                // 将满足过滤条件的删掉
                if (filter.isFilter(item.durTime, filterCount)) {
                    iterator.remove();
                    curStackSize--;
                    if (curStackSize <= targetCount) {
                        return;
                    }
                }
            }
            curStackSize = stack.size();
            filterCount++;
            // 外部会用到这个次数，可以根据遍历次数来做动态的调整，更改过滤条件，使之更宽
            if (filter.getFilterMaxCount() < filterCount) {
                break;
            }
        }
        int size = stack.size();
        if (size > targetCount) {
            // 过滤完成后还是大于指定的数字，则交给调用者自己处理
            filter.fallback(stack, size);
        }
    }

    @Deprecated
    public static String getTreeKey(List<MethodItem> stack, final int targetCount) {
        StringBuilder ss = new StringBuilder();
        final List<MethodItem> tmp = new LinkedList<>(stack);
        trimStack(tmp, targetCount, new TraceDataUtils.IStructuredDataFilter() {
            @Override
            public boolean isFilter(long during, int filterCount) {
                return during < filterCount * Constants.TIME_UPDATE_CYCLE_MS;
            }

            @Override
            public int getFilterMaxCount() {
                return Constants.FILTER_STACK_MAX_COUNT;
            }

            @Override
            public void fallback(List<MethodItem> stack, int size) {
                MatrixLog.w(TAG, "[getTreeKey] size:%s targetSize:%s", size, targetCount);
                Iterator iterator = stack.listIterator(Math.min(size, targetCount));
                while (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
        });
        for (MethodItem item : tmp) {
            ss.append(item.methodId + "|");
        }
        return ss.toString();
    }

    public static String getTreeKey(List<MethodItem> stack, long stackCost) {
        StringBuilder ss = new StringBuilder();
        long allLimit = (long) (stackCost * Constants.FILTER_STACK_KEY_ALL_PERCENT);

        LinkedList<MethodItem> sortList = new LinkedList<>();

        for (MethodItem item : stack) {
            if (item.durTime >= allLimit) {
                sortList.add(item);
            }
        }

        Collections.sort(sortList, new Comparator<MethodItem>() {
            @Override
            public int compare(MethodItem o1, MethodItem o2) {
                return Integer.compare((o2.depth + 1) * o2.durTime, (o1.depth + 1) * o1.durTime);
            }
        });

        if (sortList.isEmpty() && !stack.isEmpty()) {
            MethodItem root = stack.get(0);
            sortList.add(root);
        } else if (sortList.size() > 1 && sortList.peek().methodId == AppMethodBeat.METHOD_ID_DISPATCH) {
            sortList.removeFirst();
        }

        for (MethodItem item : sortList) {
            ss.append(item.methodId + "|");
            break;
        }
        return ss.toString();
    }


}
