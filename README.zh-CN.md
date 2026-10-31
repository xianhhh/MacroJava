# MacroJava

[English](README.md) | 简体中文

`MacroJava` 是编译后处理 Java 字节码的 Gradle 插件，提供 `@ConstExpr` 常量内联及 `GOTO`、`JUMP` 跳转。构建和运行插件需要 JDK 17；转换器保留输入 class 文件的版本，可处理 Java 8 及以上的字节码。

## 安装与使用

需要 JDK 17。新插件 ID 发布后，Gradle 会从 Gradle Plugin Portal 自动下载插件及其依赖，无需手动下载 JAR。在项目的 `settings.gradle` 中配置插件仓库：

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

在 `build.gradle` 中应用插件：

```groovy
plugins {
    id 'java'
    id 'io.github.xianhhh.macrojava' version '2.0.0'
}
```

插件会自动将注解加入 `compileOnly` 和 `annotationProcessor`，无需再声明 `macrojava` 依赖。运行 `./gradlew classes` 或 `./gradlew testClasses` 即可触发相应源码集的编译与转换；Windows 使用 `gradlew.bat`。转换后的 class 由插件接入后续打包和运行任务。

例如，在项目源码中使用注解：

```java
import xh.xianhhh.macro.ConstExpr;

public final class Example {
    @ConstExpr("8 * 8")
    public static int size;

    public static void main(String[] args) {
        System.out.println(size);
    }
}
```

项目自身的第三方库照常写在 `dependencies` 中，编译期求值时会读取这些库的字节码：

```groovy
repositories { mavenCentral() }

dependencies {
    implementation 'org.example:some-library:1.0.0'
}
```

只引用插件 JAR 作为普通依赖不会执行 MacroJava 转换；需要编译期优化时应使用上面的 Gradle 插件配置。

### 从源码使用

