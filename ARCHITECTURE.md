# Architecture & Technical Design: Spring Console

> **Embedded Kotlin REPL & Model Context Protocol (MCP) Server for Spring Boot**

This document details the internal architecture, component interactions, classloading mechanics, boot sequence, and auto-completion pipeline of `spring-console`.

---

## 1. Executive Summary & Design Principles

`spring-console` embeds an interactive Kotlin REPL and an MCP server directly into a running Spring Boot application process. It turns the live Spring `ApplicationContext` into an interactive, executable sandbox for both human developers (via an interactive JLine3 CLI) and autonomous AI agents (via JSON-RPC 2.0 over HTTP or stdio).

### Core Architectural Invariants

1. **Symmetric Execution**: AI agents and human engineers share the exact same runtime facade ([`ConsoleService`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/ConsoleService.kt)). Any evaluation, bean inspection, or schema discovery behaves identically regardless of the entry transport.
2. **Lifecycle Outside the Context**: Transports (HTTP server, stdio, terminal REPL) and reload orchestration live in a process-wide singleton ([`ConsoleRuntime`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/ConsoleRuntime.kt)) **outside** the Spring `ApplicationContext`. When a reload occurs, the Spring context is destroyed and rebuilt in-process, but transports survive to deliver the result to the caller.
3. **Direct Execution with Permanent Consequences**: Code evaluations execute directly against the live Spring context and services. There is no rollback sandbox; mutations, database writes, and service calls have immediate and permanent consequences. Transactions are managed by Spring's standard demarcation (`@Transactional` annotations on services and repositories).
4. **Compiler Thread-Affinity & Poisoning Prevention**: The runtime Kotlin scripting compiler (`KJvmReplCompilerBase`) is strictly thread-affine and cannot tolerate thread interruption. Bytecode compilation runs on a dedicated single-threaded executor (`spring-console-eval`). Thread timeouts are partitioned into two phases: compilation is never interrupted (preventing closed NIO jar channels), while user-code execution is safely interruptible.
5. **Zero Side-Effect Autocomplete**: Tab completion answers candidates purely from reflection and static metadata via [`BeanIntrospector`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/introspect/BeanIntrospector.kt). It never evaluates code or triggers proxy invocations (e.g. avoiding premature database fetches or Hibernate session initializations).

---

## 2. High-Level Subsystem Architecture

The system is structured into five distinct operational tiers:

```mermaid
graph TD
    subgraph Client Tier
        HumanCLI["Human Developer (Terminal CLI)"]
        AgentMCP["AI Agent / LLM (MCP Client)"]
    end

    subgraph Process-Wide Runtime Tier ["Process-Wide Runtime Tier (Survives Reloads)"]
        CR["ConsoleRuntime (Singleton Coordinator)"]
        REPL["ConsoleRepl (JLine3 Terminal Loop)"]
        MCP_HTTP["McpHttpTransport (Embedded Sun HTTP Server)"]
        MCP_STDIO["McpStdioTransport (System.in / System.out)"]
        MCPServer["McpServer (JSON-RPC 2.0 Dispatcher)"]
        CTools["ConsoleTools (Tool Definitions)"]
        RS["ReloadService (Reload Pipeline)"]
        CB["CompilationBridge (Gradle/Maven CLI / Tooling)"]
        Restarter["Restarter (Context Bounce Orchestrator)"]
    end

    subgraph Context-Scoped Facade Tier ["Context-Scoped Facade Tier (Recreated on Reload)"]
        CS["ConsoleService (Context Facade)"]
        CBsp["ConsoleBootstrap (Spring ApplicationListener)"]
    end

    subgraph Evaluation & Reflection Tier
        BB["BeanBinder (Proxy Unwrapping & Discovery)"]
        NS["NameSanitizer (Kotlin Keyword Escaping)"]
        ANR["ApplicationNamespaceResolver (Classpath Scanner)"]
        EH["EngineHolder (Lazy Kotlin Scripting Host)"]
        KRE["KotlinReplEngine (KJvmReplCompilerBase + Evaluator)"]
        ES["EvalService (Two-Phase Timeout & Thread Guard)"]
        OC["OutputCapture (System.out / System.err Interception)"]
        BI["BeanIntrospector (PropertyDescriptors & Methods)"]
        CSS["ContextSchemaService (Domain Entity/Repo Discovery)"]
    end

    subgraph Classloading & JVM Tier
        SCL["App / System ClassLoader (JDK, Spring Boot, Starter Libs)"]
        RCL["RestartClassLoader (Child-First over Project Output Dirs)"]
    end

    HumanCLI --> REPL
    AgentMCP --> MCP_HTTP
    AgentMCP --> MCP_STDIO

    MCP_HTTP --> MCPServer
    MCP_STDIO --> MCPServer
    MCPServer --> CTools
    CTools --> CR

    REPL --> CR
    CR --> CS
    CTools --> CS

    CR --> RS
    RS --> CB
    RS --> Restarter
    Restarter --> RCL

    CBsp --> CR
    CS --> BB
    CS --> ANR
    CS --> EH
    CS --> ES
    CS --> BI
    CS --> CSS

    ES --> EH
    ES --> OC
    EH --> KRE
    BB --> NS

    RCL -.-> SCL
```

