# AGENTS.md — Developer & AI Agent Guide for spring-console

This document provides architectural context, structural guidelines, and operational workflows for AI agents (such as Gemini) and human contributors working on `spring-console`.

---

## 1. Project Overview & Core Mission

`spring-console` embeds an interactive **Kotlin REPL** and **Model Context Protocol (MCP) server** directly inside running Spring Boot applications.

### Core Value Proposition (Symmetric Execution)
- **Symmetrical Design**: AI agents (via MCP over HTTP/stdio) and human engineers (via JLine3 terminal) share the exact same execution engine, bean bindings, and introspection tools through a unified `ConsoleService`.
- **Live ApplicationContext as an Executable Sandbox**: Singletons in the Spring context are bound directly as typed variables in a Kotlin scripting environment.
- **Permanent Consequences**: Evaluations execute directly against the live application context; all mutations and database changes are persisted permanently.
- **In-Process Hot Reload**: Allows agents and developers to edit source files, trigger incremental recompilation via the build tool, discard the child classloader, and restart the Spring context in-process without restarting the JVM.

---

## 2. Repository Structure & Modules

The repository is organized as a multi-module Gradle project:

```
spring-console/
├── build.gradle.kts             # Root Gradle build configuration
├── settings.gradle.kts          # Subproject inclusion (:spring-console-starter, :examples:todo-app)
├── gradle/libs.versions.toml    # Version catalog (Kotlin, Spring Boot, JLine, etc.)
├── spec.md                      # Product & Technical Requirements Document
├── spring-console-starter/      # Main library module
│   └── src/
│       ├── main/kotlin/io/github/springconsole/
│       │   ├── ConsoleRuntime.kt            # Process-wide runtime & transport coordinator
│       │   ├── ConsoleService.kt            # Context-scoped facade for eval, inspection, schema
│       │   ├── SpringConsoleProperties.kt   # Configuration properties (spring-console.*)
│       │   ├── api/                         # Result models (EvalResult, BeanDetails, ReloadResult, etc.)
│       │   ├── autoconfigure/               # Spring Boot auto-configuration & bootstrap listener
│       │   ├── binding/                     # Bean reflection, proxy unwrapping, identifier sanitizer
│       │   ├── engine/                      # Kotlin scripting host, eval service, tx wrapper, stack pruner
│       │   ├── introspect/                  # Bean introspection & domain schema generation
│       │   ├── mcp/                         # MCP server, JSON-RPC 2.0 dispatch, HTTP/stdio transports
│       │   ├── reload/                      # Build bridge, RestartClassLoader, in-process Restarter
│       │   └── repl/                        # JLine3 terminal REPL, lexer, highlighter, completer, renderer
│       └── test/kotlin/                     # Extensive test suite covering all subsystems
└── examples/todo-app/           # Reference demo application
    ├── console.sh               # Launch script attaching an interactive REPL on an exploded classpath
    ├── build.gradle.kts         # Demo app build setup & runtime classpath export task
    ├── src/main/java/           # Spring Boot demo (Todo entity, repository, service, controller)
    └── frontend/                # React + Vite frontend for the demo app
```

---

## 3. Key Subsystems & Architecture

```
       Human Terminal (CLI)                      AI Agent (MCP Client)
                │                                         │
                ▼                                         ▼
     ┌──────────────────────┐                  ┌──────────────────────┐
     │ JLine3 REPL          │                  │ MCP Server           │
     │ completion, syntax,  │                  │ JSON-RPC 2.0         │
     │ colorized rendering  │                  │ (HTTP :8085 / stdio) │
     └──────────┬───────────┘                  └──────────┬───────────┘
                └───────────────┬─────────────────────────┘
                                ▼
                   ┌────────────────────────┐
                   │ ConsoleService         │  ← Symmetric interface facade
                   │ eval, beans, reload... │
                   └────────────┬───────────┘
                                ▼
     ┌────────────────────────────────────────────────────────┐
     │ KotlinReplEngine (kotlin-scripting-jvm-host)           │
     │ BeanBinder · StackTracePruner                          │
     │ CompilationBridge · RestartClassLoader · Restarter    │
     └────────────────────────────────────────────────────────┘
```

### 3.1 Lifecycle & The Reload Boundary (`ConsoleRuntime`)
- `ConsoleRuntime` is a process-wide singleton that coordinator transports (MCP HTTP/stdio, JLine REPL) and reload orchestration.
- **CRITICAL INVARIANT**: Transports and reload services live **outside** the Spring `ApplicationContext`. When `:reload` or `reload` tool is invoked, the `ApplicationContext` is closed and recreated. Transports must survive the restart to deliver the response to the caller.
- `ConsoleService` is instantiated **per ApplicationContext** and detached upon context close.

### 3.2 Kotlin Scripting Engine (`engine/`)
- Built on `kotlin-scripting-jvm-host`, `KJvmReplCompilerBase`, and `BasicJvmReplEvaluator`.
- **Thread-Affinity & Poisoning Prevention**:
  - The Kotlin scripting compiler is thread-affine. Warming up or running compilations across different threads causes `psi2ir` compiler internal errors. All compiler calls run on a dedicated single-threaded executor (`spring-console-eval`).
  - **Never interrupt compilation**: Thread interruption during bytecode compilation closes shared classpath JAR channels (`ClosedByInterruptException`), permanently poisoning compilation for the JVM. The engine enforces a two-phase timeout: compilation is never interrupted; only the user evaluation phase is cancellable.
