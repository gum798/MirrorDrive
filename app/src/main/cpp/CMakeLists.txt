cmake_minimum_required(VERSION 3.22.1)
project(mirrordrive C CXX)

add_library(mirrordrive SHARED native_bridge.cpp)

find_library(log-lib log)
target_link_libraries(mirrordrive android ${log-lib})
