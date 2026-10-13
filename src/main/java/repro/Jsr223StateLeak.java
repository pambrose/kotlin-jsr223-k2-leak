package repro;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.management.ObjectName;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleScriptContext;

/**
 * Evaluates a trivial Kotlin snippet through the JSR-223 script engine, starting a new script
 * state for each evaluation, and reports how much heap, and how many Kotlin compiler
 * environments, are still reachable after a full GC.
 *
 * <p>The Kotlin JSR-223 engine keeps one REPL state per {@link ScriptContext} (in the context's
 * ENGINE_SCOPE bindings). A new engine, or a new context set on an existing engine, therefore
 * starts a new state, and from Kotlin 2.4.20 each state builds its own K2 compiler environment.
 *
 * <p>Usage: {@code Jsr223StateLeak [mode] [evaluations] [reportEvery] [histogram]}
 */
public final class Jsr223StateLeak {

  private static final String ENVIRONMENT_CLASS =
      "org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment";
  private static final long MB = 1_000_000L;

  enum Mode {
    /** A new ScriptEngine for each evaluation, dropped afterwards. */
    NEW_ENGINE("a new ScriptEngine per evaluation, dropped afterwards"),
    /** One engine; a new ScriptContext (fresh bindings) before each evaluation. */
    NEW_CONTEXT("one ScriptEngine, a new ScriptContext before each evaluation"),
    /**
     * NEW_ENGINE, but before the engine is dropped the state's root Disposable is disposed (by
     * reflection: the engine offers no way to do it). Kotlin 2.4.20+ only.
     */
    NEW_ENGINE_DISPOSED("NEW_ENGINE, disposing each state's root Disposable before dropping the engine"),
    /** Control: one engine and one context, so every snippet joins the same REPL history. */
    SAME_CONTEXT("control: one ScriptEngine and one ScriptContext for every evaluation");

    final String description;

    Mode(String description) {
      this.description = description;
    }

