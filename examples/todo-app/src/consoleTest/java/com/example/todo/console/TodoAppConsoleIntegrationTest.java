package com.example.todo.console;

import com.example.todo.Todo;
import com.example.todo.TodoRepository;
import com.example.todo.TodoResponse;
import io.github.springconsole.ConsoleRuntime;
import io.github.springconsole.ConsoleService;
import io.github.springconsole.api.EvalResult;
import io.github.springconsole.api.EvalStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Segregated integration tests verifying spring-console capabilities (interactive
 * evaluation, proxy unwrapping, bean binding, mutations, inspection) against the
 * live Todo application context.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "todo.seed-demo-data=true",
                "spring.datasource.url=jdbc:h2:mem:console-it;DB_CLOSE_DELAY=-1",
                "spring-console.mcp.port=0",
                "spring-console.warmup=false",
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TodoAppConsoleIntegrationTest {

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private TestRestTemplate rest;

    @AfterAll
    static void cleanupConsole() {
        ConsoleRuntime.INSTANCE.shutdown();
    }

    private ConsoleService console() {
        ConsoleService cs = ConsoleRuntime.INSTANCE.console();
        assertThat(cs).as("ConsoleService should be attached").isNotNull();
        return cs;
    }

    @Test
    @Order(1)
    void springConsoleIsAttachedAndCanDriveTheApp() {
        assertThat(console()).as("console attaches on startup").isNotNull();

        EvalResult result = console()
                .eval("todoService.create(com.example.todo.TodoRequest(\"from console\", null, null)).id", null);
        assertThat(result.getStatus()).as("console eval failed: %s", result).isEqualTo(EvalStatus.SUCCESS);

        TodoResponse[] all = rest.getForObject("/api/todos", TodoResponse[].class);
        assertThat(all).extracting(TodoResponse::title)
                .as("console eval should persist changes directly to the running application")
                .contains("from console");
    }

    @Test
    @Order(2)
    void namespaceAutoImportAllowsUnqualifiedDomainClassAndPreventsLazyInitializationException() {
        EvalResult r1 = console().eval("Todo t = todoRepository.findById(1L).get();");
        assertThat(r1.getStatus()).as("Eval should succeed: %s", r1).isEqualTo(EvalStatus.SUCCESS);

        EvalResult r2 = console().eval("t.title");
        assertThat(r2.getStatus()).as("Accessing property should succeed: %s", r2).isEqualTo(EvalStatus.SUCCESS);
    }

    @Test
    @Order(3)
    void useCase1_loadTodoChangeTitleSave_persistsToDatabase() {
        EvalResult evalResult = console().eval(
                "Todo t1 = todoRepository.findById(1L).get();\n" +
                "t1.setTitle(\"Updated Title Persisted\");\n" +
                "todoRepository.save(t1);"
        );
        assertThat(evalResult.getStatus()).as("Eval failed: %s", evalResult).isEqualTo(EvalStatus.SUCCESS);

        Todo fromDb = todoRepository.findById(1L).orElseThrow();
        assertThat(fromDb.getTitle()).isEqualTo("Updated Title Persisted");
    }

    @Test
    @Order(4)
    void useCase2_loadTodoDeleteTodo_persistsToDatabase() {
        assertThat(todoRepository.existsById(2L)).isTrue();

        EvalResult evalResult = console().eval(
                "Todo t2 = todoRepository.findById(2L).get();\n" +
                "todoRepository.delete(t2);"
        );
        assertThat(evalResult.getStatus()).as("Eval failed: %s", evalResult).isEqualTo(EvalStatus.SUCCESS);

        assertThat(todoRepository.existsById(2L)).isFalse();
    }

    @Test
    @Order(5)
    void useCase3_loadTodoToggleCompletedState_persistsToDatabase() {
        Todo initial = todoRepository.findById(3L).orElseThrow();
        boolean initialCompleted = initial.isCompleted();

        EvalResult evalResult = console().eval(
                "Todo t3 = todoRepository.findById(3L).get();\n" +
                "t3.setCompleted(!t3.isCompleted());\n" +
                "todoRepository.save(t3);"
        );
        assertThat(evalResult.getStatus()).as("Eval failed: %s", evalResult).isEqualTo(EvalStatus.SUCCESS);

        Todo updated = todoRepository.findById(3L).orElseThrow();
        assertThat(updated.isCompleted()).isEqualTo(!initialCompleted);
    }

    @Test
    @Order(6)
    void variableInspectionOffersAllMethodsAndPropertiesOfTodo() {
        EvalResult declResult = console().eval("Todo t = todoRepository.findById(4L).get();");
        assertThat(declResult.getStatus()).isEqualTo(EvalStatus.SUCCESS);

        var details = console().inspectClass("t", Todo.class);
        assertThat(details).isNotNull();

        List<String> methodNames = details.getMethods().stream().map(m -> m.getName()).toList();
        List<String> propertyNames = details.getProperties().stream().map(p -> p.getName()).toList();

        assertThat(methodNames).contains(
                "getId",
                "getTitle",
                "setTitle",
                "getDescription",
                "setDescription",
                "isCompleted",
                "setCompleted",
                "getCreatedAt",
                "getCompletedAt",
                "getDueDate",
                "setDueDate",
                "isOverdue"
        );

        assertThat(propertyNames).contains(
                "id",
                "title",
                "description",
                "completed",
                "createdAt",
                "completedAt",
                "dueDate",
                "overdue"
        );
    }
}
