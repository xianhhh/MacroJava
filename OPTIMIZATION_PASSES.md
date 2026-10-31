# LLVM/GCC optimization passes compared with MacroJava

English | [简体中文](OPTIMIZATION_PASSES.zh-CN.md)

This comparison uses JVM class files as both input and output. LLVM IR and GCC GIMPLE pass names do not imply that MacroJava implements the same passes. **Partial** means only the bytecode forms stated here are covered. **Needs IR** means the current ASM instruction stream lacks the required SSA, dominance, alias, or exception-edge analysis. **Not applicable** means the JVM/JIT or a machine backend handles it, or the rewrite would violate Java semantics.

## LLVM passes

| Pass | Status | MacroJava capability or limitation |
| --- | --- | --- |
| simplifycfg | Partial, extended | Removes unreachable instructions, folds constant branches and redundant jumps, and collapses `goto` target chains; no general basic-block merging or PHI nodes. |
| sccp | Partial | `ConstExprCallSiteAnalyzer.branchInputs` and fixed field/method propagation fold some cross-branch constants; no full sparse SSA solver. |
| dce | Partial, extended | Removes unreachable instructions and unread pure local expressions; retains calls, array accesses, division, class initialization, and other effectful operations. |
| mem2reg | Partial, extended | Propagates single-assignment constant locals and copies in branchless methods when source slots stay unchanged; no SSA promotion. |
| sroa | Needs IR | Splitting mutable arrays/objects requires escape, alias, and exception analysis; fixed-index array folding is not general SROA. |
| reassociate | Partial, added | Combines two literals in consecutive `int`/`long` additions while preserving overflow; does not reorder floating-point operations. |
| sink | Needs IR | Sinking needs dominance, post-dominance, exception order, and effect analysis. |
| loop-simplify | Partial | Recognizes simple single-back-edge loops, normalizes integer conditions, and removes provably zero-iteration loops; no general preheaders or dedicated exits. |
| loop-unroll | Needs IR | Repeated unrolling needs trip-count, overflow, and code-size budgets; only provably zero-iteration loops are removed. |
| loop-unswitch | Needs IR | Needs loop-invariant and alias analysis plus a code-size threshold. |
| loop-rotate | Needs IR | JVM frames, exception regions, and debug ranges must be rebuilt; current patterns do not guarantee a gain. |
| mergefunc | Needs IR | Method identity, reflection, virtual dispatch, and stack traces are observable; only tightly restricted private static methods are candidates. |
| mergereturn | Not applicable | Merging JVM returns usually adds jumps and does not improve constant evaluation here. |
| partial-inliner | Partial | Constant call sites fold and private method chains are pruned; dynamic calls are not generally partially inlined. |
| loweratomic | Not applicable | Removing Java volatile/atomic semantics would violate the memory model. |
| lowerinvoke | Not applicable | JVM exception handling is different from LLVM `invoke/unwind` lowering. |

## GCC -O1 options

