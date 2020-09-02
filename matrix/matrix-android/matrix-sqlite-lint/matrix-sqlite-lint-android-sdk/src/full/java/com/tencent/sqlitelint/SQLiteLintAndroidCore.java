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

package com.tencent.sqlitelint;

import android.content.Context;

import com.tencent.matrix.util.MatrixUtil;
import com.tencent.sqlitelint.behaviour.BaseBehaviour;
import com.tencent.sqlitelint.behaviour.alert.IssueAlertBehaviour;
import com.tencent.sqlitelint.behaviour.persistence.IssueStorage;
import com.tencent.sqlitelint.behaviour.persistence.PersistenceBehaviour;
import com.tencent.sqlitelint.behaviour.persistence.SQLiteLintDbHelper;
import com.tencent.sqlitelint.behaviour.report.IssueReportBehaviour;
import com.tencent.sqlitelint.util.SQLite3ProfileHooker;
import com.tencent.sqlitelint.util.SQLiteLintUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The core logic of Android SDK for SQLiteLint
 * 1. Manage the logic stream: Collect executed sql -> Notify the native -> Get checked issue and behave
 * 2. Manage the white list //TODO confim put here?
 *
 * //TODO hide the SQLiteLintAndroidCore from SQLiteLint
 *
 * @author liyongjie
 *         Created by liyongjie on 2017/6/14.
 */
/*package*/class SQLiteLintAndroidCore implements IOnIssuePublishListener {
    private static final String TAG = "SQLiteLint.SQLiteLintAndroidCore";

    private final Context mContext;
    private final String mConcernedDbPath;
    private final ISQLiteExecutionDelegate mSQLiteExecutionDelegate;
    private final List<BaseBehaviour> mBehaviors;

    /**
     * New instance in {@link SQLiteLintAndroidCoreManager#install(Context, SQLiteLint.InstallEnv, SQLiteLint.Options)}
     * A installation in this constructor
     */
    SQLiteLintAndroidCore(Context context, SQLiteLint.InstallEnv installEnv, SQLiteLint.Options options) {
        mContext = context;
        SQLiteLintDbHelper.INSTANCE.initialize(context);
        mConcernedDbPath = installEnv.getConcernedDbPath();
        mSQLiteExecutionDelegate = installEnv.getSQLiteExecutionDelegate();

        // demo 里面使用的模式是 CUSTOM_NOTIFY，所以，这里就暂时不分析 hook 的流程
        // 有时间再返回来分析

        // HOOK模式下，SQLiteLint会自己去获取所有已执行的sql语句及其耗时(by hooking sqlite3_profile)

        // CUSTOM_NOTIFY模式下，则需要调用 {@link SQLiteLint#notifySqlExecution(String, String, int)}
        // 来通知SQLiteLint 需要分析的、已执行的sql语句及其耗时
        if (SQLiteLint.getSqlExecutionCallbackMode() == SQLiteLint.SqlExecutionCallbackMode.HOOK) {
            SQLite3ProfileHooker.hook();
        }

        SQLiteLintNativeBridge.nativeInstall(mConcernedDbPath);

        mBehaviors = new ArrayList<>();
        // 将 issue 进行储存，保存起来
        /*PersistenceBehaviour is a default pre-behaviour */
        mBehaviors.add(new PersistenceBehaviour());
        // 出现 issue 跳转新页面，展示 issue
        if (options.isAlertBehaviourEnable()) {
            mBehaviors.add(new IssueAlertBehaviour(context, mConcernedDbPath));
        }
        // 出现 issue 进行上报
        if (options.isReportBehaviourEnable()) {
            mBehaviors.add(new IssueReportBehaviour(SQLiteLint.sReportDelegate));
        }
    }

    public void addBehavior(BaseBehaviour behaviour) {
        if (!mBehaviors.contains(behaviour)) {
            mBehaviors.add(behaviour);
        }
    }

    public void removeBehavior(BaseBehaviour behaviour) {
        mBehaviors.remove(behaviour);
    }

    //TODO unhook
    public void release() {
        if (SQLiteLint.getSqlExecutionCallbackMode() == SQLiteLint.SqlExecutionCallbackMode.HOOK) {
            SQLite3ProfileHooker.unHook();
        }
        SQLiteLintNativeBridge.nativeUninstall(mConcernedDbPath);
    }

    public ISQLiteExecutionDelegate getSQLiteExecutionDelegate() {
        return mSQLiteExecutionDelegate;
    }

    /**
     * Exposed for {@link SQLiteLint#notifySqlExecution(String, String, int)}
     */
    public void notifySqlExecution(String dbPath, String sql, long timeCost) {
        String extInfoStack = "null";   //get stack only when cost > 8
        if (timeCost >= 8) {
            extInfoStack = SQLiteLintUtil.getThrowableStack(new Throwable());
        }
        SQLiteLintNativeBridge.nativeNotifySqlExecute(dbPath, sql, timeCost, extInfoStack);
    }

    public void setWhiteList(final int xmlResId) {
        CheckerWhiteListLogic.setWhiteList(mContext, mConcernedDbPath, xmlResId);
    }

    public void enableCheckers(List<String> enableCheckers) {
        String[] enableCheckerArr = new String[enableCheckers.size()];
        for (int i = 0; i < enableCheckers.size(); i++) {
            enableCheckerArr[i] = enableCheckers.get(i);
        }
        SQLiteLintNativeBridge.nativeEnableCheckers(mConcernedDbPath, enableCheckerArr);
    }

    @Override
    public void onPublish(List<SQLiteLintIssue> publishedIssues) {
        for (SQLiteLintIssue issue : publishedIssues){
            if (IssueStorage.hasIssue(issue.id)) {
                issue.isNew = false;
            }else {
                issue.isNew = true;
            }
        }
        for (int i = 0; i < mBehaviors.size(); i++) {
            mBehaviors.get(i).onPublish(publishedIssues);
        }
    }
}
