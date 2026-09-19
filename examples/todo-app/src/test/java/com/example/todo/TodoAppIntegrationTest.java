package com.example.todo;

import com.example.todo.todo.TodoResponse;
import com.example.todo.todo.TodoStats;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the Todo REST API over HTTP.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "todo.seed-demo-data=false",
                "spring.datasource.url=jdbc:h2:mem:app-it;DB_CLOSE_DELAY=-1",
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TodoAppIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @Order(1)
    void fullCrudFlowOverHttp() {
        ResponseEntity<TodoResponse> created = rest.postForEntity(
                "/api/todos",
                Map.of("title", "integration todo", "description", "made by a test"),
                TodoResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        Long id = created.getBody().id();

        TodoResponse[] all = rest.getForObject("/api/todos", TodoResponse[].class);
        assertThat(all).extracting(TodoResponse::title).contains("integration todo");

        ResponseEntity<TodoResponse> toggled = rest.exchange(
                "/api/todos/" + id + "/toggle", HttpMethod.PATCH, HttpEntity.EMPTY, TodoResponse.class);
        assertThat(toggled.getBody().completed()).isTrue();

        TodoStats stats = rest.getForObject("/api/todos/stats", TodoStats.class);
        assertThat(stats.completed()).isEqualTo(1);

        rest.delete("/api/todos/" + id);
        assertThat(rest.getForEntity("/api/todos/" + id, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(2)
    void validationErrorsAreProblemDetails() {
        ResponseEntity<Map> response = rest.postForEntity("/api/todos", Map.of("title", "  "), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("title")).isEqualTo("Invalid request");
        assertThat((Map<String, String>) response.getBody().get("errors")).containsKey("title");
    }
}
