package com.example.todo.todo;

/**
 * Thrown when a todo id does not exist. Mapped to an HTTP 404 problem detail by
 * {@code GlobalExceptionHandler}; in the console it surfaces as a structured
 * {@code RUNTIME_EXCEPTION} whose pruned stack trace still points at the
 * failing snippet line.
 */
public class TodoNotFoundException extends RuntimeException {

    public TodoNotFoundException(Long id) {
        super("No todo with id " + id);
    }
}
