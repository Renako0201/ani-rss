#!/bin/bash

# build_info Î»ÖÃ
build_info_path=./src/main/resources/build_info

rm -rf ${build_info_path}
git rev-parse --short HEAD >> ${build_info_path}
git branch --show-current >> ${build_info_path}