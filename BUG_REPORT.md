# YouTrack report

Starting with 2.4.20-Beta2, `kotlin-scripting-jsr223` builds a new K2 compiler environment for every script state and never disposes it. A new state is created for each new `ScriptEngine` and for each new `ScriptContext` set on an engine. Every one of them keeps about 24 MB of heap reachable for the life of the JVM, more with a larger classpath, so hosts that create engines or reset contexts run out of memory. 2.4.10 and 2.4.20-Beta1 retain nothing in the same code.

**Reproducer:** https://github.com/pambrose/kotlin-jsr223-k2-leak (plain Java + `javax.script`, `./gradlew minimal` / `./gradlew run` / `./run-matrix.sh`).

### Steps to reproduce

Runtime classpath: only `org.jetbrains.kotlin:kotlin-scripting-jsr223:2.4.21` and its dependencies. Run with `-Xmx512m`:

```java
ScriptEngineManager manager = new ScriptEngineManager();
for (int i = 1; i <= 1_000; i++) {
  ScriptEngine engine = manager.getEngineByExtension("kts");
  engine.eval(i + " * 2");          // the engine is unreachable after this line
  if (i % 10 == 0) {
    System.gc();
    Runtime rt = Runtime.getRuntime();
    System.out.println(i + " evaluations: " + (rt.totalMemory() - rt.freeMemory()) / 1_000_000 + " MB in use after GC");
  }
}
```

**Expected:** heap stays flat, as on 2.4.10: `1000 evaluations: 118 MB in use after GC`.

**Actual (2.4.21):** `10 evaluations: 271 MB`, `20 evaluations: 513 MB`, then the next `eval` fails with `javax.script.ScriptException: java.lang.NullPointerException` at `K2ReplEvaluator.evalSnippet(K2ReplEvaluator.kt:79)`. With the measuring harness, the same scenario ends in `java.lang.OutOfMemoryError: Java heap space`.

The same happens with one engine and a new context per evaluation:

```java
SimpleScriptContext context = new SimpleScriptContext();
context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
engine.setContext(context);
engine.eval(...);
```

### Measurements

The values are heap retained after full GCs, live `KotlinCoreEnvironment` instances from `GC.class_histogram`, and entries in `Disposer.getTree().myObject2ParentNode`. 40 evaluations, JDK 25.0.4:

| Kotlin | retained per new engine | retained per new context | live environments after 40 new engines | Disposer tree entries after 40 new engines |
|---|---|---|---|---|
| 2.4.10 | 0.0 MB/eval | 0.0 MB/eval | 0 | 0 |
| 2.4.20-Beta1 | 0.0 MB/eval | 0.0 MB/eval | 0 | 0 |
| 2.4.20-Beta2 | 23.8 MB/eval | 23.4 MB/eval | 40 | 1115 |
| 2.4.20 | 23.8 MB/eval | 23.4 MB/eval | 40 | 1115 |
| 2.4.21 | 23.8 MB/eval | 23.5 MB/eval | 40 | 1115 |
| 2.5.0-Beta1 | 23.9 MB/eval | 23.6 MB/eval | 40 | 1198 |

Temurin 17.0.19 and 21.0.11 give the same results. After 30 dropped engines on 2.4.21, the class histogram holds exactly 30 each of `KotlinCoreEnvironment`, `MockProject`, `CompilerConfiguration`, `JvmPackagePartProvider`, and `CliVirtualFileFinderFactory`. The bulk of the 746 MB retained is deserialized library metadata (`ProtoBuf$Type` alone accounts for about 878,000 instances and 84 MB) and 33,783 `FastJarVirtualFile`s.

### Cause

`KotlinJsr223ScriptEngineImpl.createState` creates each state's compiler under a `Disposable` that it explicitly does not dispose:

```kotlin
override fun createState(lock: ReentrantReadWriteLock): K2ReplState =
    withMessageCollectorAndDisposable(disposeOnSuccess = false) { messageCollector, disposable ->
        K2ReplState(
            K2ReplCompiler(K2ReplCompiler.createCompilationState(messageCollector, disposable, compilationConfiguration)),
            K2ReplEvaluator()
        ).asSuccess()
    }.valueOrThrow()
```

`createCompilationState` → `createIsolatedCompilationContext` → `KotlinCoreEnvironment.createForProduction(parentDisposable, …)` registers the environment under that `Disposable` in IntelliJ's static `Disposer` tree. Nothing disposes it afterwards: the engine has no `close()`, and a replaced or abandoned state is never cleaned up. The static `Disposer` tree therefore keeps every state's `MockProject`, `KotlinCoreEnvironment`, FIR library sessions, and classpath index reachable.

**Confirmation:** disposing that `Disposable` before dropping each engine (`Disposer.dispose(replCompiler.state.compilerContext.disposable)`, via reflection; the reproducer's `new-engine-disposed` mode) brings retention to 0.0 MB per evaluation, 0 live environments, and 0 `Disposer` tree entries on every affected version.

**Introduced by** https://github.com/JetBrains/kotlin/commit/2bf12570d4851c2d3f631ba30bfc2ae7c5630f8d ("Scripting: port JSR-223 implementation to K2", KT-64914), which replaced `createState(lock) = replEvaluator.createState(lock)`. The K1-based engine shared one `JvmReplCompiler` per engine and retained nothing per state. The code is unchanged on `master` as of `391e5766320` (2026-10-12).

### Suggested fix

Give each state's root `Disposable` an owner and dispose it when the state is replaced or the engine is discarded. For example, keep it in `K2ReplState`, and make the engine (or its states) `AutoCloseable` so hosts can release them deterministically. Sharing one compiler environment per engine across contexts, as the K1 engine did, would also avoid rebuilding the classpath index for every new context.

### Notes

- This is distinct from KT-55064 (repeated engine creation leaking about 4 MB per iteration on 1.7.0): on 2.4.10 the new-engine scenario retains nothing.
- Workaround: keep the JSR-223 runtime (`kotlin-scripting-*`, `kotlin-compiler-embeddable`, `kotlin-daemon-embeddable`, `kotlin-script-runtime`) on 2.4.10. The host project can still compile with 2.4.21.
- Environment: macOS 27.0.1 arm64; Temurin 17.0.19, 21.0.11, 25.0.4; Gradle 9.8.1.