也可以从 [GitHub 仓库](https://github.com/xianhhh/MacroJava) 下载源码，或使用 Git：

```bash
git clone https://github.com/xianhhh/MacroJava.git
```

在使用插件的项目中，将仓库路径加入 `settings.gradle` 的 `pluginManagement`，其余 `build.gradle` 配置与上面相同：

```groovy
pluginManagement {
    includeBuild('../MacroJava') // 改成实际的源码目录
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

仓库附带 Gradle 9.3.0 Wrapper。验证插件源码可在仓库根目录运行 `./gradlew check`（Windows 为 `gradlew.bat check`）；`cd instance` 后运行同样的 `check` 可验证独立示例项目。

### 更多注解示例

```java
import xh.xianhhh.macro.ConstExpr;

final class BuildValues {
    @ConstExpr("8_000 + 80")
    static int port;

    @ConstExpr(build = "System.getProperty(\"production\", \"false\").equals(\"true\")")
    static boolean production;

    @ConstExpr(values = {"3", "5", "8"})
    static int[] values;

    @ConstExpr("42")
    static int answer() { return 0; }

    @ConstExpr(classValue = String.class)
    static Class<?> stringType;

    @ConstExpr(value = "RUNNABLE", enumType = Thread.State.class)
    static Thread.State running;

    @ConstExpr(nullValue = true)
    static Object missing;
}
```

详细资料：[优化项对照](OPTIMIZATION_PASSES.zh-CN.md) · [示例项目](instance/src/main/java/xh/xianhhh/Main.java) · [项目许可证](LICENSE) · [第三方开源声明](THIRD_PARTY_NOTICES.zh-CN.md) · [CI 构建](.github/workflows/ci.yml)。

## 常量传播

转换器会把标量字段读取和带注解的静态方法调用替换为对应类型的 JVM 常量。参数化方法只有在所有实参都能在编译期确定时才会求值。编译期数组的固定下标读取和 `length` 也可以传播。方法体可以创建对象、调用构造器和方法，以及读写实例字段，前提是项目输出或编译类路径中提供了所需的字节码。依赖类会从目录或 JAR 中按需读取，并由字节码解释器执行，不通过反射加载。

例如，带参数的方法可以读取注解数组。实参为编译期值的调用点会被求值：

```java
final class LookupTable {
    @ConstExpr(values = {"11", "29", "47"})
    static long[] values;

    @ConstExpr
    static long scaled(int index, long factor) {
        return values[index] * factor;
    }
}

long result = LookupTable.scaled(1, 3L); // 替换为 87L
int count = LookupTable.values.length;   // 替换为 3
long first = LookupTable.values[0];      // 替换为 11L
```

方法实参也可以是常量表达式，或在所有可达分支上都能确定为同一个常量的局部变量。依赖运行期输入的调用会留在运行期。数组方法会根据求值结果推断返回数组各维的长度，包括不规则数组；只有带注解的多维字段必须指定 `shape`。数组下标可在编译期确定时会折叠读取。默认情况下，返回数组仍会在运行期构造，以保留 Java 数组可修改且每次调用返回新数组的语义。

对象结果在能够重建时也可以折叠，包括具有可用构造器字节码的项目类、可序列化的 Java 对象和集合、`Optional`、没有共享引用的对象数组，以及已知接收者的 `getClass()` 结果。可序列化对象的计算结果会嵌入 class，并在每次调用时反序列化；较大的快照会拆分到多个字符串常量。这样可以避免重复运行生成逻辑，但反序列化小对象可能比直接构造更慢。构造器建立且未改变的自引用会由原构造器重建。由公开、空构造器且字段可写的简单对象组成的循环引用或共享引用图，会先创建各节点再连接字段；无法安全重建的对象图保留原调用。

大型引用数组（包括不规则多维数组）由大小受限的辅助方法填充；稀疏位置保持 `null`。若嵌入结果需要过多对象工厂或常量元素，原方法调用会保留到运行期。

在 `SMART` 模式下，使用 `StandardCharsets` 常量编码的字符串字面量，例如 `"data".getBytes(StandardCharsets.UTF_8)`，会在构建时编码。在 `TAG` 模式下，这项优化也适用于选中的方法体。动态字符串或字符集仍在运行期处理。字节内容嵌入 class；较大的数组使用现有模板解码器，默认工厂每次返回新数组。`shareTemplateArray = true` 可以选择共享模板。

直线代码块中，连续赋给多个局部变量的相同编译期数组只构造一次。后续变量加载前一个数组并复制；多维数组会逐层复制。如果第一个变量在重复构造之前已被读取、重新赋值，或跨过分支，则跳过复用。这会减少重复字节码，同时保留可变数组语义。启用共享模板时，基本类型数组复用同一引用；嵌套数组保留独立的外层数组，并共享内部基本类型数组模板。

## 控制流与裁剪

无条件跳转使用普通 Java 方法调用，不需要增加关键字。`GOTO` 是严格模式，会检查目标标签、操作数栈和跳转后的控制流；`JUMP` 是直接模式，写入 JVM `GOTO`，跳过控制流安全检查，并允许跨过 `finally`：

```java
import xh.xianhhh.macro.GOTO;
import xh.xianhhh.macro.JUMP;

int count = 0;
GOTO.label("again");
count++;
if (count < 3) GOTO.goTo("again");

JUMP.goTo("done");
JUMP.label("done");
```

标签和跳转必须位于同一方法、同一模式；两种模式不能在同一方法混用。标签名必须是非空字符串字面量，不能重名，且目标必须存在。`GOTO` 不支持含 `try/catch/finally` 的方法。`JUMP` 不执行控制流语义检查，但 JVM 对操作数栈、局部变量类型和 StackMap 帧的校验无法绕过；无效跳转可能在构建或类加载时失败。未运行字节码转换时，入口方法会抛出异常。

`@ConstExpr` 可以标记类、静态方法、静态字段或局部变量。在 `TAG` 模式下，类和方法标记还会启用方法内常量 `if`、`switch`、`while`、`for` 条件的折叠。局部变量标记会在所属方法启用相同的处理，它是处理指令，不表示用注解值替换该变量。Java 不允许直接给这些语句加注解，因此应标记所属方法或局部变量声明。只有可证明为常量的条件才会折叠；动态分支和循环保留到运行期。

分支处理还会识别控制流汇合处一致的值、折叠 JVM 的两种 `switch` 形式，并删除不可达分支及多余跳转。仅在无异常处理区的方法中折叠无条件跳转链；替换跳转时仍会保留原选择器或条件的求值，以维持异常和副作用。

对数组，已知的 `length` 可传播到后续比较。连续读取同一个局部变量做 `==`/`!=`、比较两个可追溯到不同分配指令的新数组，以及判断新数组是否为 `null` 时，都可确定引用比较结果。数组分配仍会保留；来自参数、字段或无法证明来源的数组继续在运行期比较。

依赖 JAR 中带注解的静态方法会在调用时被发现。继承的方法可沿依赖类的父类和接口解析。对多版本 JAR，构建时会使用当前 JDK 支持的最高 class 版本；运行结果应使用兼容的 JDK，使运行期选中相同版本。动态实参的调用仍保留到运行期。

转换后，只被已折叠调用使用的私有静态方法，以及随之不再使用的私有静态调用者和辅助方法会被删除。运行期代码或方法引用仍使用的方法会保留；非私有方法也会保留，以供项目外部调用。项目字节码中出现 `Class.getDeclared*`、反射 API、MethodHandle 查找等用法时，会保守保留私有成员；动态名称也不影响保留。

方法体优化还会删除相邻的无用常量入栈/出栈、局部变量读取后立即丢弃、以及同一局部槽的无效读写。在无分支方法中，源槽不变且目标只赋值一次时，会传播局部变量副本。未读取的纯算术局部赋值可删除；连续 `int`/`long` 加法中的字面量可合并。浮点运算、可能抛异常或有副作用的表达式不会参与这些改写。直接对象工厂会让新对象留在操作数栈上完成字段赋值与调用，减少临时局部槽的加载和存储。

仅供已折叠的新对象调用使用的私有实例方法和构造器，在没有存活的项目调用或方法引用需要它们时也会删除。未使用的数组工厂、解码器、模板字段及其初始化指令同样会被裁剪。没有注解且未被引用的私有字段也会删除，`serialVersionUID` 除外。启用反射保护时，带 `@ConstExpr` 的字段也会保留并初始化为计算出的值，以供反射读取。反射若在项目外部的框架中发生，处理器无法从项目字节码中发现，需显式设置 `preserveReflectiveMembers = true`。该保护保证成员可被查找和读取；通过反射修改 `@ConstExpr` 字段后，已内联的读取仍是编译期值。

运行期反射读取也会在可证明安全的形态下改写：同类 `public static` 字段通过 `类名.class.getDeclaredField("固定名称").getX(null)` 直接读取时，生成 `GETSTATIC`。同类方法若只用动态名称查找静态字段，立即 `setAccessible(true)` 并调用 `get(null)`，且没有把 `Field` 对象传给其他代码，会为最多 16 个字段生成直接读取分支；未知名称仍走原反射路径。方法反射、对象字段、逃逸的 `Field` 对象和其他动态形态保持原调用。直接分支会跳过反射 API 的访问检查；依赖 `SecurityManager` 或反射调用拦截行为的代码不应使用这种优化。

在 `SMART` 模式下，直接创建的对象也可能折叠：构造器只能初始化对象自身字段，实例方法必须是无副作用的标量计算。例如，`new Scale(7).apply(3)` 可变为 `21`，并删除对象分配与调用。局部接收者必须只赋值一次且不被复用。依赖身份哈希、静态状态、回调或构造器副作用的调用会留在运行期。

## 表达式与校验

数值型 `value` 表达式在构建时求值，支持 `+`、`-`、`*`、`/`、`%`、括号、一元正负号、十进制/十六进制/二进制整数字面量，以及数值中的下划线。浮点表达式支持相同的算术运算符和括号。

普通方法体中的纯 JDK 整数运算也可以内联：`Math`/`StrictMath` 的 `abs`、`min`、`max`、`floorDiv`、`floorMod` 和精确整数运算，以及 `Integer`/`Long` 的位计数、移位旋转、字节反转和无符号运算。所有实参都能确定时，调用会替换为数值常量；动态实参或会抛异常的输入仍保留原调用。在 `TAG` 模式下，这项处理只作用于被选中的方法体。

注解处理器通过 `META-INF/services` 注册，Gradle 插件会将自身产物加入 `compileOnly` 和 `annotationProcessor`。它会在字节码转换前拒绝非法声明，包括非静态成员、带 Java 初始化表达式的字段、冲突的值来源、不支持的类型、非常量数组形状，以及不兼容的 `Class`、枚举或 `null` 值。带注解的方法必须为静态且不能返回 `void`。使用 `value` 等注解字面量的方法不能有参数；带参数的方法从方法体求值。解释器会拒绝不支持的字节码和无法取得字节码的调用，包括 I/O、native 方法、反射和动态类加载。

`checkLegit` 默认为 `true`。严格模式会在转换前检查注解语法、类型、参数用法、类路径中可解析的调用与构造器、数组构造及常量下标。只应在可信构建中设置 `checkLegit = false`：此时 `value` 会作为 Java 表达式编译，可调用编译类路径中第三方库的 public 构造器与方法；带参数的 `value` 方法可以引用参数名。这会在构建时执行任意代码，无法审计或复现，不应对不可信依赖启用。Gradle 插件会在该模式下自动启用 `-parameters`，使 `a`、`b`、`c` 等名称可用于表达式编译。

对于 `new Clazz(...)`，处理器会扫描完整编译类路径，并自动导入名称无歧义的类。如果多个依赖都定义了 `Clazz`，请使用完整类名。两种模式都保留解释器步数和数组大小限制。在非严格模式下，越界的常量数组访问会留给运行期 JVM 处理，不会在转换时被拒绝。

## 构建与配置

运行 `./gradlew verifyMacroJava` 可执行无需测试框架的 `main` 回归测试。运行 `./gradlew benchmarkMacroJava` 可执行可重复的数组测试，输出输入/输出 class 大小、转换耗时、类初始化耗时中位数和调用耗时中位数。该测试使用相同的生成输入，在独立的原始类加载器与转换后类加载器中比较。`check` 包含 `verifyMacroJava`。

大型基本类型数组保存在输出 class 内生成的私有方法中，不需要外部资源文件。元素全部为默认值的数组只需分配；稀疏数组仅保存非默认元素；密集数组使用 Base64 和 JDK 批量解码。默认工厂返回新副本。在使用插件的项目中可以配置单个数组的编译期上限：

```groovy
macroJava {
    workers = 0 // 自动选择；1 表示串行，其他正整数指定 worker 数量
    showProgress = true // 输出当前扫描、处理、裁剪和写入阶段；默认 false
    maxArrayElements = 16_777_216
    stepsPerArrayElement = 32
    maxStepLimit = 600_000_000
    shareTemplateArray = true
    checkLegit = true
    preserveReflectiveMembers = false // 外部框架使用反射时设为 true
}
```

`enabled` 默认为 `true`，应用插件后会执行字节码转换；设为 `false` 可显式禁用。插件将 javac 输出保存在 `build/classes/java/main-raw` 和 `build/classes/java/test-raw`，并从对应的 `main-macrojava` 和 `test-macrojava` 目录提供转换后的 class。`macroJavaDryRun` 可预览主源码集的变化，不写入转换后的 class。实际构建与预览都会在 `build/reports/macrojava/` 下生成制表符分隔的大小及耗时报告。

如果项目把同一 source set 的 Java 目标目录和资源目录设为同一路径（例如 Forge 开发环境中的 `build/sourcesSets/main`），插件会把转换后的 class 放入该共享目录，并在转换前执行资源复制。这样 `mods.toml` 与带 `@Mod` 注解的 class 会位于同一个 mod 根目录。

`showProgress = true` 会在构建日志中显示当前处理的 class、真正替换的字段和方法调用、方法指令与分支数量的变化，以及实际删除的方法和字段；相同的替换会合并为 `xN`。并行模式会标出 worker 编号。直接调用处理器时可传入 `--progress`。TSV 报告中的 `poolBefore`、`poolAfter` 分别是原始与最终 class 的常量池项数。

```text
[ConstExpr] worker-0 processing ParallelBase.class
[ConstExpr] worker-0 inline call ParallelBase.helper()I into value(I)I
[ConstExpr] worker-0 inline field ParallelBase.offsetI into value(I)I
[ConstExpr] worker-1 processing ParallelA.class
[ConstExpr] worker-1 inline call ParallelBase.value(I)I into run()I
[ConstExpr] worker-1 optimize run()I: instructions 3->2, branches 0->0
[ConstExpr] pruning unused methods
[ConstExpr] remove method ParallelBase.helper()I
```

直接调用 `ConstExprBuildProcessor` 时，外部反射保护可通过 JVM 属性 `-Dxh.xianhhh.constexpr.preserveReflectiveMembers=true` 启用。默认仍会自动识别项目自身的反射调用。

每个 worker 在独立 JVM 中运行，避免共享解释器的可变状态，同时利用多核 CPU。自动模式在 class 数量少于 32 个或字节码总量少于 256 KiB 时使用串行处理；较大的输入最多使用 8 个 worker。每个 worker 都会扫描项目 class，因此小项目的启动和扫描开销可能超过并行收益。报告中的 `transformNanos` 和 `scanNanos` 是各 worker 耗时之和，`wallNanos` 是处理器调用的实际经过时间。

`mode = ConstExprMode.TAG` 只处理显式标记的方法；`mode = ConstExprMode.SMART` 会自动发现符合条件的静态方法。

`maxArrayElements`、`stepsPerArrayElement`、`maxStepLimit` 的默认值分别为 `8_388_608`、`24`、`300_000_000`。`baseStepLimit` 默认为 `100_000_000`，可独立配置。直接使用 `ConstExprBuildProcessor` 时，可改用对应的 JVM 属性：`xh.xianhhh.constexpr.maxArrayElements`、`xh.xianhhh.constexpr.stepsPerArrayElement`、`xh.xianhhh.constexpr.maxStepLimit`、`xh.xianhhh.constexpr.shareTemplateArray`。`shareTemplateArray = true` 会直接返回共享的基本类型数组模板，不进行克隆；调用者必须将返回数组视为只读。