| Option | Status | MacroJava capability or limitation |
| --- | --- | --- |
| `-fauto-inc-dec` | Not applicable | The JVM/JIT chooses addressing modes. |
| `-fbranch-count-reg` | Not applicable | The JIT handles machine registers and branch counting. |
| `-fcombine-stack-adjustments` | Partial | `compactLocalStack` removes redundant JVM stack operations, not machine stack-pointer updates. |
| `-fcompare-elim` | Partial | Folds constant comparisons and related branches. |
| `-fcprop-registers` | Partial, added | Propagates copies in branchless methods with one write to the destination local. |
| `-fdce` | Partial, extended | Removes unreachable blocks, unread pure expressions, and unused generated members. |
| `-fdefer-pop` | Not applicable | The JVM operand stack must satisfy verifier rules. |
| `-fdelayed-branch` | Not applicable | The JVM has no delay slots. |
| `-fdse` | Partial, extended | Removes unread local stores, not potentially escaping field or array stores. |
| `-fforward-propagate` | Partial | Propagates fields, methods, immutable local values, and eligible local copies. |
| `-fguess-branch-probability` | Not applicable | There is no reliable build-time profile; the JIT can use runtime profiles. |
| `-fif-conversion` / `-fif-conversion2` | Needs IR | Eagerly evaluating effectful or throwing branches changes semantics; JVM instruction costs also need evaluation. |
| `-finline-functions-called-once` | Partial | Folds evaluable call sites; general dynamic inlining needs dispatch and exception analysis. |
| `-fipa-modref` / `-fipa-profile` / `-fipa-pure-const` / `-fipa-reference` / `-fipa-reference-addressable` | Partial | Project-class pre-scan, compile-time interpretation, and reflection retention provide limited interprocedural information; no general effect summary or profile. |
| `-fmerge-constants` | Existing | ASM output and final constant-pool compaction remove unused entries and merge identical ones. |
| `-fmove-loop-invariants` | Needs IR | Hoisting needs proof about calls, exceptions, array aliases, and class-initialization order. |
| `-fmove-loop-stores` | Needs IR | Mutable field/array writes require alias and exception analysis. |
| `-fomit-frame-pointer` | Not applicable | JVM class files have no controllable machine frame pointer. |
| `-freorder-blocks` | Needs IR | Block layout should use CFG and runtime profiles while maintaining exception tables and frames. |
| `-fshrink-wrap` / `-fshrink-wrap-separate` | Not applicable | The JIT handles machine register save/restore. |
| `-fsplit-wide-types` | Not applicable | The JVM fixes the width of `long` and `double`. |
| `-fssa-backprop` / `-fssa-phiopt` | Needs IR | No SSA or explicit PHI nodes exist. |
| `-ftree-bit-ccp` / `-ftree-ccp` | Partial | Evaluates constant bit operations, local constants, and branch inputs. |
| `-ftree-ch` | Needs IR | Loop-header copying needs CFG, trip counts, and code-size control. |
| `-ftree-coalesce-vars` | Partial | Cleans redundant local loads/stores and propagates copies; no global slot coloring. |
| `-ftree-copy-prop` | Partial, added | Only branchless copies whose source slot stays unchanged. |
| `-ftree-dce` | Partial, extended | Unreachable blocks and effect-free dead local expressions. |
| `-ftree-dominator-opts` | Needs IR | There is no reusable dominator tree. |
| `-ftree-dse` | Partial, extended | Unread local stores; field and array stores are retained. |
| `-ftree-forwprop` | Partial | Propagates constants, array lengths, and eligible local copies. |
| `-ftree-fre` | Needs IR | Global redundancy elimination needs memory effects, aliases, and exception-path analysis. |
| `-ftree-phiprop` | Partial | Equal values across branches propagate; no explicit PHI nodes. |
| `-ftree-pta` | Needs IR | Only limited object/array identity analysis exists, not general pointer analysis. |
| `-ftree-scev-cprop` | Partial | Simple loop bounds and zero-iteration decisions; no scalar evolution model. |
| `-ftree-sink` | Needs IR | Same limitation as `sink`. |
| `-ftree-slsr` | Needs IR | Strength reduction needs induction-variable, overflow, and cost analysis; the JIT may handle it. |
| `-ftree-sra` | Needs IR | Same limitation as `sroa`. |
| `-ftree-ter` | Partial | Replaces local constants and eligible copies. |
| `-funit-at-a-time` | Existing | `preScan` reads every class in the current source set before transformation and pruning. |

## GCC -O2 / -O3 additions

| Option | Status | MacroJava capability or limitation |
| --- | --- | --- |
| `-fgcse` | Needs IR | Cross-block common subexpression elimination needs dominance and effect analysis. |
| `-fcrossjumping` | Needs IR | Merging equivalent tails needs CFG, frame, and exception-region analysis. |
| `-foptimize-sibling-calls` | Not applicable | The JVM has no guaranteed tail-call instruction; the JIT may optimize calls. |
| `-fpeephole2` | Partial | Local JVM instruction folding, redundant jumps, and stack cleanup. |
| `-finline-functions` | Partial | Compile-time evaluable calls are replaced; general runtime method-body inlining needs dedicated IR. |
| `-funswitch-loops` | Needs IR | Same limitation as `loop-unswitch`. |

The additions described here are local copy propagation, integer-addition literal reassociation, pure-value dead-store removal, and jump-chain collapsing. They operate only on bytecode shapes the existing analysis can prove safe, without changing the order of calls, memory accesses, exceptions, or floating-point operations.
