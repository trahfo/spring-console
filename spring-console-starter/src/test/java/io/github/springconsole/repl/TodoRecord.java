package io.github.springconsole.repl;

/**
 * Java record fixture for {@code ResultRendererTest}.
 *
 * Records are the primary shape of the demo application's DTOs, and their
 * accessors ({@code id()}, {@code title()}) do not follow the JavaBeans
 * {@code getX()} convention. This fixture pins down the record-specific
 * rendering path.
 */
public record TodoRecord(long id, String title, boolean completed) {
}
