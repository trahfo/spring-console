import type { Filter, Todo, TodoRequest, TodoStats } from './types';

const BASE_URL = '/api/todos';

/** RFC 9457 problem details as produced by the backend. */
export interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
  /** Field-level validation errors, keyed by field name. */
  errors?: Record<string, string>;
}

/**
 * Error thrown for any failed API call. Carries the HTTP status and the
 * problem-detail fields so callers can show field-level validation errors.
 * A status of 0 means the request never reached the server.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly detail?: string;
  readonly errors: Record<string, string>;

  constructor(status: number, problem: ProblemDetail = {}) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.detail = problem.detail;
    this.errors = problem.errors ?? {};
  }
}

/**
 * Perform a fetch and either return the parsed JSON body or throw an
 * {@link ApiError} built from the RFC 9457 problem-detail response.
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: {
        Accept: 'application/json',
        ...(init.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...init.headers,
      },
    });
  } catch {
    throw new ApiError(0, {
      title: 'Network error',
      detail: 'Could not reach the server. Is the backend running?',
    });
  }

  if (!response.ok) {
    let problem: ProblemDetail = {};
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      // Non-JSON error body; fall back to a status-based message.
    }
    throw new ApiError(response.status, problem);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** List todos, newest first. `active`/`completed` map to `?completed=`. */
export function listTodos(filter: Filter = 'all'): Promise<Todo[]> {
  const query = filter === 'all' ? '' : `?completed=${filter === 'completed'}`;
  return request<Todo[]>(`${BASE_URL}${query}`);
}

export function getStats(): Promise<TodoStats> {
  return request<TodoStats>(`${BASE_URL}/stats`);
}

export function createTodo(body: TodoRequest): Promise<Todo> {
  return request<Todo>(BASE_URL, { method: 'POST', body: JSON.stringify(body) });
}

export function updateTodo(id: number, body: TodoRequest): Promise<Todo> {
  return request<Todo>(`${BASE_URL}/${id}`, { method: 'PUT', body: JSON.stringify(body) });
}

export function toggleTodo(id: number): Promise<Todo> {
  return request<Todo>(`${BASE_URL}/${id}/toggle`, { method: 'PATCH' });
}

export function deleteTodo(id: number): Promise<void> {
  return request<void>(`${BASE_URL}/${id}`, { method: 'DELETE' });
}

export function clearCompleted(): Promise<{ deleted: number }> {
  return request<{ deleted: number }>(`${BASE_URL}/completed`, { method: 'DELETE' });
}
