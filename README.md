## Mod by AL3XMABAH0
* Use % for chat CMDS
* Check Releases for Potassium jars and srcs.
* Only runs with Forge 1.8.9
* Don't ask for an injectable version please.

## Building

Requirements: **JDK 8** and **Gradle 4.10.x**. ForgeGradle 2.1 does not run on newer Gradle,
and the source targets Java 8. No Gradle wrapper is checked in, so use a local Gradle 4.10.3.

Point `JAVA_HOME` at a JDK 8 (Gradle reads `JAVA_HOME`, not `java` on the PATH), then run:

```bash
export JAVA_HOME="/path/to/jdk1.8.0_xxx"        # bash
gradle build
```

On Windows `cmd` set it with `set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_151` first.

Output: `build/libs/potassium-0.2.jar` — the reobfuscated jar, ready to drop into
`.minecraft/mods` on Forge 1.8.9.

The first build needs network access: ForgeGradle downloads the 1.8.9 userdev and the
`stable_22` MCP mappings.

`gradle compileJava` only compiles; `gradle build` also runs `reobfJar`, which is what makes the
jar loadable by Forge.
