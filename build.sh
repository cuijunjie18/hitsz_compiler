#!/bin/bash

# 进入项目根目录（AircraftWar-base所在目录）
/home/cjj/Downloads/jdk/jdk-21/bin/javac \
-encoding UTF-8 \
-d out/compiler \
src/cn/edu/hitsz/compiler/asm/*.java \
src/cn/edu/hitsz/compiler/ir/*.java \
src/cn/edu/hitsz/compiler/lexer/*.java \
src/cn/edu/hitsz/compiler/parser/*.java \
src/cn/edu/hitsz/compiler/parser/table/*.java \
src/cn/edu/hitsz/compiler/symtab/*.java \
src/cn/edu/hitsz/compiler/utils/*.java \
src/cn/edu/hitsz/compiler/*.java