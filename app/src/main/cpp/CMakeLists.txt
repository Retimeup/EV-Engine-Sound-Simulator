cmake_minimum_required(VERSION 3.22.1)
project("evenginesound")

find_package(oboe REQUIRED CONFIG)

add_library(engine SHARED engine.cpp)

target_link_libraries(engine oboe::oboe log)
