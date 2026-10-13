# Kotlin JSR-223 (K2): every script state's compiler environment is retained forever

Starting with **Kotlin 2.4.20-Beta2**, the JSR-223 script engine (`kotlin-scripting-jsr223`, now built
on the K2 REPL) builds a complete compiler environment for every new script state and never disposes
it. The environment, its FIR sessions, and its index of the classpath stay reachable from IntelliJ's
static `Disposer` tree for the life of the JVM: **about 24 MB per state** with this project's small
classpath, and more with a larger one (about 27 MB in the Ktor application where this was found).

The engine starts a new state for **every new `ScriptEngine`** and for **every new `ScriptContext`**
set on an engine, so an application that does either repeatedly grows its heap without bound until
it fails. Kotlin 2.4.10 and 2.4.20-Beta1 retain nothing in the same scenarios.

The text of the YouTrack report is in [`BUG_REPORT.md`](BUG_REPORT.md).

| Kotlin | heap retained per new engine | per new context | compiler environments alive after 40 evaluations |
|---|---|---|---|
| 2.4.10 | 0.0 MB | 0.0 MB | 0 (new engine) / 1 (new context) |
| 2.4.20-Beta1 | 0.0 MB | 0.0 MB | 0 / 1 |
| **2.4.20-Beta2** | **23.8 MB** | **23.4 MB** | **40 / 40** |
| **2.4.20** | **23.8 MB** | **23.4 MB** | **40 / 40** |
| **2.4.21** | **23.8 MB** | **23.5 MB** | **40 / 40** |
| **2.5.0-Beta1** | **23.9 MB** | **23.6 MB** | **40 / 40** |

## Run it

Requires JDK 17 or later. The Gradle wrapper is included.

```bash
./gradlew minimal                                   # 1,000 evaluations in a 512 MB heap: fails on 2.4.21
./gradlew minimal -PkotlinVersion=2.4.10            # the same on 2.4.10: completes, heap flat

./gradlew run                                       # the measuring harness, Kotlin 2.4.21
./gradlew run -PkotlinVersion=2.4.10                # ... on 2.4.10
./gradlew run --args="new-context 40"               # mode, evaluations (and optionally, how often to report)
./gradlew run --args="new-engine-disposed 40"       # proof of the cause (see below)
./gradlew run --args="new-engine 30 10 histogram"  # what the retained heap is made of
./gradlew run -Pheap=512m --args="new-engine 200 20" # ends in OutOfMemoryError

./run-matrix.sh                                     # every version × mode; full output in build/matrix/
JAVA_VERSION=17 ./run-matrix.sh                     # the same on another installed JDK
```

`-PkotlinVersion` selects the Kotlin version under test, and `-PjavaVersion` (or `JAVA_VERSION` for
the script) runs it on another installed JDK.

## What the reproducer does

The code is plain Java that uses only `javax.script`. The runtime classpath holds
`org.jetbrains.kotlin:kotlin-scripting-jsr223:<version>` and what it brings in, so the only Kotlin
present is the version under test. There are no agents, coverage tools, or test frameworks in the JVM.

[`Minimal.java`](src/main/java/repro/Minimal.java) is the whole bug in a dozen lines:

```java
ScriptEngineManager manager = new ScriptEngineManager();
for (int i = 1; i <= 1_000; i++) {
  ScriptEngine engine = manager.getEngineByExtension("kts");
  engine.eval(i + " * 2");      // the engine is unreachable after this line
  if (i % 10 == 0) { System.gc(); /* print heap in use */ }
}
```

[`Jsr223StateLeak.java`](src/main/java/repro/Jsr223StateLeak.java) is the measuring harness. It
evaluates `"<i> * 2"` (and checks the result) in one of four modes, and every few evaluations
it forces full GCs and reports:

- **retained heap**: heap in use after `System.gc()`.
- **live `KotlinCoreEnvironment`**: live instances, from the JVM's own class histogram (`GC.class_histogram`
  through the `DiagnosticCommand` MBean, in process).
- **Disposer tree entries**: the size of IntelliJ's static `Disposer` tree
  (`Disposer.getTree().myObject2ParentNode`): every `Disposable` registered under a parent that has not
  been disposed.

| mode | what it does |
|---|---|
| `new-engine` | a new `ScriptEngine` for each evaluation, dropped afterwards |
| `new-context` | one engine; a new `ScriptContext` with fresh ENGINE_SCOPE bindings before each evaluation |
| `new-engine-disposed` | `new-engine`, but each state's root `Disposable` is disposed (by reflection) before the engine is dropped. K2 engine only |
| `same-context` | control: one engine and one context, so every snippet joins one REPL history |

"Per evaluation" is the slope of retained heap between evaluation 5 and the last one, after the
first evaluation has loaded the compiler.

## Results

JDK 25.0.4 (Temurin), macOS 27.0.1 arm64, 4 GB heap, 40 evaluations per run (`./run-matrix.sh`):

