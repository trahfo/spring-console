package com.example.todo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Seeds a few demo todos on an empty database so the UI has something to play with.
 * Disable with {@code todo.seed-demo-data=false}.
 */
@Component
@ConditionalOnProperty(name = "todo.seed-demo-data", havingValue = "true", matchIfMissing = true)
public class TodoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TodoDataSeeder.class);

    private final TodoRepository repository;

    public TodoDataSeeder(TodoRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        repository.save(new Todo(
                "Set up development environment",
                "Install Java 17 and configure IDE plugins.",
                null));
        repository.save(new Todo(
                "Review pull request for login flow",
                "Check security filters and token validation rules.",
                null));
        repository.save(new Todo(
                "Write integration tests",
                "Verify edge cases and validation error responses.",
                LocalDate.now().plusDays(3)));
        Todo done = new Todo("Read the documentation", null, null);
        done.setCompleted(true);
        repository.save(done);
        log.info("Seeded {} demo todos", repository.count());
    }
}
