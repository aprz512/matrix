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

//
// Created by liyongjie on 2017/12/7.
//

#include <android/log.h>
#include "repeat_read_detector.h"

namespace iocanary {

    RepeatReadInfo::RepeatReadInfo(const std::string &path, const std::string &java_stack,
                                   long java_thread_id, long op_size,
                                   int file_size) : path_(path), java_stack_(java_stack), java_thread_id_(java_thread_id) ,
                                                                   op_size_(op_size), file_size_(file_size), op_timems(GetTickCount()) {
        repeat_cnt_ = 1;
    }

    bool RepeatReadInfo::operator==(const RepeatReadInfo &target) const {
        return target.path_ == path_
            && target.java_thread_id_ == java_thread_id_
            && target.java_stack_ == java_stack_
            && target.file_size_ == file_size_
            && target.op_size_ == op_size_;
    }

    void RepeatReadInfo::IncRepeatReadCount() {
        repeat_cnt_ ++;
    }

    int RepeatReadInfo::GetRepeatReadCount() {
        return repeat_cnt_;
    }

    std::string RepeatReadInfo::GetStack() {
        return java_stack_;
    }

    /**
     * 检测是否有重复读取文件的情况
     * 测试的时候，在一个 for 里面读取文件，没有检测出来，看来是我哪里理解错了
     */
    void FileIORepeatReadDetector::Detect(const IOCanaryEnv &env,
                                          const IOInfo &file_io_info,
                                          std::vector<Issue>& issues) {

        const std::string& path = file_io_info.path_;
        // map 中没有读该文件的记录
        if (observing_map_.find(path) == observing_map_.end()) {
            // 一个过滤条件，连续最大的读写时间没有超过设定的值，忽略
            if (file_io_info.max_continual_rw_cost_time_μs_ < env.kPossibleNegativeThreshold) {
                return;
            }

            // 添加到 map
            observing_map_.insert(std::make_pair(path, std::vector<RepeatReadInfo>()));
        }

        // map
        // key : path
        // value : vector<RepeatReadInfo>
        std::vector<RepeatReadInfo>& repeat_infos = observing_map_[path];
        if (file_io_info.op_type_ == FileOpType::kWrite) {
            // 出现了一次写操作，将 vector 清空
            repeat_infos.clear();
            return;
        }

        RepeatReadInfo repeat_read_info(file_io_info.path_, file_io_info.java_context_.stack_, file_io_info.java_context_.thread_id_,
                                      file_io_info.op_size_, file_io_info.file_size_);

        // 第一次读，正常情况，记录一下，直接返回
        if (repeat_infos.size() == 0) {
            repeat_infos.push_back(repeat_read_info);
            return;
        }

        __android_log_print(ANDROID_LOG_INFO, "io-canary", "size = %d", repeat_infos.size());

        // 这里没太懂，因为文件本来就很大的话，读取肯定不止 17ms，所以集合一直会被清空
        // 除非是多线程同时读，但是这里又只监测了主线程的
        // 本地读该文件的时间 与 上一次读该文件的时间相差大于 17ms，就清空集合
        if((GetTickCount() - repeat_infos[repeat_infos.size() - 1].op_timems) > 17) {   //17ms todo astrozhou add to params
            repeat_infos.clear();
        }

        bool found = false;
        int repeatCnt;
        for (auto& info : repeat_infos) {
            if (info == repeat_read_info) {
                found = true;

                info.IncRepeatReadCount();

                repeatCnt = info.GetRepeatReadCount();
                break;
            }
        }

        if (!found) {
            repeat_infos.push_back(repeat_read_info);
            return;
        }

        __android_log_print(ANDROID_LOG_INFO, "io-canary", "repeatCnt = %d", repeatCnt);

        if (repeatCnt >= env.GetRepeatReadThreshold()) {
            Issue issue(kType, file_io_info);
            issue.repeat_read_cnt_ = repeatCnt;
            issue.stack = repeat_read_info.GetStack();
            PublishIssue(issue, issues);
        }
    }
}