    String cliName() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static Mode parse(String text) {
      return valueOf(text.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
  }

  public static void main(String[] args) throws Exception {
    Mode mode = args.length > 0 ? Mode.parse(args[0]) : Mode.NEW_ENGINE;
    int evaluations = args.length > 1 ? Integer.parseInt(args[1]) : 40;
    int reportEvery = args.length > 2 ? Integer.parseInt(args[2]) : 5;

    ScriptEngineManager manager = new ScriptEngineManager();
    ScriptEngine probe = newEngine(manager);
    String kotlinVersion = kotlinVersion();
    printHeader(mode, evaluations, probe, kotlinVersion);

    if (mode == Mode.NEW_ENGINE_DISPOSED && !isK2Engine(probe)) {
      System.out.println("This engine predates the K2 REPL state; " + mode.cliName() + " does not apply.");
      System.out.printf("RESULT kotlin=%s mode=%s n/a%n", kotlinVersion, mode.cliName());
      return;
    }

    ScriptEngine shared = (mode == Mode.NEW_CONTEXT || mode == Mode.SAME_CONTEXT) ? probe : null;
    probe = null;

    List<long[]> rows = new ArrayList<>();
    System.out.println("  evaluations   retained heap   live KotlinCoreEnvironment   Disposer tree entries");
    for (int i = 1; i <= evaluations; i++) {
      ScriptEngine engine;
      switch (mode) {
        case NEW_ENGINE, NEW_ENGINE_DISPOSED -> engine = newEngine(manager);
        case NEW_CONTEXT -> {
          engine = shared;
          SimpleScriptContext context = new SimpleScriptContext();
          context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
          engine.setContext(context);
        }
        default -> engine = shared;
      }

      Object result = engine.eval(i + " * 2");
      if (!(result instanceof Number number) || number.intValue() != i * 2) {
        throw new IllegalStateException("Evaluation " + i + " returned " + result);
      }

      if (mode == Mode.NEW_ENGINE_DISPOSED) {
        disposeStateRootDisposable(engine);
      }
      engine = null;

      if (i == 1 || i % reportEvery == 0 || i == evaluations) {
        long heapMb = retainedHeapMb();
        long environments = liveInstances(ENVIRONMENT_CLASS);
        long disposerEntries = disposerTreeEntries();
        rows.add(new long[] {i, heapMb, environments, disposerEntries});
        System.out.printf("  %11d   %10d MB   %26d   %21d%n", i, heapMb, environments, disposerEntries);
      }
    }

    // Slope from the first report after warm-up (the first evaluation loads the compiler) to the last,
    // or from the first evaluation when a short run has no other report before the last.
    long[] first = rows.size() > 2 ? rows.get(1) : rows.get(0);
    long[] last = rows.get(rows.size() - 1);
    double perEvaluation = last[0] > first[0] ? (double) (last[1] - first[1]) / (last[0] - first[0]) : 0.0;
    System.out.printf(
        "RESULT kotlin=%s mode=%s evaluations=%d retainedPerEvaluation=%.1fMB retainedHeap=%dMB"
            + " liveEnvironments=%d disposerTreeEntries=%d%n",
        kotlinVersion, mode.cliName(), evaluations, perEvaluation, last[1], last[2], last[3]);

    // A fourth argument, "histogram", prints what the retained heap is made of.
    if (args.length > 3 && args[3].equals("histogram")) {
      printHistogram();
    }
  }

  private static void printHistogram() throws Exception {
    String[] lines = classHistogram().split("\n");
    System.out.println();
    System.out.println("Largest classes by retained size:");
    for (int i = 0; i < Math.min(lines.length, 27); i++) {
      System.out.println(lines[i]);
    }
    System.out.println();
    System.out.println("Per-environment classes:");
    List<String> perEnvironment = List.of(
        ENVIRONMENT_CLASS,
        "org.jetbrains.kotlin.com.intellij.mock.MockProject",
        "org.jetbrains.kotlin.config.CompilerConfiguration",
        "org.jetbrains.kotlin.cli.jvm.compiler.JvmPackagePartProvider",
        "org.jetbrains.kotlin.cli.jvm.compiler.CliVirtualFileFinderFactory",
        "org.jetbrains.kotlin.cli.jvm.compiler.jarfs.FastJarVirtualFile");
    for (String className : perEnvironment) {
      System.out.printf("  %9d  %s%n", liveInstances(className), className);
    }
  }

  /**
   * Entries in IntelliJ's static Disposer tree (Disposer.ourTree.myObject2ParentNode): every
   * Disposable registered under a parent that has not been disposed yet. This static tree is the
   * GC root that keeps an undisposed state's environment reachable. -1 if it cannot be read.
   */
  private static long disposerTreeEntries() {
    try {
      Class<?> disposer = Class.forName("org.jetbrains.kotlin.com.intellij.openapi.util.Disposer");
      Object tree = disposer.getMethod("getTree").invoke(null);
      return ((java.util.Map<?, ?>) field(tree, "myObject2ParentNode")).size();
    } catch (ReflectiveOperationException | RuntimeException e) {
      return -1;
    }
  }

  private static ScriptEngine newEngine(ScriptEngineManager manager) {
    ScriptEngine engine = manager.getEngineByExtension("kts");
    if (engine == null) {
      throw new IllegalStateException("No JSR-223 engine for .kts on the classpath");
    }
    return engine;
  }

  private static boolean isK2Engine(ScriptEngine engine) {
    try {
      return method(engine, "getReplCompiler").getReturnType().getName().endsWith(".K2ReplCompiler");
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  /**
   * Disposes the root Disposable the engine created for its current state:
   * engine.replCompiler (K2ReplCompiler) .state (K2ReplCompilationState) .compilerContext
   * (SharedScriptCompilationContext) .disposable. KotlinJsr223ScriptEngineImpl.createState passes
   * it to withMessageCollectorAndDisposable(disposeOnSuccess = false), and nothing disposes it later.
   */
  private static void disposeStateRootDisposable(ScriptEngine engine) throws Exception {
    Object replCompiler = method(engine, "getReplCompiler").invoke(engine);
    Object compilationState = field(replCompiler, "state");
    Object compilerContext = field(compilationState, "compilerContext");
    Object rootDisposable = compilerContext.getClass().getMethod("getDisposable").invoke(compilerContext);

    ClassLoader loader = compilerContext.getClass().getClassLoader();
    Class<?> disposableClass = Class.forName("org.jetbrains.kotlin.com.intellij.openapi.Disposable", false, loader);
    Class<?> disposerClass = Class.forName("org.jetbrains.kotlin.com.intellij.openapi.util.Disposer", true, loader);
    disposerClass.getMethod("dispose", disposableClass).invoke(null, rootDisposable);
  }

  // replCompiler is a protected property of the engine, so look through declared methods.
  private static Method method(Object target, String name) throws NoSuchMethodException {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
      } catch (NoSuchMethodException ignored) {
        // Look in the superclass.
      }
    }
    throw new NoSuchMethodException(target.getClass().getName() + "." + name + "()");
  }

  private static Object field(Object target, String name) throws ReflectiveOperationException {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
      } catch (NoSuchFieldException ignored) {
        // Look in the superclass.
      }
    }
    throw new NoSuchFieldException(target.getClass().getName() + "." + name);
  }