### Core Subsystem Responsibilities

| Component | Responsibility |
|---|---|
| [`ConsoleRuntime`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/ConsoleRuntime.kt) | Process-wide singleton. Maintains current `ConsoleService`, manages transports, and coordinates reloads. |
| [`ConsoleBootstrap`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/autoconfigure/SpringConsoleAutoConfiguration.kt) | Spring `ApplicationListener`. Listens for `ApplicationReadyEvent` and `ContextClosedEvent` to attach/detach the context to `ConsoleRuntime`. |
| [`ConsoleService`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/ConsoleService.kt) | Context-scoped unified facade. Coordinates script engine instantiation, bean binding, introspection, and schema generation. |
| [`ConsoleRepl`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/repl/ConsoleRepl.kt) | JLine3-based interactive terminal loop. Wires syntax highlighting, autocomplete, history, and rendering. |
| [`McpServer`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/mcp/McpServer.kt) | Implements Model Context Protocol (JSON-RPC 2.0) with tools `eval`, `list_beans`, `inspect_bean`, `get_context_schema`, and `reload`. |
| [`KotlinReplEngine`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/engine/KotlinReplEngine.kt) | Integrates `kotlin-scripting-jvm-host`. Maintains persistent snippet state and handles bytecode generation. |
| [`BeanBinder`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/binding/BeanBinder.kt) | Scans Spring singleton registry, unwraps Spring AOP/CGLIB proxies and JDK dynamic proxies, sanitizing names for Kotlin syntax. |
| [`ApplicationNamespaceResolver`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/binding/ApplicationNamespaceResolver.kt) | Dynamically scans classes across compilation directories and packages to register automatic unambiguous imports in REPL snippets. |
| [`ConsoleCompleter`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/repl/ConsoleCompleter.kt) | Non-evaluating JLine3 tab completer for commands, bound beans, session variables, keywords, and member properties/methods. |
| [`RestartClassLoader`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/reload/RestartClassLoader.kt) | Child-first URLClassLoader loading freshly recompiled classes from disk directories while delegating third-party JARs to parent. |

---

## 3. Class Interactions: Evaluation & Query Pipeline

When a human user or an AI agent submits code to be evaluated, execution flows directly through a unified pipeline with permanent consequences.

