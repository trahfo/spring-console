Product & Technical Requirements Document: Spring Boot Kotlin Agent Console

1. Executive Summary & Core USP

The Spring Boot Kotlin Agent Console provides an interactive, Kotlin-driven REPL runtime embedded in Spring Boot applications. Designed symmetrically for human developers and autonomous AI agents, it exposes the running ApplicationContext as an executable sandbox.

The Core USP: The console treats AI agents as first-class citizens by exposing a native Model Context Protocol (MCP) server alongside the local CLI. Agents do not merely edit static code; they can actively introspect beans, execute transactional dry-run scripts, verify runtime hypotheses, trigger recompilations, and hot reload the Spring context within a single automated loop.

2. System Architecture

+-------------------------------------------------------------------------+
|                              Clients                                    |
|   [Human Terminal (CLI)]               [AI Agent (MCP Client / LLM)]    |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                        Console Interface Layer                          |
|   - Terminal REPL (JLine3 / ANSI)                                       |
|   - Embedded MCP Server (stdio / HTTP JSON-RPC 2.0)                     |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                    Kotlin Script Execution Engine                       |
|   - kotlin-scripting-jvm-host (JvmReplCompiler, JvmReplEvaluator)       |
|   - Dynamic Bean Scope Binder (providedProperties / implicitReceivers)  |
|   - Execution Sandbox (Transactional Rollback Wrapping)                 |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                     Spring Runtime & Reload Layer                       |
|   - Dynamic RestartClassLoader (Spring Boot DevTools Pattern)           |
|   - Compilation Bridge (Incremental Gradle/Maven daemon or K2 Compiler) |
|   - Bean Lifecycle Manager (ApplicationContext close & re-bootstrap)    |
+-------------------------------------------------------------------------+


3. Agent-First Interface (MCP Tool Specifications)

The console implements an MCP server endpoint. Agents interact with the runtime strictly through structured tool calls returning deterministic JSON payloads.

Exposed MCP Tools

Tool Name	Parameters	Return Type	Description
eval	code: String, rollback: Boolean = false, timeoutMs: Long = 5000	EvalResult	Executes Kotlin code in the context of the running application.
list_beans	packageFilter: String?, includeProxies: Boolean = false	List	Lists all beans registered in the context with their resolved types.
inspect_bean	beanName: String	BeanDetails	Inspects bean methods, properties, and runtime proxy targets.
reload	recompile: Boolean = true	ReloadResult	Compiles modified code, bounces the context, and re-binds the REPL.
get_context_schema	None	ContextSchema	Dumps current domain entities, repository interfaces, and services.

Schema Payloads

// EvalResult Schema
{
  "status": "SUCCESS" | "COMPILATION_ERROR" | "RUNTIME_EXCEPTION" | "TIMEOUT",
  "result": "Any serialized object / string representation",
  "printedOutput": "stdout/stderr captured during evaluation",
  "executionTimeMs": 42,
  "transactionRolledBack": true,
  "compilationErrors": [
    {
      "line": 12,
      "column": 4,
      "message": "Unresolved reference: findByEmail"
    }
  ],
  "exception": {
    "type": "org.springframework.dao.DataIntegrityViolationException",
    "message": "Duplicate entry 'agent@test.com' for key 'users.email'",
    "stackTrace": ["...first 10 lines..."]
  }
}


4. Functional Requirements

FR-1: Automated Classpath & Bean Injection

⚬ FR-1.1: The runtime must bind all beans from ApplicationContext directly into the Kotlin Scripting evaluation scope via providedProperties.
⚬ FR-1.2: Spring proxies (CGLIB, JDK dynamic proxies) must be unwrapped to expose the primary class or interface types using AopUtils.getTargetClass(), ensuring accurate auto-completion and method invocation.
⚬ FR-1.3: Beans sharing names with Kotlin reserved words (e.g., val, fun, class) or containing symbols must be sanitized or aliased deterministically.

FR-2: Safe "Try Out" Execution (Transactional Sandbox)

