package com.qcharge.openadr.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EventJaxbIsolationTest {

    private static final Path MAIN_SOURCE = Path.of("src/main/java/com/qcharge/openadr");
    private static final List<Path> APPLICATION_PATHS = List.of(
            MAIN_SOURCE.resolve("service/event/command"),
            MAIN_SOURCE.resolve("service/event/execution"),
            MAIN_SOURCE.resolve("service/event/mapping"),
            MAIN_SOURCE.resolve("service/event/processing"),
            MAIN_SOURCE.resolve("service/event/store"),
            MAIN_SOURCE.resolve("service/event/EventOptDecisionService.java"),
            MAIN_SOURCE.resolve("service/event/EventValidationService.java"),
            MAIN_SOURCE.resolve("service/resource/EventResourceResolver.java")
    );

    @Test
    void eventApplicationLayerDoesNotDependOnJaxbProtocolTypes() throws IOException {
        for (Path applicationPath : APPLICATION_PATHS) {
            if (Files.isDirectory(applicationPath)) {
                try (var files = Files.walk(applicationPath)) {
                    files.filter(path -> path.toString().endsWith(".java"))
                            .forEach(this::assertNoProtocolImports);
                }
            } else {
                assertNoProtocolImports(applicationPath);
            }
        }
    }

    private void assertNoProtocolImports(Path sourceFile) {
        try {
            String source = Files.readString(sourceFile);
            assertTrue(
                    !source.contains("com.qcharge.openadr.model.oadr20b")
                            && !source.contains("jakarta.xml.bind"),
                    () -> "JAXB/OpenADR protocol type leaked into application layer: " + sourceFile
            );
        } catch (IOException exception) {
            throw new AssertionError("Cannot inspect " + sourceFile, exception);
        }
    }
}
