# JVM flags we run with (JDK 25): documentation and measured effect

The set below is carried by MAT's `C:\Z\mat\MemoryAnalyzer.ini`, and it is a reasonable default for
any JVM that must hold large data in memory. Every flag was checked on **JDK 25.0.5**: each one is
accepted without a single warning, and `-XX:+PrintFlagsFinal` reports them as `{command line}`.

## What each flag does

| Flag | Effect | Official documentation |
|---|---|---|
| `-Xmx26G` | Maximum heap. The ergonomic default on this machine is 8 514 437 120 B (7.93 GiB), i.e. the flag triples it; 26 GB also stays under the 32 GB compressed-oops limit, so `UseCompressedOops` remains `true` | [java(1) `-Xmx`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html) |
| `-XX:+UseCompactObjectHeaders` | Object header drops from 12/16 B to 8 B. **Product feature in JDK 25** (no `UnlockExperimentalVMOptions` needed), default `false`. JEP cites 22% less heap and 8% less CPU on SPECjbb2015, 15% fewer collections | [JEP 519](https://openjdk.org/jeps/519), [java(1)](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html) |
| `--sun-misc-unsafe-memory-access=allow` | Lets code use `sun.misc.Unsafe` memory-access methods with **no** runtime warning. Modes: `allow`, `warn`, `debug`, `deny`; the default moves to `warn` (phase 2), then `deny`, then the methods disappear | [JEP 471](https://openjdk.org/jeps/471) |
| `--enable-native-access=ALL-UNNAMED` | Grants native access (JNI, restricted methods) to class-path code. Without it the JVM warns, and in a later release refuses | [java(1) `--enable-native-access`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html), [JEP 472](https://openjdk.org/jeps/472) |
| `-Djava.net.preferIPv4Stack=true` | IPv4-only sockets instead of dual-stack IPv6. Read once at startup | [Networking Properties](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/doc-files/net-properties.html) |
| `-Dfile.encoding=UTF-8` | Default charset. UTF-8 has been the default since JDK 18, so this is the *supported no-op* that keeps older command lines honest | [JEP 400](https://openjdk.org/jeps/400) |
| `-XX:+UseStringDeduplication` | G1 deduplicates equal `String` bodies, trading a little CPU for heap. Default `false`; requires G1 | [G1 GC guide](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-g1-garbage-collector1.html) |
| `-XX:+UseG1GC` | Selects G1 explicitly — **already the default**; the flag only states intent | [G1 GC guide](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-g1-garbage-collector1.html) |
| `-XX:+DoEscapeAnalysis` | C2 escape analysis — **already `true` by default**; the flag only states intent | [java(1) advanced JIT](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html) |
| `-XX:CompileCommand=inline,java/lang/String.charAt` | Asks the JIT to inline `String.charAt` (hot in parsers and in MAT) | [java(1) `-XX:CompileCommand`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html) |
| `--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED` | Exposes the JDK-internal ASM package to class-path code; Eclipse/OSGi needs it | [java(1) `--add-exports`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html) |

## Measured, not assumed

`java -XX:+PrintFlagsFinal -version` (defaults) versus the same command with our flags:

| Flag | default | with our set |
|---|---|---|
| `MaxHeapSize` | 8 514 437 120 (ergonomic) | **27 917 287 424** `{command line}` |
| `UseCompactObjectHeaders` | `false` | **`true`** `{command line}` |
| `UseStringDeduplication` | `false` | **`true`** `{command line}` |
| `UseG1GC` | `true` (ergonomic) | `true` `{command line}` |
| `DoEscapeAnalysis` | `true` (C2 product default) | `true` `{command line}` |

Two of the eleven are therefore redundant — keep them only if you want the intent visible in the file.

## The MAT trap this set lives in

Unpacking a new MAT build **replaces `MemoryAnalyzer.ini` with the shipped default**, which is
`-Xmx1024m` plus no flags at all — a 26x smaller heap, silently. After any product update:

1. keep the launcher lines from the **new** file (`-startup`, `--launcher.library` change between
   builds), and
2. restore the `-vmargs` block from a backup or from this table.

Verify that the JVM really got them — a temporary copy of the ini with one diagnostic flag, run
once, and read the launcher's own output:

```powershell
$ini = 'C:\Z\mat\MemoryAnalyzer.ini'
$verify = Join-Path $env:TEMP 'MemoryAnalyzer-verify.ini'
[IO.File]::WriteAllText($verify,
	(Get-Content -LiteralPath $ini -Raw) -replace '-vmargs', "-vmargs`r`n-XX:+PrintFlagsFinal")
& 'C:\Z\mat\MemoryAnalyzerc.exe' --launcher.ini $verify -consoleLog -nosplash `
	-application org.eclipse.mat.api.parse $dump org.eclipse.mat.api:suspects 2>&1 |
	Select-String 'MaxHeapSize|UseCompactObjectHeaders|UseStringDeduplication'
Remove-Item $verify
```

Verified output on 2026-11-17:

```
size_t MaxHeapSize = 27917287424 {product} {command line}
bool UseCompactObjectHeaders = true {product lp64_product} {command line}
bool UseStringDeduplication = true {product} {command line}
```

## Windows/pwsh note

These are JVM options, not PowerShell switches: pass them inside the JVM argument list (an `.ini`
`-vmargs` block, `JDK_JAVA_OPTIONS`, or directly after `java`). In PowerShell `>` inside an argument
is redirection, so a flag like `-XX:CompileCommand=inline,java/lang/String.charAt` is fine but any
option containing `>` must be quoted.