⚬ FR-2.1: The engine must support a rollback: Boolean parameter (defaulting to true when invoked by an agent unless explicitly overridden).
⚬ FR-2.2: When rollback = true, the script wrapper must execute within a newly created Spring TransactionStatus via PlatformTransactionManager and unconditionally trigger a rollback upon completion.
⚬ FR-2.3: Any database mutations, dirty context states, or staged entity updates must revert completely, leaving the application state clean.

FR-3: Code Refresh & Reload Pipeline (reload!)

⚬ FR-3.1: Triggering a reload must initiate incremental compilation of mutated Java/Kotlin source files via the local build daemon (Gradle Tooling API or Maven build agent).
⚬ FR-3.2: If compilation succeeds, the runtime must close the existing ConfigurableApplicationContext without terminating the process.
⚬ FR-3.3: The child RestartClassLoader must be discarded and replaced with a fresh instance holding the newly compiled .class files.
⚬ FR-3.4: A new ApplicationContext must be instantiated, re-scanned, and re-bound to the REPL.
⚬ FR-3.5: Stale evaluator snippets must be purged to prevent ClassCastException caused by references to old class loader types.

FR-4: Structured Error Reporting for Agent Self-Correction

⚬ FR-4.1: Compiler errors must never return raw unstructured logs. They must provide line-accurate, column-accurate structured JSON.
⚬ FR-4.2: Runtime exceptions must strip excessive Spring framework internal stack frames (e.g., reflection delegates, interceptors) to minimize token consumption while retaining root-cause domain frames.

5. Non-Functional Requirements

⚬ Cold Boot Performance: REPL and MCP server must become available within < 2.5 seconds of application context readiness.
⚬ Hot Reload Cycle Time: Context refresh and snippet engine reset must complete in < 3.0 seconds on an average multi-module Spring Boot project.
⚬ Memory Management: Discarded ClassLoader instances and old ApplicationContext graphs must be freed cleanly without causing JVM Metaspace leaks during repeated reload operations.
⚬ Determinism: Given identical source state and script input, the agent must receive reproducible outputs. Non-deterministic logging outputs must be isolated from return values.

6. End-to-End Agent Workflow Scenario

1. Agent plans modification:
   Agent reads issue: "Fix null pointer in OrderDiscountService.applyPromo()".

2. Introspect & Reproduce:
   - Call: inspect_bean(beanName = "orderDiscountService")
   - Call: eval(code = "orderDiscountService.applyPromo(orderId = 1, code = 'NULL_CODE')", rollback = true)
   - Result: RUNTIME_EXCEPTION (NullPointerException at OrderDiscountService.kt:42).

3. Edit Source:
   - Agent modifies src/main/kotlin/.../OrderDiscountService.kt via standard file edits.

4. Trigger Hot Reload:
   - Call: reload(recompile = true)
   - Result: SUCCESS (Recompiled 1 file, context refreshed in 1.8s).

5. Verify Fix:
   - Call: eval(code = "orderDiscountService.applyPromo(orderId = 1, code = 'NULL_CODE')", rollback = true)
   - Result: SUCCESS ("PromoResult(discount = 0.0, applied = false)").

6. Agent commits change with zero application restarts.


7. Implementation Milestones

⚬ Phase 1: Kotlin Scripting Core Engine (Weeks 1–2)
  ⚬ Embed kotlin-scripting-jvm-host inside a Spring Boot starter module.
  ⚬ Implement unproxied bean scanning and providedProperties generation.
  ⚬ Add the transactional execution wrapper with programmatic rollback.
⚬ Phase 2: Agent MCP Server & Terminal CLI (Weeks 3–4)
  ⚬ Implement MCP specification (tools: eval, list_beans, inspect_bean, get_context_schema).
  ⚬ Integrate JLine3 terminal client for local human interaction.
  ⚬ Build structured JSON formatting and stack-trace pruning filters.
⚬ Phase 3: ClassLoader Isolation & Reload Lifecycle (Weeks 5–6)
  ⚬ Integrate Gradle Tooling API for headless background incremental compilation.
  ⚬ Implement dual-ClassLoader architecture to support rapid context restarts.
  ⚬ Add the reload MCP tool and terminal command, ensuring evaluator state resets cleanly.