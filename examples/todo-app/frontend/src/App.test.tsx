import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';
import { ApiError } from './api';
import App from './App';
import type { Todo, TodoStats } from './types';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>();
  return {
    ...actual, // keep ApiError so instanceof checks still work
    listTodos: vi.fn(),
    getStats: vi.fn(),
    createTodo: vi.fn(),
    updateTodo: vi.fn(),
    toggleTodo: vi.fn(),
    deleteTodo: vi.fn(),
    clearCompleted: vi.fn(),
  };
});

const mockedApi = vi.mocked(api);

function makeTodo(overrides: Partial<Todo> = {}): Todo {
  return {
    id: 1,
    title: 'Write docs',
    description: null,
    completed: false,
    createdAt: '2026-09-18T07:00:00Z',
    completedAt: null,
    dueDate: null,
    overdue: false,
    ...overrides,
  };
}

function makeStats(overrides: Partial<TodoStats> = {}): TodoStats {
  return { total: 3, active: 2, completed: 1, overdue: 1, ...overrides };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedApi.listTodos.mockResolvedValue([makeTodo()]);
  mockedApi.getStats.mockResolvedValue(makeStats());
});

describe('App', () => {
  it('shows a loading state, then the todos and the stats line', async () => {
    render(<App />);

    expect(screen.getByText(/loading todos/i)).toBeInTheDocument();

    expect(await screen.findByText('Write docs')).toBeInTheDocument();
    expect(screen.getByText('2 active · 1 completed · 1 overdue')).toBeInTheDocument();
    expect(screen.queryByText(/loading todos/i)).not.toBeInTheDocument();
  });

  it('adds a todo and refreshes the list and stats', async () => {
    let currentTodos: Todo[] = [];
    mockedApi.listTodos.mockImplementation(async () => currentTodos);
    mockedApi.createTodo.mockImplementation(async (body) => {
      const created = makeTodo({ id: 99, title: body.title });
      currentTodos = [created, ...currentTodos];
      return created;
    });

    const user = userEvent.setup();
    render(<App />);
    await screen.findByText(/nothing here yet/i);

    await user.type(screen.getByLabelText('Title'), 'Buy milk');
    await user.click(screen.getByRole('button', { name: 'Add todo' }));

    expect(await screen.findByText('Buy milk')).toBeInTheDocument();
    expect(mockedApi.createTodo).toHaveBeenCalledWith({
      title: 'Buy milk',
      description: undefined,
      dueDate: undefined,
    });
    // Initial load + refresh after the mutation.
    expect(mockedApi.getStats).toHaveBeenCalledTimes(2);
    // The form resets after a successful add.
    expect(screen.getByLabelText('Title')).toHaveValue('');
  });

  it('shows API validation errors under the title field', async () => {
    mockedApi.createTodo.mockRejectedValue(
      new ApiError(400, {
        title: 'Validation failed',
        status: 400,
        errors: { title: 'title must not exceed 200 characters' },
      }),
    );

    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('Write docs');

    await user.type(screen.getByLabelText('Title'), 'Something too long');
    await user.click(screen.getByRole('button', { name: 'Add todo' }));

    expect(
      await screen.findByText('title must not exceed 200 characters'),
    ).toBeInTheDocument();
    // The input keeps its value so the user can correct it.
    expect(screen.getByLabelText('Title')).toHaveValue('Something too long');
  });

  it('toggles a todo via its checkbox and refreshes', async () => {
    let currentTodos = [makeTodo()];
    mockedApi.listTodos.mockImplementation(async () => currentTodos);
    mockedApi.toggleTodo.mockImplementation(async (id) => {
      const toggled = makeTodo({ id, completed: true, completedAt: '2026-09-18T08:00:00Z' });
      currentTodos = [toggled];
      return toggled;
    });

    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('Write docs');

    await user.click(screen.getByRole('checkbox', { name: /mark "write docs" as completed/i }));

    expect(mockedApi.toggleTodo).toHaveBeenCalledWith(1);
    await waitFor(() => {
      expect(screen.getByRole('checkbox')).toBeChecked();
    });
  });

  it('switches filters and requests the matching query', async () => {
    const active = makeTodo({ id: 1, title: 'Active todo' });
    const done = makeTodo({ id: 2, title: 'Done todo', completed: true });
    mockedApi.listTodos.mockImplementation(async (filter = 'all') =>
      filter === 'active' ? [active] : [active, done],
    );

    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('Done todo');

    await user.click(screen.getByRole('button', { name: 'Active' }));

    expect(mockedApi.listTodos).toHaveBeenLastCalledWith('active');
    await waitFor(() => {
      expect(screen.queryByText('Done todo')).not.toBeInTheDocument();
    });
    expect(screen.getByText('Active todo')).toBeInTheDocument();
  });

  it('clears completed todos and refreshes', async () => {
    mockedApi.clearCompleted.mockResolvedValue({ deleted: 1 });

    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('Write docs');

    const clearButton = screen.getByRole('button', { name: 'Clear completed' });
    expect(clearButton).toBeEnabled();

    await user.click(clearButton);

    expect(mockedApi.clearCompleted).toHaveBeenCalledTimes(1);
    await waitFor(() => {
      expect(mockedApi.getStats).toHaveBeenCalledTimes(2);
    });
  });

  it('disables "Clear completed" when there are no completed todos', async () => {
    mockedApi.getStats.mockResolvedValue(makeStats({ completed: 0 }));

    render(<App />);
    await screen.findByText('Write docs');

    expect(screen.getByRole('button', { name: 'Clear completed' })).toBeDisabled();
  });

  it('shows a dismissible error banner when loading fails', async () => {
    mockedApi.listTodos.mockRejectedValue(new Error('boom'));

    const user = userEvent.setup();
    render(<App />);

    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent(/something went wrong/i);

    await user.click(screen.getByRole('button', { name: 'Dismiss error' }));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
