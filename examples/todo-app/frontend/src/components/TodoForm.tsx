import { useState, type FormEvent } from 'react';
import { ApiError } from '../api';
import type { TodoRequest } from '../types';

interface TodoFormProps {
  onAdd: (body: TodoRequest) => Promise<void>;
}

/** The add-todo form: title (required), optional description and due date. */
export function TodoForm({ onAdd }: TodoFormProps) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmedTitle = title.trim();
    if (trimmedTitle === '') {
      setFieldErrors({ title: 'Title is required' });
      return;
    }

    setSubmitting(true);
    setFieldErrors({});
    setFormError(null);
    try {
      await onAdd({
        title: trimmedTitle,
        description: description.trim() || undefined,
        dueDate: dueDate || undefined,
      });
      setTitle('');
      setDescription('');
      setDueDate('');
    } catch (err) {
      if (err instanceof ApiError) {
        setFieldErrors(err.errors);
        if (Object.keys(err.errors).length === 0) {
          setFormError(err.message);
        }
      } else {
        setFormError('Could not add the todo. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form className="todo-form" onSubmit={handleSubmit} aria-label="Add todo">
      <div className="field field-title">
        <label htmlFor="new-title">Title</label>
        <input
          id="new-title"
          type="text"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          placeholder="What needs to be done?"
          maxLength={200}
          aria-invalid={fieldErrors.title !== undefined}
          aria-describedby={fieldErrors.title !== undefined ? 'new-title-error' : undefined}
        />
        {fieldErrors.title !== undefined && (
          <p className="field-error" id="new-title-error">
            {fieldErrors.title}
          </p>
        )}
      </div>

      <div className="field field-description">
        <label htmlFor="new-description">Description</label>
        <input
          id="new-description"
          type="text"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          placeholder="Optional details"
          aria-invalid={fieldErrors.description !== undefined}
          aria-describedby={
            fieldErrors.description !== undefined ? 'new-description-error' : undefined
          }
        />
        {fieldErrors.description !== undefined && (
          <p className="field-error" id="new-description-error">
            {fieldErrors.description}
          </p>
        )}
      </div>

      <div className="field field-due">
        <label htmlFor="new-due-date">Due date</label>
        <input
          id="new-due-date"
          type="date"
          value={dueDate}
          onChange={(event) => setDueDate(event.target.value)}
          aria-invalid={fieldErrors.dueDate !== undefined}
          aria-describedby={fieldErrors.dueDate !== undefined ? 'new-due-date-error' : undefined}
        />
        {fieldErrors.dueDate !== undefined && (
          <p className="field-error" id="new-due-date-error">
            {fieldErrors.dueDate}
          </p>
        )}
      </div>

      <button type="submit" className="btn btn-primary" disabled={submitting}>
        {submitting ? 'Adding…' : 'Add todo'}
      </button>

      {formError !== null && (
        <p className="field-error form-error" role="alert">
          {formError}
        </p>
      )}
    </form>
  );
}
