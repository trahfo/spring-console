import { useCallback, useEffect, useState } from 'react';
import * as api from './api';
import { ApiError } from './api';
import { ErrorBanner } from './components/ErrorBanner';
import { FilterTabs } from './components/FilterTabs';
import { StatsBar } from './components/StatsBar';
import { TodoForm } from './components/TodoForm';
import { TodoList } from './components/TodoList';
import type { Filter, Todo, TodoRequest, TodoStats } from './types';

export default function App() {
  const [todos, setTodos] = useState<Todo[]>([]);
  const [stats, setStats] = useState<TodoStats | null>(null);
  const [filter, setFilter] = useState<Filter>('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /** Reload the visible list and the stats line in one go. */
  const refresh = useCallback(async (nextFilter: Filter) => {
    const [nextTodos, nextStats] = await Promise.all([
      api.listTodos(nextFilter),
      api.getStats(),
    ]);
    setTodos(nextTodos);
    setStats(nextStats);
  }, []);

  const reportError = useCallback((err: unknown) => {
    setError(
      err instanceof ApiError
        ? err.message
        : 'Something went wrong. Please try again.',
    );
  }, []);

  useEffect(() => {
    let cancelled = false;
    refresh(filter)
      .catch((err: unknown) => {
        if (!cancelled) reportError(err);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [filter, refresh, reportError]);

  // Form errors (validation) are handled inline by TodoForm/TodoItem, so
  // these two rethrow; the fire-and-forget actions report to the banner.
  const handleAdd = async (body: TodoRequest) => {
    await api.createTodo(body);
    await refresh(filter);
  };

  const handleUpdate = async (id: number, body: TodoRequest) => {
    await api.updateTodo(id, body);
    await refresh(filter);
  };

  const handleToggle = async (id: number) => {
    try {
      await api.toggleTodo(id);
      await refresh(filter);
    } catch (err) {
      reportError(err);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await api.deleteTodo(id);
      await refresh(filter);
    } catch (err) {
      reportError(err);
    }
  };

  const handleClearCompleted = async () => {
    try {
      await api.clearCompleted();
      await refresh(filter);
    } catch (err) {
      reportError(err);
    }
  };

  return (
    <main className="app">
      <div className="card">
        <header className="app-header">
          <h1>Todos</h1>
          <StatsBar stats={stats} />
        </header>

        {error !== null && (
          <ErrorBanner message={error} onDismiss={() => setError(null)} />
        )}

        <TodoForm onAdd={handleAdd} />

        <div className="toolbar">
          <FilterTabs filter={filter} onChange={setFilter} />
          <button
            type="button"
            className="btn btn-subtle"
            onClick={handleClearCompleted}
            disabled={stats === null || stats.completed === 0}
          >
            Clear completed
          </button>
        </div>

        {loading ? (
          <p className="loading" role="status">
            Loading todos…
          </p>
        ) : (
          <TodoList
            todos={todos}
            filter={filter}
            onToggle={handleToggle}
            onUpdate={handleUpdate}
            onDelete={handleDelete}
          />
        )}
      </div>
    </main>
  );
}
