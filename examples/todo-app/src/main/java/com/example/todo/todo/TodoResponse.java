package com.example.todo.todo;

import java.time.Instant;
import java.time.LocalDate;

/**
 * API representation of a {@link Todo}.
 */
public record TodoResponse(
        Long id,
        String title,
        String description,
        boolean completed,
        Instant createdAt,
        Instant completedAt,
        LocalDate dueDate,
        boolean overdue
) {

    public static TodoResponse from(Todo todo) {
        return new TodoResponse(
                todo.getId(),
                todo.getTitle(),
                todo.getDescription(),
                todo.isCompleted(),
                todo.getCreatedAt(),
                todo.getCompletedAt(),
                todo.getDueDate(),
                todo.isOverdue()
        );
    }
}