  /** Heap in use after full GCs, so only reachable objects are counted. */
  private static long retainedHeapMb() throws InterruptedException {
    for (int i = 0; i < 3; i++) {
      System.gc();
      Thread.sleep(100);
    }
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / MB;
  }

  /** The JVM's own class histogram of live objects (GC.class_histogram), taken in process. */
  private static String classHistogram() throws Exception {
    return (String) ManagementFactory.getPlatformMBeanServer().invoke(
        new ObjectName("com.sun.management:type=DiagnosticCommand"),
        "gcClassHistogram",
        new Object[] {new String[0]},
        new String[] {String[].class.getName()});
  }

  /** Live instances of a class, from the JVM's class histogram. */
  private static long liveInstances(String className) throws Exception {
    for (String line : classHistogram().split("\n")) {
      String[] columns = line.trim().split("\\s+");
      if (columns.length >= 4 && columns[3].equals(className)) {
        return Long.parseLong(columns[1]);
      }
    }
    return 0;
  }

  // The engine jar's version keeps pre-release qualifiers (2.4.20-Beta1) that KotlinVersion.CURRENT drops.
  private static String kotlinVersion() {
    String prefix = "kotlin-scripting-jsr223-";
    for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
      String name = new File(path).getName();
      if (name.startsWith(prefix) && name.endsWith(".jar")) {
        return name.substring(prefix.length(), name.length() - ".jar".length());
      }
    }
    try {
      return Class.forName("kotlin.KotlinVersion").getField("CURRENT").get(null).toString();
    } catch (ReflectiveOperationException e) {
      return "unknown";
    }
  }

  private static void printHeader(Mode mode, int evaluations, ScriptEngine engine, String kotlinVersion) {
    Runtime runtime = Runtime.getRuntime();
    System.out.printf("Kotlin JSR-223 script-state retention: mode=%s (%s), %d evaluations%n",
        mode.cliName(), mode.description, evaluations);
    System.out.printf("Kotlin %s on Java %s (%s), max heap %d MB%n",
        kotlinVersion, System.getProperty("java.runtime.version"), System.getProperty("java.vendor"),
        runtime.maxMemory() / MB);
    System.out.printf("Engine %s%n  extends %s%n",
        engine.getClass().getName(), engine.getClass().getSuperclass().getName());
    String[] kotlinJars = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
        .map(path -> new File(path).getName())
        .filter(name -> name.startsWith("kotlin-"))
        .sorted()
        .toArray(String[]::new);
    System.out.printf("Kotlin jars on the classpath: %s%n%n", String.join(", ", kotlinJars));
  }

  private Jsr223StateLeak() {}
}
