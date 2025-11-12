cmake_minimum_required(VERSION 3.18.1)
project(wrapper)

add_executable(wrapper wrapper.c)

target_link_libraries(wrapper PRIVATE dl)

target_link_libraries(wrapper PRIVATE -static-libgcc -static-libstdc++)