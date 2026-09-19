/** A todo as returned by the backend (`TodoResponse`). */
export interface Todo {
  id: number;
  title: string;
  description: string | null;
  completed: boolean;
  /** ISO-8601 instant, e.g. "2026-09-18T07:00:00Z". */
  createdAt: string;
  /** ISO-8601 instant, or null while the todo is active. */
  completedAt: string | null;
  /** ISO date (YYYY-MM-DD), or null when no due date is set. */
  dueDate: string | null;
  overdue: boolean;
}

/** Request body for creating or updating a todo (`TodoRequest`). */
export interface TodoRequest {
  title: string;
  description?: string;
  /** ISO date (YYYY-MM-DD). */
  dueDate?: string;
}

/** Aggregate counts from `GET /api/todos/stats`. */
export interface TodoStats {
  total: number;
  active: number;
  completed: number;
  overdue: number;
}

/** Client-side list filter; maps to the `?completed=` query parameter. */
export type Filter = 'all' | 'active' | 'completed';
