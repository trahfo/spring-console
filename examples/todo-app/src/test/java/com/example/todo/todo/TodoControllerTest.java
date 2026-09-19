package com.example.todo.todo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TodoController.class)
class TodoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TodoService service;

    private Todo todo(long id, String title, boolean completed) {
        Todo todo = new Todo(title, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(todo, "id", id);
        org.springframework.test.util.ReflectionTestUtils.setField(todo, "createdAt", Instant.parse("2026-01-01T10:00:00Z"));
        if (completed) {
            todo.setCompleted(true);
        }
        return todo;
    }

    @Test
    void listsTodos() throws Exception {
        when(service.findAll()).thenReturn(List.of(todo(1, "write docs", false)));

        mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("write docs"))
                .andExpect(jsonPath("$[0].completed").value(false));
    }

    @Test
    void listsFilteredByCompletion() throws Exception {
        when(service.findByCompleted(true)).thenReturn(List.of(todo(2, "done thing", true)));

        mvc.perform(get("/api/todos").param("completed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].completed").value(true));

        verify(service).findByCompleted(true);
    }

    @Test
    void createsTodoWithLocationHeader() throws Exception {
        when(service.create(any())).thenReturn(todo(42, "new todo", false));

        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new todo\",\"dueDate\":\"2030-05-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/todos/42")))
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    void rejectsBlankTitlesWithProblemDetail() throws Exception {
        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.title").value("title must not be blank"));
    }

    @Test
    void mapsMissingTodosTo404ProblemDetail() throws Exception {
        when(service.findById(99L)).thenThrow(new TodoNotFoundException(99L));

        mvc.perform(get("/api/todos/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Todo not found"))
                .andExpect(jsonPath("$.detail").value("No todo with id 99"));
    }

    @Test
    void togglesCompletion() throws Exception {
        when(service.toggle(1L)).thenReturn(todo(1, "toggled", true));

        mvc.perform(patch("/api/todos/1/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void deletesWithNoContent() throws Exception {
        mvc.perform(delete("/api/todos/1")).andExpect(status().isNoContent());
        verify(service).delete(1L);
    }

    @Test
    void clearCompletedReportsCount() throws Exception {
        when(service.clearCompleted()).thenReturn(3L);

        mvc.perform(delete("/api/todos/completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3));
    }

    @Test
    void statsEndpointDelegatesToService() throws Exception {
        when(service.stats()).thenReturn(new TodoStats(5, 3, 2, 1));

        mvc.perform(get("/api/todos/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.active").value(3))
                .andExpect(jsonPath("$.completed").value(2))
                .andExpect(jsonPath("$.overdue").value(1));
    }

    @Test
    void overdueFlagIsExposedInResponses() throws Exception {
        Todo overdue = new Todo("late", null, LocalDate.now().minusDays(2));
        org.springframework.test.util.ReflectionTestUtils.setField(overdue, "id", 7L);
        org.springframework.test.util.ReflectionTestUtils.setField(overdue, "createdAt", Instant.now());
        when(service.findById(eq(7L))).thenReturn(overdue);

        mvc.perform(get("/api/todos/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(true));
    }
}
