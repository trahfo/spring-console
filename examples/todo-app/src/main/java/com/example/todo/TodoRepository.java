package com.example.todo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data JPA repository for {@link Todo}.
 *
 * <p>All methods are <em>derived queries</em>: Spring Data parses the method
 * name and generates the JPQL, so there is no implementation to maintain.
 * The console's {@code :schema} command lists exactly these methods and the
 * managed domain type, which is how an agent can discover the query surface
 * without reading the source.
 *
 * <p>The repository bean is bound into the REPL as {@code todoRepository}
 * (behind a JDK dynamic proxy, which the console unwraps for introspection).
 */
public interface TodoRepository extends JpaRepository<Todo, Long> {

    /** Newest first — the order the UI list uses. */
    List<Todo> findAllByOrderByCreatedAtDesc();

    /** Filter by completion state, newest first. */
    List<Todo> findByCompletedOrderByCreatedAtDesc(boolean completed);

    /** Active items whose deadline has passed; backs the "overdue" counter. */
    List<Todo> findByCompletedFalseAndDueDateBefore(LocalDate date);

    /** Count items in one completion state, without loading them. */
    long countByCompleted(boolean completed);

    /** Bulk delete of every completed item; used by "clear completed". */
    void deleteByCompletedTrue();
}
