# LLVM/GCC 优化项与 MacroJava 对照

[English](OPTIMIZATION_PASSES.md) | 简体中文

本表以 JVM class 为输入和输出，不把 LLVM IR 或 GCC GIMPLE 的 Pass 名称等同于本项目实现。`部分`表示仅覆盖表中写明、可证明等价的字节码形态；`需 IR`表示理论上能做，但当前 ASM 指令流没有所需的 SSA、支配关系、别名或异常边分析。`不适用`表示应由 JVM/JIT 或机器后端处理，或改写会破坏 Java 语义。

## LLVM 列表

| Pass | 状态 | 本项目的对应能力或限制 |
|---|---|---|
| simplifycfg | 部分，本轮扩展 | 删除不可达指令、折叠常量分支和冗余跳转；新增 `goto` 链目标折叠。没有通用基本块合并或 PHI。 |
| sccp | 部分 | `ConstExprCallSiteAnalyzer.branchInputs` 与固定字段/方法传播能折叠部分跨分支常量；没有完整稀疏 SSA 求解器。 |
| dce | 部分，本轮扩展 | 删除不可达指令及未读的纯局部表达式；保留调用、数组访问、除法、类初始化等可能产生效果的操作。 |
| mem2reg | 部分，本轮扩展 | 单赋值常量局部传播；新增无分支方法中源槽不变的局部复制传播。没有 SSA 提升。 |
| sroa | 需 IR | 对可变数组/对象做成员拆分需要逃逸、别名和异常分析；现有固定下标数组折叠不是通用 SROA。 |
| reassociate | 部分，本轮新增 | 仅将连续 `int`/`long` 加法中的两个字面量合并，保持整数溢出语义；不重排浮点运算。 |
| sink | 需 IR | 下沉需要支配/后支配、异常顺序和副作用分析。 |
| loop-simplify | 部分 | 识别简单单回边循环、规范化整数条件、删除可证明零次的循环；未插入通用预头部或专用出口。 |
| loop-unroll | 需 IR | 多次展开需要 trip count、溢出和代码体积预算；当前仅消除可证明零次的循环。 |
| loop-unswitch | 需 IR | 需要循环不变量和别名分析，以及代码体积阈值。 |
| loop-rotate | 需 IR | JVM 栈帧、异常处理区和调试范围必须同步重建；现有模式不保证收益。 |
| mergefunc | 需 IR | 方法身份、反射、虚分派与栈轨迹可观察；仅可考虑严格限制的私有静态方法。 |
| mergereturn | 不适用 | 合并多个 JVM `return` 通常增加跳转；不改善本项目常量求值。 |
| partial-inliner | 部分 | 已有常量调用点折叠与私有方法链裁剪；未对动态调用做通用部分内联。 |
| loweratomic | 不适用 | 删除 Java volatile/原子语义会破坏内存模型。 |
| lowerinvoke | 不适用 | JVM 异常处理与 LLVM `invoke/unwind` 并非同一种降级目标。 |

## GCC -O1 列表

