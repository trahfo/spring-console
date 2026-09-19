# Todo App

A reference Spring Boot todo-list application featuring a RESTful JPA backend, Bean Validation, RFC 9457 problem details, and an optional React + TypeScript frontend.

This application is built as an ordinary, standalone Spring Boot service. It can be run and tested entirely on its own, and also serves as an example of how **spring-console** can be added as a zero-configuration, drop-in dependency.

---

## 1. Prerequisites

- **Java**: JDK 17 or newer (JDK 21 recommended)
- **Node.js**: 20.19+ (only required if building/running the React frontend)
- **Gradle**: Wrapper included (`./gradlew`)

---

## 2. Quick Start

### Backend (Spring Boot)

Run the backend application:

```sh
# from examples/todo-app
./gradlew bootRun

# or from the repository root: ./gradlew :examples:todo-app:bootRun
```

The REST API will be available at `http://localhost:8080/api/todos`.

### Frontend (React + Vite)

In a separate terminal:

```sh
cd examples/todo-app/frontend
npm install
npm run dev
```

Open the printed URL (typically `http://localhost:5173`). The dev server proxies `/api` requests to the backend at `http://localhost:8080`.

### Production Packaging

Build the React frontend and package it inside the Spring Boot executable jar:

```sh
cd examples/todo-app/frontend && npm install && npm run build
cd ../../..
./gradlew :examples:todo-app:bootJar
java -jar examples/todo-app/build/libs/todo-app-0.1.0-SNAPSHOT.jar
```

The Spring Boot application serves both the API and the static React bundle on `http://localhost:8080`.

---

## 3. Architecture & Domain Model

### Domain Entity: `Todo`
Located in `com.example.todo.Todo`:
- `id`: Primary key (`Long`, auto-incremented)
- `title`: String (required, max 200 characters)
- `description`: String (optional, max 2000 characters)
- `completed`: Boolean flag (default `false`)
- `createdAt`: Timestamp (`Instant`, immutable)
- `completedAt`: Timestamp (`Instant`, recorded upon completion)
- `dueDate`: Date (`LocalDate`, optional)
- `isOverdue()`: Derived helper method (`!completed && dueDate != null && dueDate.isBefore(LocalDate.now())`)

### Records (DTOs)
- `TodoRequest(title, description, dueDate)`: Incoming create/update payload with Bean Validation annotations.
- `TodoResponse(...)`: Outgoing representation including `overdue` status.
- `TodoStats(total, active, completed, overdue)`: Aggregate metrics.

---

## 4. REST API Reference

Base path: `/api/todos`. All requests and responses use JSON. Errors return RFC 9457 Problem Details (`application/problem+json`).

| Method | Path | Description | Success Response |
|--------|------|-------------|------------------|
| `GET` | `/api/todos` | List todos (optional `?completed=true\|false`) | `200 OK` (`List<TodoResponse>`) |
| `GET` | `/api/todos/stats` | Aggregate counts | `200 OK` (`TodoStats`) |
| `GET` | `/api/todos/{id}` | Fetch a todo by ID | `200 OK` (`TodoResponse`) / `404` |
| `POST` | `/api/todos` | Create a new todo | `201 Created` + `Location` |
| `PUT` | `/api/todos/{id}` | Update existing todo | `200 OK` (`TodoResponse`) |
| `PATCH` | `/api/todos/{id}/toggle` | Toggle completed status | `200 OK` (`TodoResponse`) |
| `DELETE` | `/api/todos/{id}` | Delete a todo | `204 No Content` / `404` |
| `DELETE` | `/api/todos/completed` | Bulk delete completed todos | `200 OK` (`{"deleted": n}`) |

---

## 5. Testing

The application tests are cleanly separated:

### Standard Application Tests
Unit and integration tests for the domain, controller, and service layers (with zero dependency on `spring-console`):

```sh
# from examples/todo-app
./gradlew test

# or from repository root: ./gradlew :examples:todo-app:test
```

### Frontend Tests
Vitest unit and component tests:

```sh
cd frontend && npm test
```

### Console Integration Tests
Integration tests verifying `spring-console` capabilities (evaluation, bean binding, inspection, permanent database persistence) against the Todo application are kept in a dedicated source set (`src/consoleTest/java`):

```sh
# from examples/todo-app
./gradlew consoleTest

# or from repository root: ./gradlew :examples:todo-app:consoleTest
```

---

## 6. Using with `spring-console`

`spring-console` is designed as an additive, zero-config library. In an ordinary Spring Boot project, enabling it requires only adding the starter dependency:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.trahfo:spring-console:0.1.0")
}
```

The library auto-configures with opinionated defaults (local HTTP MCP server on port 8085, JLine3 REPL enabled when attached to a TTY). No custom configuration in `application.yml` is required.

### Launching the Interactive REPL

Because the Kotlin scripting compiler requires an exploded classpath and JLine3 needs an interactive terminal, launch the demo using the root helper script:

```sh
# from inside examples/todo-app (targets $PWD by default):
../../scripts/console.sh

# or from repository root, pointing to the app directory:
./scripts/console.sh examples/todo-app
```

At the prompt, you can interact directly with the application's beans:

```kotlin
spring-console> todoService.findAll()
spring-console> todoService.create(TodoRequest("Try spring-console", null, null))
spring-console> :schema
```

For full details on the REPL, hot reload, and AI agent integration over MCP, see the repository [README.md](../../README.md) and [MCP.md](../../MCP.md).
