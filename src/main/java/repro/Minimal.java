package repro;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/** The smallest reproduction: a new engine per evaluation, dropped right after it. */
public final class Minimal {
  public static void main(String[] args) throws Exception {
    ScriptEngineManager manager = new ScriptEngineManager();
    for (int i = 1; i <= 1_000; i++) {
      ScriptEngine engine = manager.getEngineByExtension("kts");
      engine.eval(i + " * 2");
      if (i % 10 == 0) {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_000_000;
        System.out.println(i + " evaluations: " + usedMb + " MB in use after GC");
      }
    }
  }

  private Minimal() {}
}
