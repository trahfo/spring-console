import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type { Todo } from '../types';
import { TodoItem } from './TodoItem';

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

function renderItem(todo: Todo) {
  const onToggle = vi.fn();
  const onUpdate = vi.fn().mockResolvedValue(undefined);
  const onDelete = vi.fn();
  render(
    <ul>
      <TodoItem todo={todo} onToggle={onToggle} onUpdate={onUpdate} onDelete={onDelete} />
    </ul>,
  );
  return { onToggle, onUpdate, onDelete };
}

describe('TodoItem', () => {
  it('renders title, description, and a due date chip', () => {
    renderItem(
      makeTodo({ title: 'Ship release', description: 'Tag and publish', dueDate: '2026-09-30' }),
    );

    expect(screen.getByText('Ship release')).toBeInTheDocument();
    expect(screen.getByText('Tag and publish')).toBeInTheDocument();
    const chip = screen.getByText(/due\s+sep 30, 2026/i);
    expect(chip).toHaveClass('due-chip');
    expect(chip).not.toHaveClass('is-overdue');
  });

  it('highlights the due date chip when the todo is overdue', () => {
    renderItem(makeTodo({ dueDate: '2026-09-01', overdue: true }));

    const chip = screen.getByText(/overdue\s+·\s+sep 1, 2026/i);
    expect(chip).toHaveClass('due-chip', 'is-overdue');
  });

  it('strikes through completed todos and toggles via the checkbox', async () => {
    const user = userEvent.setup();
    const { onToggle } = renderItem(makeTodo({ completed: true }));

    const checkbox = screen.getByRole('checkbox', { name: /mark "write docs" as active/i });
    expect(checkbox).toBeChecked();
    expect(screen.getByRole('listitem')).toHaveClass('is-completed');

    await user.click(checkbox);
    expect(onToggle).toHaveBeenCalledWith(1);
  });

  it('edits a todo via the Edit button and saves', async () => {
    const user = userEvent.setup();
    const { onUpdate } = renderItem(
      makeTodo({ description: 'Cover the API', dueDate: '2026-09-30' }),
    );

    await user.click(screen.getByRole('button', { name: 'Edit' }));

    // The edit form is prefilled with the current values.
    const titleInput = screen.getByLabelText('Title');
    expect(titleInput).toHaveValue('Write docs');
    expect(screen.getByLabelText('Description')).toHaveValue('Cover the API');
    expect(screen.getByLabelText('Due date')).toHaveValue('2026-09-30');

    await user.clear(titleInput);
    await user.type(titleInput, 'Write better docs');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(onUpdate).toHaveBeenCalledWith(1, {
      title: 'Write better docs',
      description: 'Cover the API',
      dueDate: '2026-09-30',
    });
    // Back to view mode after a successful save.
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
    });
  });

  it('enters edit mode on double-click', async () => {
    const user = userEvent.setup();
    renderItem(makeTodo());

    await user.dblClick(screen.getByText('Write docs'));

    expect(screen.getByLabelText('Title')).toHaveValue('Write docs');
  });

  it('cancels editing without saving', async () => {
    const user = userEvent.setup();
    const { onUpdate } = renderItem(makeTodo());

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    await user.type(screen.getByLabelText('Title'), ' with changes');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onUpdate).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
    expect(screen.getByText('Write docs')).toBeInTheDocument();
  });

  it('cancels editing with the Escape key', async () => {
    const user = userEvent.setup();
    renderItem(makeTodo());

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    await user.keyboard('{Escape}');

    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
  });

  it('blocks saving an empty title', async () => {
    const user = userEvent.setup();
    const { onUpdate } = renderItem(makeTodo());

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    await user.clear(screen.getByLabelText('Title'));
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(screen.getByText('Title is required')).toBeInTheDocument();
    expect(onUpdate).not.toHaveBeenCalled();
  });

  it('shows API validation errors and stays in edit mode', async () => {
    const user = userEvent.setup();
    const { onUpdate } = renderItem(makeTodo());
    onUpdate.mockRejectedValue(
      new ApiError(400, {
        title: 'Validation failed',
        status: 400,
        errors: { title: 'title must not be blank' },
      }),
    );

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('title must not be blank')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
  });

  it('deletes via the Delete button', async () => {
    const user = userEvent.setup();
    const { onDelete } = renderItem(makeTodo());

    await user.click(screen.getByRole('button', { name: /delete "write docs"/i }));

    expect(onDelete).toHaveBeenCalledWith(1);
  });
});
