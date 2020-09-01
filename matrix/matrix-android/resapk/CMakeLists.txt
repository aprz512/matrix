# cmake_minimum_required：指定运行此配置文件所需的 CMake 的最低版本
cmake_minimum_required(VERSION 3.4.1)

# 该命令表示项目的名称是 IOCanary
project(resapk)


add_library(
        # 生成的库的名字
        threadid

        # 生成动态库
        SHARED

        # 下面的都是源文件
        src/main/cpp/threadid.cpp
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
target_link_libraries(threadid

        # 起的别名
        ${log-lib})