```mermaid
sequenceDiagram
    autonumber
    actor Caller as Human (REPL) or Agent (MCP)
    participant Transport as ConsoleRepl / McpHttpTransport
    participant Runtime as ConsoleRuntime
    participant Service as ConsoleService
    participant EvalSvc as EvalService
    participant Worker as Dedicated Executor ("spring-console-eval")
    participant OutCap as OutputCapture
    participant Engine as KotlinReplEngine

    Caller->>Transport: Submit code (e.g. "todoService.findAll()")
    Transport->>Runtime: console()
    Runtime-->>Transport: active ConsoleService instance
    Transport->>Service: eval(code, timeoutMs = 5000)
    Service->>EvalSvc: eval(code, timeoutMs = 5000)

    EvalSvc->>Worker: submit Callable
    activate Worker
    Worker->>OutCap: capture { ... }
    OutCap->>OutCap: Redirect System.out & System.err

    Worker->>Engine: eval(code, onEvaluationStart callback)

    Note over Worker,Engine: Phase 1: KJvmReplCompilerBase (Untimed / Never Interrupted)
    Engine->>Engine: compile snippet to JVM bytecode

    Note over Worker,Engine: Phase 2: User Execution (Timed / Cancellable)
    Engine->>EvalSvc: onEvaluationStart() [signals CountDownLatch]
    Engine->>Engine: BasicJvmReplEvaluator.eval()
    Engine-->>Worker: SnippetOutcome.Success(resultValue)

    Worker->>Worker: HibernateProxyHelper.initializeAndUnwrap(resultValue)
    OutCap-->>Worker: captured stdout/stderr
    deactivate Worker

    Worker-->>EvalSvc: SnippetOutcome.Success(unwrappedValue)
    EvalSvc->>EvalSvc: toResult(outcome, capture, elapsed)
    EvalSvc-->>Service: EvalResult(status = SUCCESS, result, printedOutput, ...)
    Service-->>Transport: EvalResult
    Transport-->>Caller: Render formatted table / JSON payload
```

---

## 4. Bootstrapping, Classloading, and Classpaths

### 4.1 The Exploded Classpath Requirement

The runtime Kotlin scripting compiler (`KJvmReplCompilerBase`) is an embedded compiler. To compile Kotlin snippets referencing project classes and dependencies, it requires **direct filesystem access** to `.class` directories and `.jar` archives.

