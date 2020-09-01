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

#include "io_canary.h"
#include <thread>
#include "detector/detector.h"
#include "detector/main_thread_detector.h"
#include "detector/repeat_read_detector.h"
#include "detector/small_buffer_detector.h"

namespace iocanary {

    IOCanary& IOCanary::Get() {
        // 一个全局对象，单例
        static IOCanary kInstance;
        return kInstance;
    }

    IOCanary::IOCanary() {
        exit_ = false;
        // 创建一个线程，detect 是被调用的函数，其参数是 this（隐式参数）
        std::thread detect_thread(&IOCanary::Detect, this);
        // 分离线程，线程单独运行
        detect_thread.detach();
    }

    void IOCanary::SetConfig(IOCanaryConfigKey key, long val) {
        env_.SetConfig(key, val);
    }


    void IOCanary::SetIssuedCallback(OnPublishIssueCallback issued_callback) {
        issued_callback_ = issued_callback;
    }

    void IOCanary::RegisterDetector(DetectorType type) {
        switch (type) {
            case DetectorType::kDetectorMainThreadIO:
                // 添加到detector容器集合
                detectors_.push_back(new FileIOMainThreadDetector());
                break;
            case DetectorType::kDetectorSmallBuffer:
                detectors_.push_back(new FileIOSmallBufferDetector());
                break;
            case DetectorType::kDetectorRepeatRead:
                detectors_.push_back(new FileIORepeatReadDetector());
                break;
            default:
                break;
        }
    }

    void IOCanary::OnOpen(const char *pathname, int flags, mode_t mode,
                          int open_ret, const JavaContext& java_context) {
        collector_.OnOpen(pathname, flags, mode, open_ret, java_context);
    }

    void IOCanary::OnRead(int fd, const void *buf, size_t size,
                          ssize_t read_ret, long read_cost) {
        collector_.OnRead(fd, buf, size, read_ret, read_cost);
    }

    void IOCanary::OnWrite(int fd, const void *buf, size_t size,
                           ssize_t write_ret, long write_cost) {
        collector_.OnWrite(fd, buf, size, write_ret, write_cost);
    }

    void IOCanary::OnClose(int fd, int close_ret) {
        std::shared_ptr<IOInfo> info = collector_.OnClose(fd, close_ret);
        if (info == nullptr) {
            return;
        }

        OfferFileIOInfo(info);
    }

    void IOCanary::OfferFileIOInfo(std::shared_ptr<IOInfo> file_io_info) {
        std::unique_lock<std::mutex> lock(queue_mutex_);
        queue_.push_back(file_io_info);
        // 通知等待线程，添加进去了一个元素
        queue_cv_.notify_one();
        lock.unlock();
    }

    /**
     * 从 queue_ 里面获取队头的 file_io_info 对象
     */
    int IOCanary::TakeFileIOInfo(std::shared_ptr<IOInfo> &file_io_info) {
        // std::unique_lock对象以独占所有权的方式(unique owership)管理mutex对象的上锁和解锁操作，
        // 即在unique_lock对象的声明周期内，它所管理的锁对象会一直保持上锁状态；
        // 而unique_lock的生命周期结束之后，它所管理的锁对象会被解锁。
        std::unique_lock<std::mutex> lock(queue_mutex_);

        // 如果队列为空，那就一直等待，开起来像是一个生产者-消费者模式
        while (queue_.empty()) {
            // wait 会释放锁
            queue_cv_.wait(lock);
            if (exit_) {
                return -1;
            }
        }

        // 因为参数是引用，所以这里会改变传递进来的实参值
        file_io_info = queue_.front();
        // pop 居然没有返回 pop 出来的值，难怪要多加一句
        queue_.pop_front();
        return 0;
    }

    void IOCanary::Detect() {
        std::vector<Issue> published_issues;
        // 只要将 new 运算符返回的指针 p 交给一个 shared_ptr 对象“托管”，
        // 就不必担心在哪里写delete p语句——实际上根本不需要编写这条语句，
        // 托管 p 的 shared_ptr 对象在消亡时会自动执行delete p。
        // 有点 java 的味道了
        std::shared_ptr<IOInfo> file_io_info;
        while (true) {
            published_issues.clear();

            int ret = TakeFileIOInfo(file_io_info);

            // exit_ 为0， 就跳出了
            if (ret != 0) {
                break;
            }

            // detectors_ 是监听集合
            // 具体可见 IOCanary::RegisterDetector 方法
            // 在对一个文件操作完毕之后，open -> read/write -> close，才会回调
            for (auto detector : detectors_) {
                detector->Detect(env_, *file_io_info, published_issues);
            }

            // 调用回调方法
            // 该监听是在 iocanary::JNI_OnLoad 里面设置的
            if (issued_callback_ && !published_issues.empty()) {
                issued_callback_(published_issues);
            }

            // 释放指针
            file_io_info = nullptr;
        }
    }

    IOCanary::~IOCanary() {
        std::unique_lock<std::mutex> lock(queue_mutex_);
        exit_ = true;
        lock.unlock();
        queue_cv_.notify_one();

        detectors_.clear();
    }
}
