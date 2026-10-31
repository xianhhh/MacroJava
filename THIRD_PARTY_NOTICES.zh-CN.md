# Third-party notices

[English](THIRD_PARTY_NOTICES.md) | 简体中文

本文件列出 MacroJava 仓库直接使用或随仓库提供的第三方开源组件。各组件仍归原作者所有；本文件不改变其许可证，也不代表 MacroJava 自身的开源许可证。

| 组件 | 本项目中的用途 | 声明版本 | 许可证 | 原始许可证 |
| --- | --- | --- | --- | --- |
| [ASM](https://asm.ow2.io/) `org.ow2.asm:asm` | 字节码读写 | 9.11.1 | BSD-3-Clause | [ASM License](https://asm.ow2.io/license.html) |
| [ASM Tree](https://asm.ow2.io/) `org.ow2.asm:asm-tree` | 字节码树模型 | 9.11.1 | BSD-3-Clause | [ASM License](https://asm.ow2.io/license.html) |
| [ASM Analysis](https://asm.ow2.io/) `org.ow2.asm:asm-analysis` | 数据流与栈帧分析 | 9.11.1 | BSD-3-Clause | [ASM License](https://asm.ow2.io/license.html) |
| [Gradle](https://gradle.org/) API 和 Wrapper | 插件开发及仓库构建 | Wrapper 9.3.0；API 随构建环境 | Apache-2.0 | [Gradle License](https://github.com/gradle/gradle/blob/master/LICENSE) |
| [Gradle Plugin Publish Plugin](https://plugins.gradle.org/plugin/com.gradle.plugin-publish) | 发布构建工具，不随 MacroJava 插件 JAR 打包 | 2.0.0 | Apache-2.0 | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |

ASM 的表格版本来自 `build.gradle` 的直接声明。ASM 模块通过 Maven 依赖提供，不嵌入 MacroJava 插件 JAR。

## ASM BSD-3-Clause license

ASM: a very small and fast Java bytecode manipulation framework

Copyright (c) 2000-2011 INRIA, France Telecom

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holders nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