- **Standard Spring Boot Fat JARs**: A flat `bootJar` nests dependencies under `BOOT-INF/lib/*.jar` and compiled classes under `BOOT-INF/classes/`. The runtime compiler cannot traverse nested JAR URLs through Java NIO zip providers without extracting them, resulting in `Unable to find kotlin stdlib` or missing class errors.
- **Exploded Classpath**: When launched via [`console.sh`](file:///Users/jakob/Workspace/spring-console/scripts/console.sh) or Gradle's `writeRuntimeClasspath`, Gradle resolves all dependencies into an exploded file list passed to `java -cp`:
  - Project class directories: `build/classes/java/main`, `build/classes/kotlin/main`
  - Third-party JAR files: `~/.gradle/caches/modules-2/files-2.1/...`

### 4.2 Two-Tier Classloader Hierarchy

To support in-process hot reload without restarting the JVM, `spring-console` adopts a classloader hierarchy inspired by Spring Boot DevTools:

```mermaid
classDiagram
    class AppClassLoader {
        <<System / Parent>>
        +JDK Runtime classes
        +Spring Boot core jars
        +kotlin-compiler-embeddable.jar
        +spring-console-starter.jar
        +Third-party dependency jars
    }

    class RestartClassLoader {
        <<Child-First URLClassLoader>>
        +urls: File[] (project compiled .class directories)
        +loadClass(name, resolve): Class
        +getResource(name): URL
    }

    RestartClassLoader --|> AppClassLoader : Parent Delegation Fallback
```

1. **AppClassLoader (Parent / Stable)**: Loads the JDK, Spring Boot framework classes, third-party libraries, the Kotlin compiler, and the process-wide `ConsoleRuntime`. It is created once when the JVM boots and never discarded.
2. **RestartClassLoader (Child / Ephemeral)**: A child-first classloader pointing to the application's compiled class directories. When loading classes:
   - It checks `findLoadedClass(name)`.
   - It attempts child-first `findClass(name)` against the compiled directory URLs so newly built `.class` files always win.
   - If not found in the project directories, it delegates to `super.loadClass(name)` (AppClassLoader).

### 4.3 Sequence Diagram: Application Cold Boot & Console Initialization

```mermaid
sequenceDiagram
    autonumber
    participant Shell as console.sh / JVM
    participant Main as Application.main(args)
    participant Spring as SpringApplication.run()
    participant Ctx as ConfigurableApplicationContext
    participant AutoConfig as SpringConsoleAutoConfiguration
    participant Bootstrap as ConsoleBootstrap
    participant Runtime as ConsoleRuntime
    participant Svc as ConsoleService
    participant Binder as BeanBinder
    participant Transports as McpHttpTransport & ConsoleRepl
    participant Warmup as Warmup Thread ("spring-console-warmup")

    Shell->>Main: java -cp <exploded-classpath> com.example.Application
    activate Main
    Main->>Spring: SpringApplication.run(Application::class.java, *args)
    activate Spring

    Spring->>Ctx: refresh() (Scan beans, instantiate singletons)
    Spring->>AutoConfig: Instantiate ConsoleBootstrap Bean
    AutoConfig-->>Bootstrap: new ConsoleBootstrap(properties)
    Spring->>Ctx: publishEvent(ApplicationReadyEvent)
    
    Ctx->>Bootstrap: onApplicationEvent(ApplicationReadyEvent)
    activate Bootstrap

    Note over Bootstrap: Inspects mainClass, args, and java.class.path directory entries
    Bootstrap->>Bootstrap: restartContext = RestartContext(mainClassName, args, classpathDirs, parentLoader)

    Bootstrap->>Svc: new ConsoleService(context, properties)
    activate Svc
    Svc->>Binder: boundBeans() [Scan singletons, unwrap AOP/JDK proxies, sanitize names]
    Binder-->>Svc: List<BoundBean>
    deactivate Svc

    Bootstrap->>Runtime: attach(consoleService, restartContext)
    activate Runtime

    Runtime->>Runtime: startReloadMachinery(properties, restartContext)
    Note over Runtime: Prepares CompilationBridge and Restarter

    Runtime->>Transports: startTransports(properties)
    activate Transports
    Transports->>Transports: McpHttpTransport.start() [Bind HTTP port :8085]
    Transports->>Transports: ConsoleRepl.startIfInteractive() [Attach JLine3 terminal thread]
    deactivate Transports

    Runtime->>Svc: warmUpAsync()
    activate Svc
    Svc->>Warmup: start daemon thread "spring-console-warmup"
    activate Warmup
    Note over Warmup: Initializes KotlinReplEngine on eval executor without delaying startup
    Warmup-->>Svc: Complete compiler warmup
    deactivate Warmup
    deactivate Svc

    deactivate Runtime
    deactivate Bootstrap
    deactivate Spring
    Main-->>Shell: Application Ready (Terminal Prompt displayed & MCP live)
    deactivate Main
```

---

## 5. Autocomplete Subsystem & Tab Completion

The REPL autocomplete feature is powered by [`ConsoleCompleter`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/repl/ConsoleCompleter.kt) (implementing JLine3's `Completer` interface). It operates inside the terminal event loop and provides intelligent completion for:

1. **Commands**: `:help`, `:beans`, `:inspect`, `:schema`, `:reload`, `:quit`.
2. **Command Arguments**: Bean names for `:inspect <Tab>`.
3. **Root Identifiers**: Bound bean REPL names (e.g. `todoService`), session variables (`val`/`var`/`fun` declared in previous snippets), and Kotlin keywords.
4. **Member Expressions (`receiver.member`)**:
   - `context.<Tab>`: Reflective methods and synthetic JavaBean properties of Spring's `ApplicationContext`.
   - `bean.<Tab>`: Introspected properties and public declared methods of the target bean.
   - `variable.<Tab>`: Methods and properties of previously declared session variables.

### 5.1 Safety Guarantees

- **No Code Evaluation**: Tab completion never invokes user code or getters. It queries the cached [`BeanDetails`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/api/Results.kt) model produced by reflection in [`BeanIntrospector`](file:///Users/jakob/Workspace/spring-console/spring-console-starter/src/main/kotlin/io/github/springconsole/introspect/BeanIntrospector.kt).
- **Never Throws**: The entire completion pipeline is wrapped in `try-catch` blocks catching both `Exception` and `LinkageError`, ensuring terminal input never locks up even if optional library classes are missing.
- **Immediate Dot Completion**: A custom JLine widget (`dot-complete`) is bound to the `.` key. Whenever the user finishes typing a known bean or variable name and hits `.`, candidate options are presented instantly without requiring an extra `<Tab>` keystroke.

### 5.2 Sequence Diagram: Autocomplete Workflow

```mermaid
sequenceDiagram
    autonumber
    actor Developer as Human Developer
    participant Terminal as JLine3 Terminal
    participant Reader as LineReader
    participant Completer as ConsoleCompleter
    participant Runtime as ConsoleRuntime
    participant Svc as ConsoleService
    participant Introspector as BeanIntrospector
    participant Resolver as ApplicationNamespaceResolver

    Developer->>Terminal: Types "todoService.f" and presses <Tab>
    Terminal->>Reader: Process key event (TAB)
    Reader->>Completer: complete(reader, line, candidates)
    activate Completer

    Completer->>Completer: completeSafely(line, candidates)
    Completer->>Completer: buffer = line.line(), cursor = line.cursor()

    alt Starts with ":" (REPL Command)
        Completer->>Completer: completeCommand(beforeCursor, candidates)
        Note over Completer: Matches :help, :beans, :inspect, etc.
    else No Dot (Root Symbol)
        Completer->>Completer: completeRoot(prefix, candidates)
        Completer->>Completer: safeBeanNames() + sessionVariables + Kotlin keywords
    else Contains Dot (Member Access: "todoService.f")
        Completer->>Completer: dot = lastDotOutsideQuotes(wordBeforeCursor)
        Completer->>Completer: receiver = "todoService", prefix = "f"
        
        alt receiver == "context"
            Completer->>Completer: contextCandidates(receiverPrefix)
            Note over Completer: Inspects ApplicationContext.class methods & getters
        else Regular Bean or Variable Receiver
            Completer->>Completer: safeInspect("todoService")
            Completer->>Svc: inspectBean("todoService")
            activate Svc
            Svc->>Introspector: inspectBean("todoService")
            activate Introspector
            
            Introspector->>Introspector: Locate BoundBean in boundBeansSupplier()
            Introspector->>Introspector: publicDeclaredMethods(bean.type)
            Introspector->>Introspector: properties(bean.type) via BeanUtils.getPropertyDescriptors
            Note over Introspector: Prunes synthetic methods, Object methods (equals, wait, etc.), and '$'
            Introspector-->>Svc: BeanDetails(name, methods, properties, interfaces)
            deactivate Introspector
            Svc-->>Completer: BeanDetails
            deactivate Svc

            loop For each matching Property
                Completer->>Completer: add Candidate(value = "todoService.fieldName", displ = fieldName, descr = type)
            end

            loop For each matching Method
                Note over Completer: Format signature: "findAll()" or "findById(id: Long)"
                Completer->>Completer: add Candidate(value = "todoService.find...", suffix = ")", complete = !hasParams)
            end
        end
    end

    Completer-->>Reader: candidates populated
    deactivate Completer

    Reader->>Terminal: Render completion candidates menu / inline insertion
    Terminal-->>Developer: Displays matching methods: [findAll(), findById(id: Long)]
```

---

## 6. In-Process Hot Reload Pipeline

The `:reload` command and the `reload(recompile: Boolean)` MCP tool allow both developers and AI agents to update source code, recompile, and bounce the Spring context without restarting the JVM:

```mermaid
sequenceDiagram
    autonumber
    actor Caller as Developer (:reload) or Agent (reload tool)
    participant Runtime as ConsoleRuntime
    participant ReloadSvc as ReloadService
    participant Bridge as CompilationBridge
    participant Restarter as Restarter
    participant OldCtx as Old ApplicationContext
    participant RCL as New RestartClassLoader
    participant BootThread as Restart Thread ("spring-console-restart")
    participant NewCtx as New ApplicationContext
    participant Bootstrap as ConsoleBootstrap

    Caller->>Runtime: reload(recompile = true)
    Runtime->>ReloadSvc: reload(recompile = true)
    activate ReloadSvc

    opt recompile == true
        ReloadSvc->>Bridge: compile()
        activate Bridge
        Bridge->>Bridge: Execute "./gradlew classes" or "./mvnw compile"
        Bridge-->>ReloadSvc: CompilationOutcome.Success
        deactivate Bridge
    end

    ReloadSvc->>Restarter: restart(timeoutMs = 30000)
    activate Restarter

    Restarter->>Runtime: beginRestart()
    Note over Runtime: Sets restarting = true, creates pendingAttach CompletableFuture

    Restarter->>OldCtx: close()
    Note over OldCtx: Destroys singletons, releases DB connections, triggers ContextClosedEvent

    Restarter->>RCL: new RestartClassLoader(classpathDirs, parentLoader)
    Note over RCL: Points to freshly recompiled .class files on disk

    Restarter->>BootThread: Spawn thread with contextClassLoader = RCL
    activate BootThread
    BootThread->>BootThread: Invoke mainClassName.main(args) on RCL
    BootThread->>NewCtx: SpringApplication.run() boots fresh context
    NewCtx->>Bootstrap: publish ApplicationReadyEvent
    Bootstrap->>Runtime: attach(newConsoleService, restartContext)
    deactivate BootThread

    Note over Runtime: Completes pendingAttach future, swaps current ConsoleService
    Runtime-->>Restarter: pendingAttach completed
    Restarter->>Runtime: endRestart()

    Restarter-->>ReloadSvc: Outcome.Success(durationMs)
    deactivate Restarter
    ReloadSvc-->>Runtime: ReloadResult(status = SUCCESS)
    deactivate ReloadSvc
    Runtime-->>Caller: Reload successful in 842ms. Transports uninterrupted.
```

---

## 7. Security & Execution Boundaries
 
1. **Permanent Consequences & Standard Transaction Demarcation**:
   - Every execution run via `eval` executes directly against the target Spring Boot application with permanent consequences.
   - There is no transactional rollback sandbox; mutations, database updates, and service methods run and commit as configured in the host application.
   - Service-layer transaction boundaries (e.g. `@Transactional`) operate normally via their Spring proxies.
2. **Timeout Enforcement**:
   - Snippet compilation phase has a high watchdog timeout (120s) and is **never interrupted** to preserve the JVM's classpath JAR file handles.
   - User evaluation phase enforces the configured timeout (`defaultTimeoutMs = 5000ms`), interrupting runaway loops safely.
   - If a thread ignores interruption, the executor is abandoned, a fresh evaluation thread is spawned, and the REPL session state is cleared to avoid compiler IR poisoning (`psi2ir` corruption).
3. **Identifier Sanitization**:
   - `NameSanitizer` cleans bean identifiers containing characters like `-`, `/`, or `.`.
   - Kotlin reserved keywords (e.g. `val`, `fun`, `class`, `package`) are escaped with backticks or suffixed deterministically (`class_`), preventing syntax errors during scripting compilation.