- State is preserved across snippets within a session (declared variables and functions remain available) until a reload or timeout occurs.

### 3.3 Bean Binding & Proxy Unwrapping (`binding/`)
- Instantiated singletons are discovered from `ApplicationContext.beanFactory` without triggering premature initialization of lazy/prototype beans.
- Infrastructure and internal framework beans are excluded by default (`spring-console.include-infrastructure-beans=false`).
- **Proxy Unwrapping**:
  - JDK dynamic proxies expose interfaces (typed to the most specific non-framework interface, e.g., `TodoRepository`).
  - CGLIB proxies unwrap to the target class (`AopUtils.getTargetClass()`).
  - The live bean instance passed to the script maintains its proxy wrapper so `@Transactional` and other Spring AOP interceptors function normally.
- `NameSanitizer` cleans bean names into valid Kotlin identifiers, safely escaping reserved keywords (`class` → `class_`) and resolving collisions deterministically.

### 3.4 MCP Server & Tools (`mcp/`)
Exposes JSON-RPC 2.0 tools for AI agents:
1. `eval(code: String, timeoutMs: Long = 5000)`: Runs Kotlin snippets against the context with permanent consequences.
2. `list_beans(packageFilter: String?, includeProxies: Boolean = false)`: Discovers bound beans and their types.
3. `inspect_bean(beanName: String)`: Deep introspection into a bean's methods, properties, and runtime interfaces.
4. `reload(recompile: Boolean = true)`: Recompiles code via the build tool and refreshes the context.
5. `get_context_schema()`: Dumps JPA entities, Spring Data repositories, and services.

### 3.5 Terminal REPL (`repl/`)
- Built on JLine3 with custom `KotlinLexer` and `KotlinSyntaxHighlighter`.
- `ConsoleCompleter`: Provides tab-completion for `:commands`, beans, declared variables, and reflective method/property names without evaluating code.
- `ResultRenderer`: Renders collections as formatted tables, records as key-value trees, and Spring Data pages with metadata.

---

## 4. Development & Testing Commands

### Prerequisites
- **Java**: JDK 17+
- **Kotlin**: 2.2.20
- **Spring Boot**: 3.5.6
- **Node.js**: 20.19+ (only required for demo frontend)

### Gradle Commands
```bash
# Build the entire project and run all tests
./gradlew build

# Run starter library tests
./gradlew :spring-console-starter:test

# Run example application standard tests
./gradlew :examples:todo-app:test

# Run example application console integration tests
./gradlew :examples:todo-app:consoleTest

# Run a specific test class
./gradlew :spring-console-starter:test --tests "io.github.springconsole.engine.KotlinReplEngineTest"
```

### Running the Demo Application
The REPL requires an interactive terminal and an **exploded classpath** (because flat Spring Boot executable JARs nest `kotlin-stdlib` inside `BOOT-INF/lib`, which the runtime Kotlin compiler cannot read as disk files).

```bash
# Recommended: Run console from the target project directory
cd examples/todo-app && ../../scripts/console.sh

# Or point console.sh to the target project directory from anywhere
scripts/console.sh examples/todo-app

# Or run headless / console-only without web server:
scripts/console.sh examples/todo-app --spring.main.web-application-type=none --spring-console.mcp.enabled=false
```

### Frontend Demo (Optional)
```bash
cd examples/todo-app/frontend
npm install
npm test
npm run build
```

---

## 5. Architectural Invariants & Agent Guidelines

When modifying or extending this codebase, adhere strictly to these rules:

1. **Symmetric Behavior**: Any feature added to the terminal REPL must be exposed or matched in `ConsoleService` and MCP tools where applicable.
2. **Never Break Compiler Isolation**:
   - Keep script compilation on the designated single-threaded evaluation executor.
   - Do not allow thread interruption during the compilation phase.
3. **Handle Proxy Resolution Accurately**: Never cast JDK dynamic proxies directly to concrete implementation classes in `BeanBinder`. Always inspect `java.lang.reflect.Proxy.isProxyClass` and extract appropriate domain interfaces.
4. **Direct Execution with Permanent Consequences**: In `eval`, code executes directly against the live context without artificial rollback wrapping, ensuring all mutations have real and permanent consequences.
5. **Clean Separation of Transports**: Keep `McpServer`, `McpHttpTransport`, `McpStdioTransport`, and `ConsoleRepl` decoupled from Spring bean lifecycles; they must route via `ConsoleRuntime`.
6. **Token-Efficient Structured Output**:
   - `StackTracePruner` must prune Spring reflection delegates and interceptors to keep stack traces minimal and high-signal for LLMs.
   - `EvalResult.rawValue` must stay annotated with `@get:JsonIgnore` so that MCP payloads remain clean and deterministic JSON.
7. **Opinionated Zero-Configuration & Launcher Convention**:
   - Always strive for opinionated defaults. A target Spring Boot application adopting `spring-console` must never require custom Gradle tasks (e.g., no `writeRuntimeClasspath`), custom properties, or boilerplate configuration.
   - When `scripts/console.sh` is launched without a parameter, it strictly expects the Spring Boot application to be in the folder it is launched from (`$PWD`).
   - Neither `console.sh` nor any core console classes may ever hardcode references to specific demo or example applications.
