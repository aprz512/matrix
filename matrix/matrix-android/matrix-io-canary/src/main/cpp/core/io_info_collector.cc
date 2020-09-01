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
// Author: liyongjie
// Created by liyongjie
//

#include "io_info_collector.h"
#include <thread>
#include "comm/io_canary_utils.h"

namespace iocanary {

    void IOInfoCollector::OnOpen(const char *pathname, int flags, mode_t mode, int open_ret,
                                 const JavaContext &java_context) {
        //__android_log_print(ANDROID_LOG_DEBUG, kTag, "OnOpen fd:%d; path:%s", open_ret, pathname);

        // 文件打开失败
        if (open_ret == -1) {
            return;
        }

        // 这里很奇怪啊，为啥要使用 返回值作为key？？？open_ret 是文件的描述符
        // 文件已经被记录了
        if (info_map_.find(open_ret) != info_map_.end()) {
            //__android_log_print(ANDROID_LOG_WARN, kTag, "OnOpen fd:%d already in info_map_", open_ret);
            return;
        }

        // make_shared 会在堆上创建对象，返回一个智能指针
        std::shared_ptr<IOInfo> info = std::make_shared<IOInfo>(pathname, java_context);
        // 记录
        info_map_.insert(std::make_pair(open_ret, info));
    }

    void IOInfoCollector::OnRead(int fd, const void *buf, size_t size,
                                 ssize_t read_ret, long read_cost) {

        if (read_ret == -1 || read_cost < 0) {
            return;
        }

        if (info_map_.find(fd) == info_map_.end()) {
            //__android_log_print(ANDROID_LOG_DEBUG, kTag, "OnRead fd:%d not in info_map_", fd);
            return;
        }

        CountRWInfo(fd, FileOpType::kRead, size, read_cost);
    }

    void IOInfoCollector::OnWrite(int fd, const void *buf, size_t size,
                                  ssize_t write_ret, long write_cost) {

        if (write_ret == -1 || write_cost < 0) {
            return;
        }

        if (info_map_.find(fd) == info_map_.end()) {
            //__android_log_print(ANDROID_LOG_DEBUG, kTag, "OnWrite fd:%d not in info_map_", fd);
            return;
        }

        CountRWInfo(fd, FileOpType::kWrite, size, write_cost);
    }

    /**
     * 返回 fd 对应的文件信息，在 info_map_ 中
     */
    std::shared_ptr<IOInfo> IOInfoCollector::OnClose(int fd, int close_ret) {

        if (info_map_.find(fd) == info_map_.end()) {
            //__android_log_print(ANDROID_LOG_DEBUG, kTag, "OnClose fd:%d not in info_map_", fd);
            return nullptr;
        }

        // 从打开到关闭的耗时
        info_map_[fd]->total_cost_μs_ = GetSysTimeMicros() - info_map_[fd]->start_time_μs_;
        // 文件大小
        info_map_[fd]->file_size_ = GetFileSize(info_map_[fd]->path_.c_str());
        // 其他信息在读写时记录了
        std::shared_ptr<IOInfo> info = info_map_[fd];
        info_map_.erase(fd);

        return info;
    }

    void
    IOInfoCollector::CountRWInfo(int fd, const FileOpType &fileOpType, long op_size, long rw_cost) {
        if (info_map_.find(fd) == info_map_.end()) {
            return;
        }

        const int64_t now = GetSysTimeMicros();

        info_map_[fd]->op_cnt_++;
        info_map_[fd]->op_size_ += op_size;
        info_map_[fd]->rw_cost_us_ += rw_cost;

        // 记录单次最大的读写时间
        if (rw_cost > info_map_[fd]->max_once_rw_cost_time_μs_) {
            info_map_[fd]->max_once_rw_cost_time_μs_ = rw_cost;
        }

        //__android_log_print(ANDROID_LOG_DEBUG, kTag, "CountRWInfo rw_cost:%d max_once_rw_cost_time_:%d current_continual_rw_time_:%d;max_continual_rw_cost_time_:%d; now:%lld;last:%lld",
        //      rw_cost, info_map_[fd]->max_once_rw_cost_time_μs_, info_map_[fd]->current_continual_rw_time_μs_, info_map_[fd]->max_continual_rw_cost_time_μs_, now, info_map_[fd]->last_rw_time_ms_);
        // 如果连续读写间隔小于   kContinualThreshold（8ms）
        if (info_map_[fd]->last_rw_time_μs_ > 0 &&
            (now - info_map_[fd]->last_rw_time_μs_) < kContinualThreshold) {
            // 本次连续读写时间
            info_map_[fd]->current_continual_rw_time_μs_ += rw_cost;
        } else {
            info_map_[fd]->current_continual_rw_time_μs_ = rw_cost;
        }
        if (info_map_[fd]->current_continual_rw_time_μs_ >
            info_map_[fd]->max_continual_rw_cost_time_μs_) {
            info_map_[fd]->max_continual_rw_cost_time_μs_ = info_map_[fd]->current_continual_rw_time_μs_;
        }
        // 记录读写时刻
        info_map_[fd]->last_rw_time_μs_ = now;

        // 记录操作的 buffer 大小，这里是记录的最大值
        if (info_map_[fd]->buffer_size_ < op_size) {
            info_map_[fd]->buffer_size_ = op_size;
        }

        // 如果对一个文件又读又写，记录第一次的读写类型？？？
        if (info_map_[fd]->op_type_ == FileOpType::kInit) {
            info_map_[fd]->op_type_ = fileOpType;
        }
    }
}
