#!/bin/sh

cd external/rtmidi
cmake -DCMAKE_INSTALL_PREFIX=`pwd`/dist-shared -B build-shared
cmake --build build-shared && cmake --install build-shared

cmake -DBUILD_SHARED_LIBS=false -DCMAKE_INSTALL_PREFIX=`pwd`/dist-static -B build-static
cmake --build build-static && cmake --install build-static
