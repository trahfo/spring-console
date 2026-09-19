# spring-console

An interactive **Kotlin REPL and MCP server** embedded in your running Spring
Boot application.

spring-console exposes the live `ApplicationContext` as an executable interactive environment.
Beans are bound into a Kotlin scripting scope as typed variables, evaluations execute
directly with permanent consequences, and modified sources can be recompiled and
hot-restarted inside the same JVM. Humans get a colorized terminal with tab completion
and syntax highlighting; AI agents get the same capabilities as deterministic MCP tools.

```
             Human terminal                         AI agent (MCP client)
                  │                                        │
                  ▼                                        ▼
        ┌───────────────────┐                   ┌───────────────────┐
        │ JLine3 REPL       │                   │ MCP server        │
        │ completion,       │                   │ JSON-RPC 2.0      │
        │ highlighting,     │                   │ (HTTP or stdio)   │
        │ rich rendering    │                   │                   │
        └─────────┬─────────┘                   └─────────┬─────────┘
                  └───────────────┬───────────────────────┘
                                  ▼
                     ┌──────────────────────────┐
                     │ ConsoleService           │  ← identical behavior for both
                     │ eval, list/inspect beans,│
                     │ context schema, reload   │
                     └────────────┬─────────────┘
                                  ▼
        ┌───────────────────────────────────────────────────────┐
        │ Kotlin scripting engine · bean binding · live sandbox │
        │ reload pipeline (compiler + context restart)          │
        └───────────────────────────────────────────────────────┘
```

---

## Modules

| Module | Description |
|--------|-------------|
| [`spring-console-starter`](spring-console-starter) | The library: auto-configuration, scripting engine, REPL, MCP server, reload pipeline |
| [`examples/todo-app`](examples/todo-app) | A complete Spring Boot + React demo application with its own extensive [README](examples/todo-app/README.md) |
| [Architecture Guide](ARCHITECTURE.md) | In-depth technical architecture, class interaction diagrams, bootup sequence, and autocomplete pipeline |
| [MCP Guide](MCP.md) | Complete reference for AI agents, endpoints (`http://127.0.0.1:8085/mcp`), tool schemas, and client configuration |
| [Comparison Guide](COMPARISON.md) | In-depth comparison against Spring Shell, CRaSH, JShell, Groovy Console, and Spring Boot Admin |

---

## How to run

`spring-console` supports two execution workflows: **Auto-Attach Mode** (recommended for everyday development) and **Standalone Mode**.

### 1. The 3-Tab Development Workflow (Auto-Attach Mode)

In a typical full-stack development session, you run:

```text
┌────────────────────────────────┐  ┌────────────────────────────────┐  ┌────────────────────────────────┐
│ Tab 1: Backend Server          │  │ Tab 2: Frontend (Vite)         │  │ Tab 3: Interactive Console     │
│ ./gradlew bootRun              │  │ npm run dev                    │  │ scripts/console.sh             │
│ (listening on :8080 & :8085)   │  │ (proxying /api to :8080)       │  │ (auto-attaches to :8085)       │
└────────────────────────────────┘  └────────────────────────────────┘  └────────────────────────────────┘
```

1. **Tab 1 — Backend**: Start your Spring Boot application normally (e.g. `./gradlew bootRun` or via your IDE). The backend listens on port `8080` (Tomcat) and starts the embedded MCP HTTP server on `http://127.0.0.1:8085/mcp`.
2. **Tab 2 — Frontend**: Run your frontend development server (e.g. `npm run dev` with Vite), proxying API calls to `localhost:8080`.
3. **Tab 3 — Console**: Run `scripts/console.sh` (or from your project folder):
   ```sh
   # from the target project directory (opinionated default: expects Spring Boot app in $PWD):
   cd examples/todo-app && ../../scripts/console.sh

   # or pointing to the project directory from anywhere:
   scripts/console.sh examples/todo-app
   ```

`console.sh` automatically detects the running backend via its MCP endpoint and **attaches directly as a lightweight client**:
* **Zero port conflicts**: No attempts to bind port `8080` or `8085` in Tab 3.
* **Live shared state**: Evaluating queries and mutations (`todoService.create(...)`) directly mutates the live database and beans in Tab 1, immediately reflected in Tab 2's UI.
* **Safe detachment**: Typing `:quit` or pressing `Ctrl-D` detaches the console without terminating the running application.
* **Remote hot reload**: Typing `:reload` instructs the running backend to recompile and restart its Spring context in-process.

### 2. Standalone Mode