```
Kotlin         mode                        per eval      retained   environments Disposer entries
2.4.10         new-engine                     0.0MB          23MB              0                0
2.4.10         new-context                    0.0MB          49MB              1               63
2.4.10         new-engine-disposed   n/a
2.4.10         same-context                   0.7MB          77MB              1               63
2.4.20-Beta1   new-engine                     0.0MB          23MB              0                0
2.4.20-Beta1   new-context                    0.0MB          49MB              1               60
2.4.20-Beta1   new-engine-disposed   n/a
2.4.20-Beta1   same-context                   0.7MB          77MB              1               60
2.4.20-Beta2   new-engine                    23.8MB         981MB             40             1115
2.4.20-Beta2   new-context                   23.4MB         967MB             40             1115
2.4.20-Beta2   new-engine-disposed            0.0MB          22MB              0                0
2.4.20-Beta2   same-context                   1.3MB         104MB              1              140
2.4.20         new-engine                    23.8MB         982MB             40             1115
2.4.20         new-context                   23.4MB         968MB             40             1115
2.4.20         new-engine-disposed            0.0MB          22MB              0                0
2.4.20         same-context                   1.3MB         104MB              1              140
2.4.21         new-engine                    23.8MB         982MB             40             1115
2.4.21         new-context                   23.5MB         968MB             40             1115
2.4.21         new-engine-disposed            0.0MB          22MB              0                0
2.4.21         same-context                   1.3MB         104MB              1              140
2.5.0-Beta1    new-engine                    23.9MB         991MB             40             1198
2.5.0-Beta1    new-context                   23.6MB         976MB             40             1198
2.5.0-Beta1    new-engine-disposed            0.0MB          22MB              0                0
2.5.0-Beta1    same-context                   1.3MB         106MB              1              145
```

The JDK makes no difference. On Temurin 17.0.19 and 21.0.11, 10 evaluations each:

```
JDK 17   2.4.10   new-engine    0.0MB    23MB    0      0
JDK 17   2.4.21   new-engine   23.8MB   268MB   10    305
JDK 17   2.4.21   new-context  23.2MB   264MB   10    305
JDK 21   2.4.10   new-engine    0.0MB    23MB    0      0
JDK 21   2.4.21   new-engine   23.8MB   268MB   10    305
JDK 21   2.4.21   new-context  23.4MB   265MB   10    305
```

In a 512 MB heap (`./gradlew run -Pheap=512m --args="new-engine 200 20"`), 2.4.10 finishes all 200
evaluations at a flat 24 MB, and 2.4.21 is at 513 MB with 20 live environments after 20
evaluations and then throws `java.lang.OutOfMemoryError: Java heap space`. `./gradlew minimal`
fails the same way after about 20 evaluations, although there the failure surfaces as
`javax.script.ScriptException: java.lang.NullPointerException` from
`K2ReplEvaluator.evalSnippet(K2ReplEvaluator.kt:79)` (the `!!` on the snippet class's eval method)
rather than as an `OutOfMemoryError`.

`same-context` is the REPL working as designed: one state whose history grows with each snippet.
Its growth is larger on the K2 engine (1.3 MB against 0.7 MB per snippet), but it involves only one
environment, and it is not the subject of this report.

## Root cause

`KotlinJsr223ScriptEngineImpl.createState` (kotlin-scripting-jvm-host 2.4.21, line 87) builds each
state's compiler under a fresh `Disposable` and explicitly opts out of disposing it:

```kotlin
override fun createState(lock: ReentrantReadWriteLock): K2ReplState =
    withMessageCollectorAndDisposable(disposeOnSuccess = false) { messageCollector, disposable ->
        K2ReplState(
            K2ReplCompiler(
                K2ReplCompiler.createCompilationState(messageCollector, disposable, compilationConfiguration)
            ),
            K2ReplEvaluator()
        ).asSuccess()
    }.valueOrThrow()
```

`withMessageCollectorAndDisposable` (kotlin-scripting-compiler, `jvmCompilationUtil.kt`) creates
`Disposer.newDisposable("Default disposable for scripting compiler")` and disposes it in `finally`
only `if (disposeOnSuccess || failed)`. `K2ReplCompiler.createCompilationState` passes it to
`createIsolatedCompilationContext`, which calls
`KotlinCoreEnvironment.createForProduction(parentDisposable, ...)` (`compilationContext.kt`, line 85)
and then builds the FIR library sessions over the whole classpath.

Nothing disposes that `Disposable` afterwards. A state can still reach it
(`replCompiler.state.compilerContext.disposable`), but no code path ever calls `Disposer.dispose` on it.
The engine has no `close()` or other lifecycle hook, and a state that has been replaced (new context)
or abandoned (dropped engine) is never cleaned up. Everything `KotlinCoreEnvironment` registers under the parent therefore
stays in IntelliJ's static `Disposer` tree (`Disposer.ourTree`). That tree is the GC root that keeps
`MockProject`, the environment, its FIR sessions, the deserialized library metadata, and the
`FastJarFileSystem` classpath index reachable. The harness sees this directly: about 27 `Disposer` tree
entries are added per state and never removed.

After 30 dropped engines on 2.4.21 (`./gradlew run --args="new-engine 30 10 histogram"`), 746 MB
is retained. The JVM's class histogram shows exactly 30 each of `KotlinCoreEnvironment`, `MockProject`,
`CompilerConfiguration`, `JvmPackagePartProvider`, and `CliVirtualFileFinderFactory`: one per state.
Most of the bytes are deserialized library metadata (about 878,000 `ProtoBuf$Type`, 84 MB, plus the
other `ProtoBuf$*` classes) and 33,783 `FastJarVirtualFile`s from the per-state classpath index. On
2.4.10 the same run retains 23 MB and none of those classes has a live instance.

### Proof

`new-engine-disposed` is `new-engine` plus one call, made before the engine is dropped:
`Disposer.dispose(...)` on the state's root `Disposable` (reached by reflection as
`replCompiler.state.compilerContext.disposable`, because nothing public exposes it). With that call,
retention drops from 23.8 MB to **0.0 MB** per evaluation, live environments from 40 to **0**, and
`Disposer` tree entries from 1,115 to **0**, on every affected version.

