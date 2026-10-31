# MacroJava

English | [简体中文](README.zh-CN.md)

MacroJava is a Gradle plugin that transforms Java bytecode after compilation. It provides `@ConstExpr` constant inlining and `GOTO`/`JUMP` branches. Building and running the plugin requires JDK 17. The transformer preserves the input class-file version and supports Java 8 or newer bytecode.

## Installation and usage

Once this plugin ID is published, Gradle downloads the plugin and its dependencies from the Gradle Plugin Portal. Configure plugin repositories in `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Apply the plugin in `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'io.github.xianhhh.macrojava' version '2.0.0'
}
```

The plugin adds its annotations to `compileOnly` and `annotationProcessor` automatically. Run `./gradlew classes` or `./gradlew testClasses` to compile and transform the corresponding source set (`gradlew.bat` on Windows). Packaging and run tasks use the transformed classes. Existing Java imports still use the `xh.xianhhh` package; only the Gradle group and plugin ID use the reverse-domain prefix.

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

Declare application libraries in `dependencies` as usual. The evaluator reads their bytecode from the compile classpath:

```groovy
repositories { mavenCentral() }

dependencies {
    implementation 'org.example:some-library:1.0.0'
}
```

Adding the plugin JAR as a regular dependency does not run the transformation; apply the Gradle plugin for compile-time optimization.

### Build from source

Clone the [GitHub repository](https://github.com/xianhhh/MacroJava):

```bash
git clone https://github.com/xianhhh/MacroJava.git
```

To use that checkout as a plugin, add it to the consuming project's `settings.gradle`:

```groovy
pluginManagement {
    includeBuild('../MacroJava') // Adjust to the source directory.
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

The repository includes a Gradle 9.3.0 wrapper. Run `./gradlew check` at the root to verify the plugin; run the same command from `instance` to verify the independent example project.

### Annotation examples

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

See also the [optimization comparison](OPTIMIZATION_PASSES.md), [example project](instance/src/main/java/xh/xianhhh/Main.java), [license](LICENSE), [third-party notices](THIRD_PARTY_NOTICES.md), and [CI build](.github/workflows/ci.yml).

## Constant propagation

In SMART mode, constant-argument scalar numeric calls to `Math` and `StrictMath` are folded, including floating-point methods such as `sin`, `fma`, and `copySign`; `random()` remains a runtime call. Emitted `float` and `double` constants retain the computed raw bits, including negative zero. Platform-dependent `Math` results reflect the build JDK. Static numeric methods from third-party classpath entries are also folded when their bytecode consists only of numeric operations and recursively verifiable pure calls. Calls with dynamic arguments, runtime exceptions, side effects, or unverified bytecode remain at runtime. This can cover qualifying math helpers in libraries such as `org.joml`, but does not fold arbitrary object operations.

### Parameter write-back annotations

`@In`, `@Out`, and `@Ref` mark primitive parameters. `@In` rejects reassignment inside the method. `@Ref` receives the caller's value and writes its final value back on normal return. `@Out` does not receive the caller's value: it must be assigned before its first read and on every normal return path, then writes the result back:

```java
import xh.xianhhh.macro.In;
import xh.xianhhh.macro.Out;
import xh.xianhhh.macro.Ref;

static boolean setAnswer(@Out int result) { result = 42; return true; }
static void addSeven(@Ref int value) { value += 7; }
static double twice(@In double value) { return value * 2; }

int result = 0;
boolean accepted = setAnswer(result); // accepted is true; result is 42
addSeven(result); // result is 49 after transformation
```

Currently, `@Out/@Ref` requires a static method compiled in the same source set, with any return type and exactly one primitive parameter; the argument must directly load a local variable. The original return value remains available to assignments and conditions, and write-back occurs after a normal return. Java still requires an `@Out` argument to be initialized before the call, but its value is not passed to the callee. The annotation processor validates declarations. Bytecode transformation processes `GOTO/JUMP`, then parameter write-back, then `ConstExpr` folding. Unsupported calls fail with a diagnostic.

The transformer replaces scalar field reads and annotated static calls with typed JVM constants. Calls with parameters are evaluated when every argument is known at compile time. Fixed-index array reads and `length` can also propagate. Evaluated methods may construct objects, call methods, and access instance fields when the required bytecode is available in project output or the compile classpath. Dependency bytecode is read from directories or JARs and interpreted without reflective loading.

```java
final class LookupTable {
    @ConstExpr(values = {"11", "29", "47"})
    static long[] values;

    @ConstExpr
    static long scaled(int index, long factor) {
        return values[index] * factor;
    }
}

long result = LookupTable.scaled(1, 3L); // Replaced with 87L.
int count = LookupTable.values.length;   // Replaced with 3.
long first = LookupTable.values[0];      // Replaced with 11L.
```

Arguments can be constant expressions or locals that have the same constant on every reachable path. Calls depending on runtime input stay at runtime. Array results preserve their inferred dimensions, including ragged arrays; annotated multidimensional fields require `shape`. By default, returned arrays are constructed at runtime so each call returns a new mutable array.

Reconstructible object results can also fold: project classes with available constructor bytecode, serializable Java objects and collections, `Optional`, object arrays without shared references, and `getClass()` on a known receiver. Serialized results are embedded in the class and deserialized for each call; large snapshots are split across string constants. Deserializing small objects may cost more than constructing them directly. Constructor-established self-references use the original constructor. Simple object graphs with cycles or shared references can be rebuilt when nodes have public no-argument constructors and writable fields. Other graphs retain the original call.

Large reference arrays, including ragged multidimensional arrays, are filled by size-limited helper methods; sparse elements remain `null`. Calls remain at runtime when embedding would require too many factories or constants. String literals encoded with a `StandardCharsets` constant can be folded in `SMART` mode, or in selected method bodies in `TAG` mode. Dynamic strings and charsets remain at runtime. Large byte arrays use the template decoder; `shareTemplateArray = true` opts into sharing.

In straight-line code, repeated assignments of the same compile-time array to successive locals can reuse one construction and copy it for later locals. Reuse is skipped if the first local has been read or reassigned, or control flow branches. Multidimensional arrays are copied by level. With template sharing enabled, primitive arrays share a reference while outer nested arrays remain distinct.

## Control flow and pruning

Use ordinary Java calls for unconditional branches. `GOTO` checks labels, the operand stack, and post-jump control flow. `JUMP` emits a direct JVM `GOTO`, skips those semantic checks, and can cross `finally`:

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

Labels and jumps must be in the same method and use one mode. Label names must be nonempty string literals, unique, and defined. `GOTO` does not support methods containing `try/catch/finally`. `JUMP` cannot bypass JVM operand-stack, local-type, or StackMap verification; invalid jumps may fail during build or class loading. Without transformation, the entry methods throw.

`@ConstExpr` can mark classes, static methods, static fields, and local variables. In `TAG` mode, class or method markers also enable folding of constant `if`, `switch`, `while`, and `for` conditions inside methods. A marked local enables the same method-level processing; its annotation is an instruction to transform the method, not to replace the local with the annotation value. Only provably constant conditions fold. The transformer also handles values consistent at control-flow joins, both JVM `switch` forms, unreachable branches, and redundant jumps. Jump chains are folded only in methods without exception handlers; selector and condition evaluation remains intact.

Known array lengths can propagate into comparisons. Reference comparisons can fold for repeated reads of one local, distinct newly allocated arrays, and a new array versus `null`. Allocation remains in place. Arrays from parameters, fields, or unknown origins retain runtime comparisons.

Annotated static methods in dependency JARs are discovered at call sites. Inherited methods can be resolved through superclass and interface bytecode. For multi-release JARs, the build selects the highest class version supported by the current JDK; use a compatible runtime so it selects the same version.

After transformation, private static helpers used only by folded calls, their unused callers, and unused generated factories, decoders, fields, and initialization code are pruned. Live runtime calls and method references retain their targets. Nonprivate methods remain available to external callers. Project bytecode using `Class.getDeclared*`, reflection APIs, or MethodHandle lookups conservatively retains private members. For reflection in external frameworks, set `preserveReflectiveMembers = true`; annotated fields then retain their computed values for reflection. Inlined reads still have their compile-time values after a reflective field mutation.

Some safe reflective static-field reads are rewritten as `GETSTATIC`: fixed-name same-class public fields, or up to 16 dynamic-name fields when a same-class lookup immediately calls `setAccessible(true)` and `get(null)` without escaping the `Field`. Unknown names keep the reflective path. Method reflection, instance fields, and escaping `Field` objects are unchanged. Direct branches bypass reflection access checks, so avoid this optimization when code depends on `SecurityManager` or reflection interception.

Method cleanup removes redundant constant push/pop pairs, discarded local loads, and ineffective same-slot reads/writes. It propagates local copies in branchless methods when the source stays unchanged and the destination is assigned once, removes unread pure arithmetic stores, and combines literals in consecutive `int`/`long` additions. It preserves floating-point order and expressions that may throw or have effects. In `SMART` mode, simple direct object constructions and side-effect-free scalar instance calls can fold, such as `new Scale(7).apply(3)` becoming `21`; identity-dependent or effectful calls remain at runtime.

## Expressions and validation

Numeric `value` expressions support `+`, `-`, `*`, `/`, `%`, parentheses, unary signs, decimal/hex/binary integer literals, and numeric underscores. Floating-point expressions support the same arithmetic operators and parentheses. Pure JDK integer calls can also fold when all arguments are known: selected `Math`/`StrictMath` functions and `Integer`/`Long` bit operations. Inputs that might throw and calls with dynamic arguments remain unchanged. In `TAG` mode, this applies only to selected method bodies.

The annotation processor is registered through `META-INF/services`. It rejects invalid declarations before transformation, including nonstatic members, fields with Java initializers, conflicting value sources, unsupported types, nonconstant array shapes, and incompatible `Class`, enum, or null values. Annotated methods must be static and nonvoid. Methods using annotation literals such as `value` cannot take parameters; parameterized methods are evaluated from their body. The interpreter rejects unsupported bytecode and calls whose bytecode is unavailable, including I/O, native methods, reflection, and dynamic class loading.

`checkLegit` defaults to `true`. Strict mode validates annotation syntax, types, parameters, resolvable calls and constructors, array construction, and constant indexes. Set `checkLegit = false` only in trusted builds: `value` is then compiled as a Java expression and can call public constructors and methods from third-party compile dependencies. Parameterized `value` methods may refer to parameter names; the plugin enables `-parameters` for this mode. This can execute arbitrary code during the build and cannot be audited or reproduced for untrusted dependencies. For `new Clazz(...)`, unambiguous class names are imported from the full compile classpath; use a qualified name when dependencies define duplicates. Interpreter step and array-size limits apply in both modes. Non-strict mode leaves out-of-bounds constant array accesses for the runtime JVM.

## Build and configuration

`./gradlew verifyMacroJava` runs the framework-free regression program. `./gradlew benchmarkMacroJava` runs a repeatable array benchmark comparing original and transformed classes in separate class loaders and reports class sizes, transformation time, median class initialization time, and median call time. `check` includes `verifyMacroJava`.

Large primitive arrays live in private methods in the output class and need no external resource. Default-only arrays need allocation, sparse arrays store nondefault elements, and dense arrays use Base64 and JDK bulk decoding. Factories return a fresh copy by default. Configure per-array compilation limits in a consuming project:

```groovy
macroJava {
    workers = 0 // Automatic; 1 is serial, other positive values set the worker count.
    showProgress = true // Show scan, transform, prune, and write stages; default is false.
    maxArrayElements = 16_777_216
    stepsPerArrayElement = 32
    maxStepLimit = 600_000_000
    shareTemplateArray = true
    checkLegit = true
    preserveReflectiveMembers = false // Enable for reflection in external frameworks.
}
```

`enabled` defaults to `true`; set it to `false` to disable transformation. Raw javac output goes to `build/classes/java/main-raw` and `test-raw`, while transformed classes are exposed from `main-macrojava` and `test-macrojava`. `macroJavaDryRun` and `macroJavaTestDryRun` preview the main and test source sets respectively without writing transformed classes. Builds and previews write tab-separated size and timing reports under `build/reports/macrojava/`; the test preview report is `test-dry-run.tsv`. When a source set shares its Java and resources output directory (for example a Forge mod root), the plugin writes transformed classes there after copying resources.

With `showProgress = true`, the build log shows processed classes, replaced calls and fields, instruction and branch count changes, and removed members. Repeated replacements are grouped as `xN`; parallel logs include worker IDs. Pass `--progress` when calling the processor directly. Report columns `poolBefore` and `poolAfter` count constant-pool entries before and after transformation.

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

When calling `ConstExprBuildProcessor` directly, enable protection for external reflection with `-Dxh.xianhhh.constexpr.preserveReflectiveMembers=true`. Reflection inside the project is detected automatically. Each worker runs in its own JVM. Automatic mode is serial below 32 classes or 256 KiB of bytecode and uses up to eight workers for larger inputs. Worker scan and transformation times are summed in `scanNanos` and `transformNanos`; `wallNanos` is elapsed processor time.

`mode = ConstExprMode.TAG` handles only explicitly marked methods; `mode = ConstExprMode.SMART` discovers eligible static methods. Defaults are `8_388_608` for `maxArrayElements`, `24` for `stepsPerArrayElement`, `300_000_000` for `maxStepLimit`, and `100_000_000` for `baseStepLimit`. Direct processor calls can set the corresponding `xh.xianhhh.constexpr.*` JVM properties, including `shareTemplateArray`. When sharing is enabled, callers must treat returned primitive array templates as read-only.