If no backend is currently running, `scripts/console.sh` boots the Spring Boot application directly with the interactive REPL in the foreground:

```sh
scripts/console.sh examples/todo-app
```

* **Automatic port conflict prevention**: If port `8080` happens to be occupied by an unrelated service on your machine, `console.sh` detects it and automatically assigns a random port (`--server.port=0`) so the application and console start without crashing.
* **Console-only (headless) mode**: To run an application with **only** the console — no web server, no MCP endpoint:
  ```sh
  scripts/console.sh examples/todo-app \
    --spring.main.web-application-type=none \
    --spring-console.mcp.enabled=false
  ```

---

## Interactive prompt preview

In an interactive terminal you get full JLine3 editing with syntax highlighting and tab-completion:

```text
Spring Console — Kotlin REPL bound to this application context
Kotlin code is syntax-highlighted; press Tab to complete beans, members, and commands.
Type :help for commands.
spring-console> todoService.findAll()
List<Todo> (4 items)
id: Long │ title: String                │ completed: boolean │ dueDate: LocalDate
─────────┼─────────────────────────────┼────────────────────┼────────────────────
1        │ Set up development environ..│ false              │ null
...
```

At the same time, the embedded MCP endpoint is available at `http://127.0.0.1:8085/mcp`. See the [MCP Guide](MCP.md) for AI agent setup and the [demo app README](examples/todo-app/README.md) for a guided tour.

---

## Adding to your project

Add the dependency to any Spring Boot 3.x application:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.springconsole:spring-console:0.1.0")
}
```

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.github.springconsole</groupId>
    <artifactId>spring-console</artifactId>
    <version>0.1.0</version>
</dependency>
```

Only `spring-boot-starter` is required transitively; JPA/Spring Data integrations are detected automatically when present. Target applications require **zero custom Gradle tasks** and **zero custom configuration**.

---

## Feature tour

### Auto-completion (JLine3 `Completer`)

Press **Tab** to complete:

* `:commands` and their arguments (`:inspect ` → bean names, `:beans ` → package),
* bound bean names and `val`/`var`/`fun` names declared earlier in the session,
* **members after a dot**, with return types:
  `todoService.fin<Tab>` → `findById(`, `findByCompleted(`, …
* `context.` → `ApplicationContext` members.

Member completion is answered from introspection metadata, never by evaluating
your code, so it has no side effects. See
[`ConsoleCompleter`](spring-console-starter/src/main/kotlin/io/github/springconsole/repl/ConsoleCompleter.kt).

### Syntax highlighting (JLine3 `Highlighter`)

The input line is colored as you type: keywords, soft keywords, strings, chars,
numbers, literals, comments, annotations, and backticked identifiers each have a
distinct style. A small hand-written lexer keeps highlighting total and fast on
half-typed input. If the line editor reports a parse error, the offending range
is marked. See
[`KotlinSyntaxHighlighter`](spring-console-starter/src/main/kotlin/io/github/springconsole/repl/KotlinSyntaxHighlighter.kt)
and [`KotlinLexer`](spring-console-starter/src/main/kotlin/io/github/springconsole/repl/KotlinLexer.kt).

### Rich, color-coded value view

Naming a value (or calling a method that returns one) renders it structurally
instead of calling `toString()`:

| Runtime shape | Rendered as |
|---------------|-------------|
| `Collection`, array, `Sequence`, Java `Stream` | table with typed columns |
| Java record / object with getters | key/value block |
| bean without properties | type header + method signatures |
| `Map` | key/value table |
| `Optional` | unwrapped value |
| Spring Data `Slice`/`Page` | page header + content table |
| scalars, enums, dates, `null` | one colored line |
| `Throwable` | red message + dimmed frames |

Output is bounded (rows/columns/cell width/string length) with explicit
truncation notices, and every reflective read is guarded so a lazy proxy cannot
break the whole view. See
[`ResultRenderer`](spring-console-starter/src/main/kotlin/io/github/springconsole/repl/ResultRenderer.kt)
and [`ReplPresenter`](spring-console-starter/src/main/kotlin/io/github/springconsole/repl/ReplPresenter.kt).

### Direct execution with permanent consequences

Evaluations run directly against the live application context, with real, permanent
effects on your database and services:

```text
spring-console> todoService.create(TodoRequest("Buy milk", null, null))
Todo  (8 properties)
  ...
```

### Hot reload

```text
spring-console> :reload
Recompiling and restarting context…
Context refreshed in 1.8s
```

