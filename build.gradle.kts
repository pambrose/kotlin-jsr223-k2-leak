plugins {
  java
  application
}

// The Kotlin version under test. The harness is plain Java, so the only Kotlin on the runtime
// classpath is this version's JSR-223 engine and what it brings in (stdlib, compiler, scripting).
val kotlinVersion = providers.gradleProperty("kotlinVersion").getOrElse("2.4.21")

dependencies {
  runtimeOnly("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinVersion")
}

java {
  // -PjavaVersion=17|21|25 runs the harness on that JDK (Gradle finds installed JDKs).
  // Without it, the harness runs on the JDK that runs Gradle.
  providers.gradleProperty("javaVersion").orNull?.let {
    toolchain.languageVersion = JavaLanguageVersion.of(it)
  }
}

tasks.compileJava {
  options.release = 17
}

application {
  mainClass = "repro.Jsr223StateLeak"
}

tasks.named<JavaExec>("run") {
  // -Pheap=512m shows the retention ending in an OutOfMemoryError.
  maxHeapSize = providers.gradleProperty("heap").getOrElse("4g")
}

tasks.register<JavaExec>("minimal") {
  group = "application"
  description = "Runs repro.Minimal: 1,000 evaluations, each on a new engine, in a 512 MB heap."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass = "repro.Minimal"
  maxHeapSize = providers.gradleProperty("heap").getOrElse("512m")
  javaLauncher = javaToolchains.launcherFor(java.toolchain)
}
