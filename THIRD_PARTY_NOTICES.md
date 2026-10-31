# Third-party notices

English | [简体中文](THIRD_PARTY_NOTICES.zh-CN.md)

This file lists third-party open-source components directly used by or distributed with MacroJava. The components remain the property of their authors. This notice does not change their licenses or MacroJava's own license.

| Component | Use in this project | Declared version | License | Original license |
| --- | --- | --- | --- | --- |
| [ASM](https://asm.ow2.io/) `org.ow2.asm:asm` | Bytecode reading and writing | 9.11.1 | BSD-3-Clause | [ASM License](https://asm.ow2.io/license.html) |
| [ASM Tree](https://asm.ow2.io/) `org.ow2.asm:asm-tree` | Bytecode tree model | 9.11.1 | BSD-3-Clause | [ASM License](https://asm.ow2.io/license.html) |
| [ASM Analysis](https://asm.ow2.io/) `org.ow2.asm:asm-analysis` | Data-flow and stack-frame analysis | 9.11.1 | BSD-3-Clause | [ASM License](https://asm.ow2.io/license.html) |
| [Gradle](https://gradle.org/) API and Wrapper | Plugin development and repository builds | Wrapper 9.3.0; API from the build environment | Apache-2.0 | [Gradle License](https://github.com/gradle/gradle/blob/master/LICENSE) |
| [Gradle Plugin Publish Plugin](https://plugins.gradle.org/plugin/com.gradle.plugin-publish) | Publishing tool; not packaged in the MacroJava plugin JAR | 2.0.0 | Apache-2.0 | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |

The ASM versions above come from direct declarations in `build.gradle`. ASM modules are supplied as Maven dependencies and are not embedded in the MacroJava plugin JAR.

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
