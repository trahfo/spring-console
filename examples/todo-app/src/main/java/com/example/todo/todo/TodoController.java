package com.example.todo.todo;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * REST API consumed by the React frontend (see {@code frontend/src/api.ts}).
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService service;

    public TodoController(TodoService service) {
        this.service = service;
    }

    /** {@code GET /api/todos[?completed=…]} — newest first, optionally filtered. */
    @GetMapping
    public List<TodoResponse> list(@RequestParam(name = "completed", required = false) Boolean completed) {
        List<Todo> todos = completed == null ? service.findAll() : service.findByCompleted(completed);
        return todos.stream().map(TodoResponse::from).toList();
    }

    /** {@code GET /api/todos/stats} — the counters shown in the UI footer. */
    @GetMapping("/stats")
    public TodoStats stats() {
        return service.stats();
    }

    /** {@code GET /api/todos/{id}} — 404 when the id is unknown. */
    @GetMapping("/{id}")
    public TodoResponse get(@PathVariable Long id) {
        return TodoResponse.from(service.findById(id));
    }

    /** {@code POST /api/todos} — validates the body, returns 201 + Location. */
    @PostMapping
    public ResponseEntity<TodoResponse> create(@Valid @RequestBody TodoRequest request, UriComponentsBuilder uri) {
        Todo todo = service.create(request);
        return ResponseEntity
                .created(uri.path("/api/todos/{id}").buildAndExpand(todo.getId()).toUri())
                .body(TodoResponse.from(todo));
    }

    /** {@code PUT /api/todos/{id}} — replaces title, description, and due date. */
    @PutMapping("/{id}")
    public TodoResponse update(@PathVariable Long id, @Valid @RequestBody TodoRequest request) {
        return TodoResponse.from(service.update(id, request));
    }

    /** {@code PATCH /api/todos/{id}/toggle} — flips the completion state. */
    @PatchMapping("/{id}/toggle")
    public TodoResponse toggle(@PathVariable Long id) {
        return TodoResponse.from(service.toggle(id));
    }

    /** {@code DELETE /api/todos/{id}} — 204 on success, 404 when unknown. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** {@code DELETE /api/todos/completed} — bulk clear; returns the count. */
    @DeleteMapping("/completed")
    public Map<String, Long> clearCompleted() {
        return Map.of("deleted", service.clearCompleted());
    }
}
