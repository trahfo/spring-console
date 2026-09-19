import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';
import { ApiError } from './api';
import type { Todo } from './types';

const fetchMock = vi.fn();

/** Minimal stand-in for a fetch Response, independent of the test environment. */
function makeResponse(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => {
      if (body === undefined) {
        throw new SyntaxError('Unexpected end of JSON input');
      }
      return body;
    },
  } as unknown as Response;
}

const todo: Todo = {
  id: 1,
  title: 'Write docs',
  description: null,
  completed: false,
  createdAt: '2026-09-18T07:00:00Z',
  completedAt: null,
  dueDate: null,
  overdue: false,
};

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

describe('listTodos', () => {
  it('fetches /api/todos without a query for the "all" filter', async () => {
    fetchMock.mockResolvedValue(makeResponse(200, [todo]));

    await expect(api.listTodos()).resolves.toEqual([todo]);
    expect(fetchMock).toHaveBeenCalledWith('/api/todos', expect.any(Object));
  });

  it('maps the active filter to ?completed=false', async () => {
    fetchMock.mockResolvedValue(makeResponse(200, []));

    await api.listTodos('active');
    expect(fetchMock).toHaveBeenCalledWith('/api/todos?completed=false', expect.any(Object));
  });

  it('maps the completed filter to ?completed=true', async () => {
    fetchMock.mockResolvedValue(makeResponse(200, []));

    await api.listTodos('completed');
    expect(fetchMock).toHaveBeenCalledWith('/api/todos?completed=true', expect.any(Object));
  });
});

describe('getStats', () => {
  it('fetches /api/todos/stats', async () => {
    const stats = { total: 5, active: 3, completed: 2, overdue: 1 };
    fetchMock.mockResolvedValue(makeResponse(200, stats));

    await expect(api.getStats()).resolves.toEqual(stats);
    expect(fetchMock).toHaveBeenCalledWith('/api/todos/stats', expect.any(Object));
  });
});

describe('createTodo', () => {
  it('POSTs a JSON body with a Content-Type header', async () => {
    fetchMock.mockResolvedValue(makeResponse(201, todo));
    const body = { title: 'Write docs', dueDate: '2026-09-30' };

    await expect(api.createTodo(body)).resolves.toEqual(todo);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/todos');
    expect(init.method).toBe('POST');
    expect(init.body).toBe(JSON.stringify(body));
    expect(init.headers).toMatchObject({ 'Content-Type': 'application/json' });
  });
});

describe('updateTodo', () => {
  it('PUTs to /api/todos/{id}', async () => {
    fetchMock.mockResolvedValue(makeResponse(200, todo));

    await api.updateTodo(1, { title: 'Updated' });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/todos/1');
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ title: 'Updated' }));
  });
});

describe('toggleTodo', () => {
  it('PATCHes /api/todos/{id}/toggle', async () => {
    fetchMock.mockResolvedValue(makeResponse(200, { ...todo, completed: true }));

    const result = await api.toggleTodo(1);

    expect(result.completed).toBe(true);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/todos/1/toggle');
    expect(init.method).toBe('PATCH');
  });
});

describe('deleteTodo', () => {
  it('resolves without reading a body from a 204 response', async () => {
    fetchMock.mockResolvedValue(makeResponse(204));

    await expect(api.deleteTodo(1)).resolves.toBeUndefined();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/todos/1');
    expect(init.method).toBe('DELETE');
  });
});

describe('clearCompleted', () => {
  it('DELETEs /api/todos/completed and returns the count', async () => {
    fetchMock.mockResolvedValue(makeResponse(200, { deleted: 2 }));

    await expect(api.clearCompleted()).resolves.toEqual({ deleted: 2 });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/todos/completed');
    expect(init.method).toBe('DELETE');
  });
});

describe('error mapping', () => {
  it('maps an RFC 9457 problem response to a typed ApiError', async () => {
    fetchMock.mockResolvedValue(
      makeResponse(400, {
        title: 'Validation failed',
        detail: 'Request validation failed',
        status: 400,
        errors: { title: 'title must not be blank' },
      }),
    );

    const error = await api.createTodo({ title: '' }).catch((err: unknown) => err);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(400);
    expect(apiError.message).toBe('Request validation failed');
    expect(apiError.detail).toBe('Request validation failed');
    expect(apiError.errors).toEqual({ title: 'title must not be blank' });
  });

  it('falls back to a status message when the error body is not JSON', async () => {
    fetchMock.mockResolvedValue(makeResponse(500));

    const error = await api.listTodos().catch((err: unknown) => err);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(500);
    expect(apiError.message).toBe('Request failed with status 500');
    expect(apiError.errors).toEqual({});
  });

  it('wraps network failures in an ApiError with status 0', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    const error = await api.listTodos().catch((err: unknown) => err);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(0);
    expect(apiError.message).toMatch(/could not reach the server/i);
  });
});