The build tool is auto-detected (`gradlew classes` / `mvnw compile`), the context
is closed and re-created in-process, and the REPL re-binds. Compilation failures
return structured `{line, column, message}` diagnostics.

### MCP tools
 
The embedded MCP server enables AI agents (Claude, Cursor, Antigravity) to introspect
and modify the live application via HTTP (`http://127.0.0.1:8085/mcp`) or stdio.
See the dedicated [MCP Guide](MCP.md) for endpoint details, schemas, and client setup.

| Tool | Purpose |
|------|---------|
| `eval` | Execute Kotlin against the running context (`timeoutMs`) |
| `list_beans` | List beans with resolved (unproxied) types |
| `inspect_bean` | Methods, properties, interfaces of one bean |
| `reload` | Recompile + hot-restart the context |
| `get_context_schema` | Entities, repositories, services |

---

## The REPL command surface

```text
:help                    show this help
:beans [package]         list beans bound into the REPL scope
:inspect <bean>          show a bean's methods and types
:schema                  dump entities, repositories, and services
:reload [--no-compile]   recompile sources and hot-restart the context
:quit                    detach the console (stops the app if it has no other interface)
```

Anything else is evaluated as Kotlin against the live context.

To run an application with **only** the console — no web server, no MCP
endpoint — start it as a non-web application and disable MCP:

```sh
scripts/console.sh examples/todo-app \
  --spring.main.web-application-type=none \
  --spring-console.mcp.enabled=false
```

The console runs on a non-daemon thread, so `:quit`/Ctrl-D stops a console-only process cleanly.

---

## Configuration

Everything lives under `spring-console.*`:

```yaml
spring-console:
  enabled: true
  eval-timeout-ms: 5000
  warmup: true
  include-infrastructure-beans: false
  default-imports: []
  repl:
    enabled: true
  mcp:
    enabled: true
    host: 127.0.0.1
    port: 8085
    path: /mcp
    stdio: false
  reload:
    enabled: true
    command: []            # auto-detected when empty
    project-dir: ""
    compile-timeout-ms: 120000
    restart-timeout-ms: 60000
```

> **Security:** the console executes arbitrary code and can expose application
> internals. The MCP server binds to `127.0.0.1` by default; do not expose it to
> untrusted networks, and disable the console
> (`spring-console.enabled=false`) in production.

---

## Building and testing

```sh
./gradlew build                      # compile + test everything
./gradlew :spring-console-starter:test
./gradlew :examples:todo-app:test

cd examples/todo-app/frontend
npm install
npm test                             # vitest
```

Every feature is covered by tests. The console tests live in
`spring-console-starter/src/test/kotlin/io/github/springconsole/`:

| Area | Representative tests |
|------|----------------------|
| Scripting engine & state | `engine/KotlinReplEngineTest` |
| Transactional sandbox | `ConsoleServiceIntegrationTest` |
| Bean binding & introspection | `ConsoleServiceIntegrationTest`, `autoconfigure/*` |
| MCP protocol & tools | `mcp/McpServerIntegrationTest` |
| Reload pipeline | `reload/CompilationBridgeTest`, `reload/RestartClassLoaderTest`, `reload/BuildErrorParserTest` |
| Tab completion | `repl/ConsoleCompleterTest` |
| Terminal selection / REPL lifecycle | `repl/ConsoleReplTest` |
| Warm-up & compiler thread affinity | `engine/ConsoleWarmupTest`, `engine/CompilerHintTest` |
| Syntax highlighting | `repl/KotlinLexerTest`, `repl/KotlinSyntaxHighlighterTest`, `repl/ReplThemeTest` |
| Rich rendering | `repl/ResultRendererTest`, `repl/ReplPresenterTest` |
| Commands | `repl/ReplCommandHandlerTest`, `repl/BracketsBalancedTest` |
| End-to-end REPL path | `repl/ConsoleReplPipelineTest` |
| MCP payload contract | `api/EvalResultSerializationTest` |

---

## Comparison with alternatives

Looking for how `spring-console` compares to tools like Spring Shell, CRaSH, JShell, Groovy consoles, or Spring Boot Admin?
See the comprehensive [Tool Comparison Guide](COMPARISON.md) for architectural teardowns, trade-offs, and a full feature ranking matrix.

---

## Requirements

* JDK 17+
* Kotlin 2.2 (for the starter module itself)
* Spring Boot 3.5
* Node.js 20.19+ — only for the demo frontend

The console uses [JLine3](https://github.com/jline/jline3) for the terminal,
the Kotlin scripting JVM host for evaluation, and Jackson for JSON.