| 选项 | 状态 | 本项目的对应能力或限制 |
|---|---|---|
| `-fauto-inc-dec` | 不适用 | 寻址模式由 JVM/JIT 选择。 |
| `-fbranch-count-reg` | 不适用 | 机器寄存器与分支计数由 JIT 处理。 |
| `-fcombine-stack-adjustments` | 部分 | `compactLocalStack` 清理冗余 JVM 栈操作；不是机器栈指针优化。 |
| `-fcompare-elim` | 部分 | 常量比较及相关分支折叠。 |
| `-fcprop-registers` | 部分，本轮新增 | 无分支、单写入局部槽位的复制传播。 |
| `-fdce` | 部分，本轮扩展 | 不可达块、未读纯表达式和生成成员裁剪。 |
| `-fdefer-pop` | 不适用 | JVM 操作数栈必须满足验证规则。 |
| `-fdelayed-branch` | 不适用 | JVM 无延迟分支槽。 |
| `-fdse` | 部分，本轮扩展 | 未读局部存储；不删除可能逃逸的字段或数组存储。 |
| `-fforward-propagate` | 部分 | 字段、方法、不可变局部值及本轮局部复制传播。 |
| `-fguess-branch-probability` | 不适用 | 没有可靠的构建期运行画像；JIT 可用运行期画像。 |
| `-fif-conversion` / `-fif-conversion2` | 需 IR | 任一分支若含副作用或异常，急切求值会改变语义；还需评估 JVM 指令收益。 |
| `-finline-functions-called-once` | 部分 | 已有可求值调用点折叠；通用动态内联需要分派和异常分析。 |
| `-fipa-modref` / `-fipa-profile` / `-fipa-pure-const` / `-fipa-reference` / `-fipa-reference-addressable` | 部分 | 项目类预扫描、编译期解释与反射保留提供有限过程间信息；没有通用效果摘要或画像。 |
| `-fmerge-constants` | 已有 | ASM 新写出和最终常量池收缩移除无引用项并合并相同项。 |
| `-fmove-loop-invariants` | 需 IR | 循环不变量外提必须证明调用、异常、数组别名和类初始化顺序。 |
| `-fmove-loop-stores` | 需 IR | 可变字段/数组写入的别名及异常语义需要证明。 |
| `-fomit-frame-pointer` | 不适用 | JVM class 无可控制的机器帧指针。 |
| `-freorder-blocks` | 需 IR | 可做基本块布局，但应由 CFG 与运行期画像指导，并维护异常表/栈帧。 |
| `-fshrink-wrap` / `-fshrink-wrap-separate` | 不适用 | 保存/恢复机器寄存器由 JIT 负责。 |
| `-fsplit-wide-types` | 不适用 | JVM `long`/`double` 的宽度由规范固定。 |
| `-fssa-backprop` / `-fssa-phiopt` | 需 IR | 当前没有 SSA 或显式 PHI 节点。 |
| `-ftree-bit-ccp` / `-ftree-ccp` | 部分 | 常量位运算、局部常量和分支输入求值。 |
| `-ftree-ch` | 需 IR | 循环头复制依赖 CFG、trip count 和代码体积控制。 |
| `-ftree-coalesce-vars` | 部分 | 冗余局部载入/存储清理与复制传播；没有全局槽位着色。 |
| `-ftree-copy-prop` | 部分，本轮新增 | 仅无分支且源槽位不改写的复制传播。 |
| `-ftree-dce` | 部分，本轮扩展 | 不可达块与无副作用的死局部表达式。 |
| `-ftree-dominator-opts` | 需 IR | 没有可复用的支配树。 |
| `-ftree-dse` | 部分，本轮扩展 | 未读局部存储；不触碰字段/数组存储。 |
| `-ftree-forwprop` | 部分 | 常量、数组长度、局部复制的前向传播。 |
| `-ftree-fre` | 需 IR | 全局冗余消除需要内存效果、别名与异常路径分析。 |
| `-ftree-phiprop` | 部分 | 相同分支值可传播；没有显式 PHI。 |
| `-ftree-pta` | 需 IR | 当前只有有限对象/数组身份分析，没有通用指针分析。 |
| `-ftree-scev-cprop` | 部分 | 简单循环边界与零次循环判定；没有标量进化模型。 |
| `-ftree-sink` | 需 IR | 同 `sink`。 |
| `-ftree-slsr` | 需 IR | 循环强度削减需归纳变量、溢出和成本模型；JIT 通常也会处理。 |
| `-ftree-sra` | 需 IR | 同 `sroa`。 |
| `-ftree-ter` | 部分 | 局部常量替换与本轮复制传播。 |
| `-funit-at-a-time` | 已有 | `preScan` 读取当前源码集全部 class，再转换与裁剪。 |

## GCC -O2 / -O3 增量

| 选项 | 状态 | 本项目的对应能力或限制 |
|---|---|---|
| `-fgcse` | 需 IR | 跨块公共表达式消除需支配树和效果分析。 |
| `-fcrossjumping` | 需 IR | 等价尾块合并需 CFG、栈帧与异常区分析。 |
| `-foptimize-sibling-calls` | 不适用 | JVM 没有保证的尾调用指令；JIT 自行优化。 |
| `-fpeephole2` | 部分 | JVM 指令级局部折叠、冗余跳转与栈操作精简。 |
| `-finline-functions` | 部分 | 编译期可求值调用被替换；通用运行期方法体内联尚需专门 IR。 |
| `-funswitch-loops` | 需 IR | 同 `loop-unswitch`。 |

本轮直接实现了局部复制传播、整数加法常量重排、纯值死存储删除和跳转链折叠。它们只处理已有字节码分析能确认的形态，不改变调用、内存访问、异常或浮点运算的执行顺序。