### Where it came from

The leak arrived with [JetBrains/kotlin@2bf12570d48](https://github.com/JetBrains/kotlin/commit/2bf12570d4851c2d3f631ba30bfc2ae7c5630f8d)
("Scripting: port JSR-223 implementation to K2", 2026-07-04, `^KT-64914 fixed`). That commit replaced

```kotlin
override fun createState(lock: ReentrantReadWriteLock): IReplStageState<*> = replEvaluator.createState(lock)
```

with the `withMessageCollectorAndDisposable(disposeOnSuccess = false)` version above. The K1-based
engine shared one `JvmReplCompiler` per engine and retained nothing per state. The first release with
the K2 engine, and the first affected one, is 2.4.20-Beta2. 2.4.20-Beta1 still extends the K1
`org.jetbrains.kotlin.cli.common.repl.KotlinJsr223JvmScriptEngineBase`. As of `master` at
`391e5766320` (2026-10-12), `createState` is unchanged (lines 70–71).

## Who is affected

Any host that creates more than a handful of Kotlin script states over a JVM's lifetime:

- a new `ScriptEngine` per evaluation or per request;
- a pool of engines whose contexts are reset between uses (`engine.setContext(new SimpleScriptContext())`
  with fresh bindings), the usual way to isolate one evaluation from the next;
- anything that re-creates engines on reload.

Each of these gains about 24 MB or more of permanently retained heap per state. We found it when a
content test suite that evaluates a few hundred Kotlin snippets, one state each, started failing with
`OutOfMemoryError` in a 2 GB heap after moving from 2.4.10 to 2.4.21.

## Workarounds

- **Keep the JSR-223 runtime on 2.4.10.** Pin `kotlin-scripting-*`, `kotlin-compiler-embeddable`,
  `kotlin-daemon-embeddable`, and `kotlin-script-runtime` to 2.4.10 on the runtime classpath only; the
  project itself can still compile with a newer Kotlin. In a library, `strictly("2.4.10")` on
  `kotlin-scripting-jsr223` publishes the pin to consumers.
- **Use one engine and one context.** This creates no new states, but the REPL history then grows with
  every snippet, and state leaks from one evaluation to the next.
- **Dispose by reflection**, as `new-engine-disposed` does. This works, but it depends on internal names.

## Suggested fix

Give each state's root `Disposable` an owner and dispose it when the state is discarded. For example,
`K2ReplState` could hold the `Disposable` and the engine could dispose a state when it is replaced. The
engine (or the states it created) could also be made `AutoCloseable`, so a host can release them
deterministically. The K1 design of sharing one compiler environment per engine across contexts would
avoid building about 24 MB of classpath index per context in the first place.

## Related issues

- [KT-55064](https://youtrack.jetbrains.com/issue/KT-55064) (open, reported against 1.7.0): "repeated
  engine creation and disposal leaks ~4 MB of heap on every iteration". This is distinct: on 2.4.10 the
  `new-engine` mode here retains nothing, and the 2.4.20+ retention is about six times larger and has
  a different cause.
- [KT-82741](https://youtrack.jetbrains.com/issue/KT-82741) (fixed): a K2 REPL OOM in
  `FirJavaElementFinderKt.collectAllDependentSourceSessionsTo` when rerunning the same cell. That's a
  different path; it involves one state.

## Environment

- Kotlin 2.4.10, 2.4.20-Beta1, 2.4.20-Beta2, 2.4.20, 2.4.21, 2.5.0-Beta1 (`kotlin-scripting-jsr223`
  from Maven Central)
- JDK: Temurin 17.0.19, 21.0.11, 25.0.4
- macOS 27.0.1, arm64; Gradle 9.8.1
