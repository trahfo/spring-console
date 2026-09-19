package com.example.todo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TodoService.class)
class TodoServiceTest {

    @Autowired
    private TodoService service;

    @Autowired
    private TodoRepository repository;

    private Todo create(String title) {
        return service.create(new TodoRequest(title, null, null));
    }

    @Test
    void createTrimsTitleAndNormalizesBlankDescription() {
        Todo todo = service.create(new TodoRequest("  Buy milk  ", "   ", null));

        assertThat(todo.getId()).isNotNull();
        assertThat(todo.getTitle()).isEqualTo("Buy milk");
        assertThat(todo.getDescription()).isNull();
        assertThat(todo.isCompleted()).isFalse();
        assertThat(todo.getCreatedAt()).isNotNull();
    }

    @Test
    void findAllReturnsNewestFirst() {
        create("first");
        create("second");

        assertThat(service.findAll()).extracting(Todo::getTitle).containsExactly("second", "first");
    }

    @Test
    void toggleFlipsCompletionAndTracksTimestamp() {
        Todo todo = create("toggle me");

        Todo completed = service.toggle(todo.getId());
        assertThat(completed.isCompleted()).isTrue();
        assertThat(completed.getCompletedAt()).isNotNull();

        Todo reopened = service.toggle(todo.getId());
        assertThat(reopened.isCompleted()).isFalse();
        assertThat(reopened.getCompletedAt()).isNull();
    }

    @Test
    void updateReplacesMutableFields() {
        Todo todo = create("old title");

        service.update(todo.getId(), new TodoRequest("new title", "details", LocalDate.of(2030, 1, 1)));

        Todo reloaded = service.findById(todo.getId());
        assertThat(reloaded.getTitle()).isEqualTo("new title");
        assertThat(reloaded.getDescription()).isEqualTo("details");
        assertThat(reloaded.getDueDate()).isEqualTo(LocalDate.of(2030, 1, 1));
    }

    @Test
    void statsCountsActiveCompletedAndOverdue() {
        service.create(new TodoRequest("overdue", null, LocalDate.now().minusDays(1)));
        create("active");
        service.toggle(create("done").getId());

        TodoStats stats = service.stats();

        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.active()).isEqualTo(2);
        assertThat(stats.completed()).isEqualTo(1);
        assertThat(stats.overdue()).isEqualTo(1);
    }

    @Test
    void completedTodosAreNeverOverdue() {
        Todo todo = service.create(new TodoRequest("was overdue", null, LocalDate.now().minusDays(1)));
        service.toggle(todo.getId());

        assertThat(service.findOverdue()).isEmpty();
        assertThat(service.findById(todo.getId()).isOverdue()).isFalse();
    }

    @Test
    void clearCompletedDeletesOnlyCompletedTodos() {
        create("keep me");
        service.toggle(create("done 1").getId());
        service.toggle(create("done 2").getId());

        long deleted = service.clearCompleted();

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(service.findAll()).extracting(Todo::getTitle).containsExactly("keep me");
    }

    @Test
    void deleteRemovesTheTodo() {
        Todo todo = create("delete me");
        service.delete(todo.getId());
        assertThat(repository.existsById(todo.getId())).isFalse();
    }

    @Test
    void missingIdsRaiseTodoNotFound() {
        assertThatThrownBy(() -> service.findById(9999L)).isInstanceOf(TodoNotFoundException.class);
        assertThatThrownBy(() -> service.delete(9999L)).isInstanceOf(TodoNotFoundException.class);
        assertThatThrownBy(() -> service.toggle(9999L)).isInstanceOf(TodoNotFoundException.class);
        assertThatThrownBy(() -> service.update(9999L, new TodoRequest("x", null, null)))
                .isInstanceOf(TodoNotFoundException.class);
    }
}
