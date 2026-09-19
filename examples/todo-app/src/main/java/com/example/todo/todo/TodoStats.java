package com.example.todo.todo;

/**
 * Aggregate counts shown in the UI footer.
 */
public record TodoStats(long total, long active, long completed, long overdue) {
}
