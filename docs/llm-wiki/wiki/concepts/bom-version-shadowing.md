---
type: concept
project: programmers-tracker
tags: [gradle, dependencies, spring-boot, debugging-pattern]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-05-design-review-and-stack-upgrade.md]
---

# BOM Version Shadowing — the dependency you declared is not the one that runs

## The trap

Spring Boot's dependency management pins the versions of libraries it knows about, including
ones you declared yourself with an explicit version. Your build file can say 1.11.0 while the
runtime classpath carries 1.8.1, and **nothing warns you** — the build succeeds, the tests
pass, and the failure appears only when a method that exists in one version and not the other
is actually called.

## Measured twice, on the same dependency

**2026-08-04, Spring Boot 3.5.16.** The first real connection to Programmers died instantly:

```
NoSuchMethodError: kotlinx.coroutines.BuildersKt.runBlockingK$default
  at io.ktor.util.CryptoKt__CryptoJvmKt.generateNonceBlocking
```

`build.gradle.kts` declared `kotlinx-coroutines-core:1.11.0`. `gradle dependencyInsight`
showed what actually resolved:

```
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1
  Selection reasons: Selected by rule, By constraint
\--- kotlinx-coroutines-bom:1.8.1  ← pulled in by Spring's BOM
```

**2026-08-05, Spring Boot 4.1.0.** Before trusting the upgrade, the new BOM was read
directly: `kotlin-coroutines.version=1.10.2` and `kotlin.version=2.3.21` (against a 2.4.10
compiler plugin). Still below what Ktor 3.5.2 needs — the same crash would have returned.

## The fix, and what does *not* fix it

```kotlin
// Overriding the BOM's own property is what actually changes the resolved version.
extra["kotlin-coroutines.version"] = libs.versions.coroutines.get()
extra["kotlin.version"] = libs.versions.kotlin.get()
```

A **version catalog does not solve this.** `gradle/libs.versions.toml` controls the versions
*we declare*; the BOM controls versions it *manages transitively*. The two mechanisms coexist,
and only one of them wins on the runtime classpath. Consolidating versions into a catalog can
even make it feel solved while the shadowing continues untouched.

## Why the symptom is delayed

The mismatch is a **linkage** error, not a resolution error. Compilation uses the API; the
missing method is discovered when the classpath is loaded and the call executes. That is why
gates can be entirely green while the feature is broken — and why the constitution's
"mock-only completion is forbidden" rule caught it: only a real connection surfaced it, and it
surfaced within milliseconds of connecting.

## Standing rule

Whenever a framework BOM is upgraded, re-verify every shared dependency with
`gradle dependencyInsight --dependency <artifact> --configuration runtimeClasspath` and
re-run the live path. This is now part of the version policy in
[[decisions/2026-08-05-backend-stack]]. Related: [[decisions/2026-08-04-ktor-websocket-client]].
