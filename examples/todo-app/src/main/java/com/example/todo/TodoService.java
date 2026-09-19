package com.example.todo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Application service for the todo list. Mutating operations are
 * transactional and persist directly to the database.
 */
@Service
@Transactional(readOnly = true)
public class TodoService {

    private final TodoRepository repository;

    public TodoService(TodoRepository repository) {
        this.repository = repository;
    }

    /** All todos, newest first. */
    public List<Todo> findAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /** Todos in one completion state, newest first. */
    public List<Todo> findByCompleted(boolean completed) {
        return repository.findByCompletedOrderByCreatedAtDesc(completed);
    }

    /** @throws TodoNotFoundException when no todo has this id */
    public Todo findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new TodoNotFoundException(id));
    }

    /** Active todos whose due date has passed. */
    public List<Todo> findOverdue() {
        return repository.findByCompletedFalseAndDueDateBefore(LocalDate.now());
    }

    /** Aggregate counters for the UI footer. */
    public TodoStats stats() {
        long completed = repository.countByCompleted(true);
        long active = repository.countByCompleted(false);
        return new TodoStats(completed + active, active, completed, findOverdue().size());
    }

    /** Creates a todo, trimming the title and normalising blank text to null. */
    @Transactional
    public Todo create(TodoRequest request) {
        return repository.save(new Todo(request.title().trim(), normalize(request.description()), request.dueDate()));
    }

    /** Applies a full update to an existing todo. */
    @Transactional
    public Todo update(Long id, TodoRequest request) {
        Todo todo = findById(id);
        todo.setTitle(request.title().trim());
        todo.setDescription(normalize(request.description()));
        todo.setDueDate(request.dueDate());
        return todo;
    }

    /** Flips the completion state (and the completion timestamp). */
    @Transactional
    public Todo toggle(Long id) {
        Todo todo = findById(id);
        todo.setCompleted(!todo.isCompleted());
        return todo;
    }

    /** @throws TodoNotFoundException when no todo has this id */
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new TodoNotFoundException(id);
        }
        repository.deleteById(id);
    }

    /** Removes every completed todo and returns how many were deleted. */
    @Transactional
    public long clearCompleted() {
        long count = repository.countByCompleted(true);
        repository.deleteByCompletedTrue();
        return count;
    }

    /** Blank/whitespace-only optional text is stored as {@code null}, not "". */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
