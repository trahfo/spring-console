# Todo App Frontend

A small React + TypeScript frontend for the todo-list example backend. Built
with [Vite](https://vitejs.dev), styled with plain CSS, and tested with
[Vitest](https://vitest.dev) and Testing Library. No runtime dependencies
beyond `react` and `react-dom`.

## Prerequisites

- Node.js 20.19+ (any current LTS works)
- The Spring Boot backend from the parent directory, which serves the API on
  `http://localhost:8080`

## Development

Start the backend first, then the Vite dev server:

```sh
# from examples/todo-app
./gradlew bootRun

# in another terminal, from examples/todo-app/frontend
npm install
npm run dev
```

`bootRun` is enough for the UI: it serves the REST API. It does **not** start
the interactive console REPL (Gradle runs the app in a JVM without a terminal);
use `../../../scripts/console.sh` when you want the REPL as well.

The dev server proxies every `/api` request to `http://localhost:8080` (see
`server.proxy` in [`vite.config.ts`](vite.config.ts)), so the frontend and
backend behave as a single origin during development. Open the URL Vite
prints (usually `http://localhost:5173`).

## Building for production

```sh
npm run build
```

Type-checks with `tsc` and writes the production bundle to `dist/`. The
Gradle build of the parent project bundles `frontend/dist` into the Spring
Boot jar as static resources (see the `bootJar` task in
[`../build.gradle.kts`](../build.gradle.kts)), so after building the frontend
and the jar, the Spring app serves the UI itself at `http://localhost:8080` —
no separate web server needed.

`npm run preview` serves the built bundle locally if you want to inspect it
(note: the preview server does not proxy `/api`).

## Testing

```sh
npm test         # single run (vitest run)
npm run test:watch  # watch mode
```

Tests run in jsdom. The API client tests stub `fetch` directly; the component
tests mock the API client module — no network access and no mock-server
dependency.

## Project layout

```
src/
  api.ts               Typed API client; throws ApiError with RFC 9457 fields
  types.ts             Shared request/response types
  App.tsx              State, data loading, and mutation handlers
  components/
    TodoForm.tsx       Add form with inline validation errors
    TodoList.tsx       List + empty states
    TodoItem.tsx       Row with toggle, inline edit (dblclick or Edit), delete
    FilterTabs.tsx     All / Active / Completed filter
    StatsBar.tsx       "X active · Y completed · Z overdue" line
    ErrorBanner.tsx    Dismissible request-failure banner
  styles.css           Single plain-CSS stylesheet (light + dark)
  test/setup.ts        Vitest setup (jest-dom matchers, cleanup)
```

## API contract

The client talks to `/api/todos` (list, create, update, toggle, delete,
`/stats`, `DELETE /completed`). Error responses are RFC 9457 problem details;
`ApiError` exposes `status`, `detail`, and the per-field `errors` map, which
the forms use to show validation messages inline.
