import type { Filter, Todo, TodoRequest } from '../types';
import { TodoItem } from './TodoItem';

interface TodoListProps {
  todos: Todo[];
  filter: Filter;
  onToggle: (id: number) => void;
  onUpdate: (id: number, body: TodoRequest) => Promise<void>;
  onDelete: (id: number) => void;
}

const EMPTY_MESSAGES: Record<Filter, string> = {
  all: 'Nothing here yet — add your first todo above.',
  active: 'No active todos. Nice work!',
  completed: 'No completed todos yet.',
};

export function TodoList({ todos, filter, onToggle, onUpdate, onDelete }: TodoListProps) {
  if (todos.length === 0) {
    return <p className="empty">{EMPTY_MESSAGES[filter]}</p>;
  }

  return (
    <ul className="todo-list">
      {todos.map((todo) => (
        <TodoItem
          key={todo.id}
          todo={todo}
          onToggle={onToggle}
          onUpdate={onUpdate}
          onDelete={onDelete}
        />
      ))}
    </ul>
  );
}
