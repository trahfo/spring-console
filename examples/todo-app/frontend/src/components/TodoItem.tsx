import { useState, type FormEvent, type KeyboardEvent } from 'react';
import { ApiError } from '../api';
import type { Todo, TodoRequest } from '../types';

interface TodoItemProps {
  todo: Todo;
  onToggle: (id: number) => void;
  onUpdate: (id: number, body: TodoRequest) => Promise<void>;
  onDelete: (id: number) => void;
}

/** Format an ISO date (YYYY-MM-DD) like "Sep 30, 2026". */
function formatDueDate(isoDate: string): string {
  const date = new Date(`${isoDate}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) {
    return isoDate;
  }
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(date);
}

/**
 * One row of the list. Double-click the text (or press the Edit button)
 * to switch to an inline edit form with Save/Cancel; Escape cancels.
 */
export function TodoItem({ todo, onToggle, onUpdate, onDelete }: TodoItemProps) {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(todo.title);
  const [description, setDescription] = useState(todo.description ?? '');
  const [dueDate, setDueDate] = useState(todo.dueDate ?? '');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const startEditing = () => {
    setTitle(todo.title);
    setDescription(todo.description ?? '');
    setDueDate(todo.dueDate ?? '');
    setFieldErrors({});
    setSaveError(null);
    setEditing(true);
  };

  const cancelEditing = () => setEditing(false);

  const handleEditKeyDown = (event: KeyboardEvent<HTMLFormElement>) => {
    if (event.key === 'Escape') {
      cancelEditing();
    }
  };

  const handleSave = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmedTitle = title.trim();
    if (trimmedTitle === '') {
      setFieldErrors({ title: 'Title is required' });
      return;
    }

    setSaving(true);
    setFieldErrors({});
    setSaveError(null);
    try {
      await onUpdate(todo.id, {
        title: trimmedTitle,
        description: description.trim() || undefined,
        dueDate: dueDate || undefined,
      });
      setEditing(false);
    } catch (err) {
      if (err instanceof ApiError) {
        setFieldErrors(err.errors);
        if (Object.keys(err.errors).length === 0) {
          setSaveError(err.message);
        }
      } else {
        setSaveError('Could not save changes. Please try again.');
      }
    } finally {
      setSaving(false);
    }
  };

  if (editing) {
    const titleId = `edit-title-${todo.id}`;
    const descriptionId = `edit-description-${todo.id}`;
    const dueDateId = `edit-due-date-${todo.id}`;

    return (
      <li className="todo-item is-editing">
        <form
          className="todo-edit-form"
          onSubmit={handleSave}
          onKeyDown={handleEditKeyDown}
          aria-label={`Edit "${todo.title}"`}
        >
          <div className="field">
            <label htmlFor={titleId}>Title</label>
            <input
              id={titleId}
              type="text"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              maxLength={200}
              autoFocus
              aria-invalid={fieldErrors.title !== undefined}
              aria-describedby={
                fieldErrors.title !== undefined ? `${titleId}-error` : undefined
              }
            />
            {fieldErrors.title !== undefined && (
              <p className="field-error" id={`${titleId}-error`}>
                {fieldErrors.title}
              </p>
            )}
          </div>

          <div className="field">
            <label htmlFor={descriptionId}>Description</label>
            <input
              id={descriptionId}
              type="text"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder="Optional details"
            />
            {fieldErrors.description !== undefined && (
              <p className="field-error">{fieldErrors.description}</p>
            )}
          </div>

          <div className="field">
            <label htmlFor={dueDateId}>Due date</label>
            <input
              id={dueDateId}
              type="date"
              value={dueDate}
              onChange={(event) => setDueDate(event.target.value)}
            />
            {fieldErrors.dueDate !== undefined && (
              <p className="field-error">{fieldErrors.dueDate}</p>
            )}
          </div>

          {saveError !== null && (
            <p className="field-error form-error" role="alert">
              {saveError}
            </p>
          )}

          <div className="todo-edit-actions">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Saving…' : 'Save'}
            </button>
            <button type="button" className="btn" onClick={cancelEditing}>
              Cancel
            </button>
          </div>
        </form>
      </li>
    );
  }

  return (
    <li className={`todo-item${todo.completed ? ' is-completed' : ''}`}>
      <input
        type="checkbox"
        className="todo-checkbox"
        checked={todo.completed}
        onChange={() => onToggle(todo.id)}
        aria-label={`Mark "${todo.title}" as ${todo.completed ? 'active' : 'completed'}`}
      />
      <div className="todo-body" onDoubleClick={startEditing}>
        <p className="todo-title">{todo.title}</p>
        {todo.description !== null && todo.description !== '' && (
          <p className="todo-description">{todo.description}</p>
        )}
        {todo.dueDate !== null && (
          <span className={`due-chip${todo.overdue ? ' is-overdue' : ''}`}>
            {todo.overdue ? 'Overdue · ' : 'Due '}
            {formatDueDate(todo.dueDate)}
          </span>
        )}
      </div>
      <div className="todo-actions">
        <button type="button" className="btn btn-subtle" onClick={startEditing}>
          Edit
        </button>
        <button
          type="button"
          className="btn btn-subtle btn-danger"
          onClick={() => onDelete(todo.id)}
          aria-label={`Delete "${todo.title}"`}
        >
          Delete
        </button>
      </div>
    </li>
  );
}
