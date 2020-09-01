# cmake_minimum_required：指定运行此配置文件所需的 CMake 的最低版本
cmake_minimum_required(VERSION 3.4.1)

# 该命令表示项目的名称是 IOCanary
project(IOCanary)

#aux_source_directory(../matrix-android-commons/src/main/cpp/elf_hook ELF_HOOK)
#include_directories(../matrix-android-commons/src/main/cpp/elf_hook)

# add_subdirectory 指明本项目包含一个子目录 ，这样子目录下的 CMakeLists.txt 文件和源代码也会被处理
add_subdirectory(../matrix-android-commons/src/main/cpp/libxhook ${CMAKE_CURRENT_BINARY_DIR}/libxhook)


# aux_source_directory 该命令会查找指定目录下的所有源文件，然后将结果存进指定变量名。
# 它不会递归包含子目录，仅包含指定的目录
aux_source_directory(src/main/cpp/detector/ SRC_DIR_DETECTOR)
aux_source_directory(src/main/cpp/comm/ SRC_DIR_COMM)
aux_source_directory(src/main/cpp/core/ SRC_DIR_CORE)
aux_source_directory(${PROJECT_SOURCE_DIR}/../matrix-android-commons/src/main/cpp/md5 MD5_SRCS)

# include_directories 包含指定目录下的头文件，会递归目录
include_directories(${PROJECT_SOURCE_DIR}/../matrix-android-commons/src/main/cpp/md5)
include_directories(src/main/cpp)

add_library(
        # 生成的库的名字
        io-canary

        # 生成动态库
        SHARED

        # 下面的都是源文件
        ${ELF_HOOK}
        ${SRC_DIR_COMM}
        ${SRC_DIR_DETECTOR}
        ${SRC_DIR_CORE}
        ${MD5_SRCS}
        src/main/cpp/io_canary_jni.cc
)

# 查找库用的，是用来添加一些我们在编译我们的本地库的时候需要依赖的一些库
# 这个主要是查找系统库用的，如果项目里面有用到系统的.so库就是要把库名写到这个函数里面去找到相对应的为
# 由于cmake已经知道系统库的路径，所以我们这里只是指定使用log库，然后给log库起别名为log-lib便于我们后面引用，
# 此处的log库是我们后面调试时需要用来打log日志的库，是NDK为我们提供的。
# 找到 log-lib 库并链接到该动态库中
find_library(log-lib
        log)


# 在linux中c/c++的编译一般都是用gcc来编译的，c/c++编译时会产生.o文件，要通过make工具来把这些.o文件链接起来，
# 这样才能得一个可执行程序。所以.so在编译时要把所有库链接起来才能编译。
# target_link_libraries（）就是干这个事，
# target_link_libraries 是为了关联我们自己的库和一些第三方库或者系统库。
# 把要链接的库别名都写到这里就可以了，如果是系统的库要用这个格式${库的名字}。
target_link_libraries(io-canary

        # 起的别名
        ${log-lib}

        xhook)