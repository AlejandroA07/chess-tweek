package com.chesstweek.composition.web;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The interface matches on server error codes, so every code it names must exist on the server. */
class TrainingErrorCodesTest {

    private static final Path FRONTEND_CODES = Path.of("frontend/src/trainingErrorCodes.ts");
    private static final Pattern ENTRY = Pattern.compile("^\\s*\\w+: '([a-z_]+)',$");

    @Test
    void everyCodeTheInterfaceReadsIsDefinedByTheServer() throws IOException {
        List<String> frontend = Files.readAllLines(FRONTEND_CODES).stream()
                .map(ENTRY::matcher)
                .filter(java.util.regex.Matcher::matches)
                .map(matcher -> matcher.group(1))
                .toList();

        assertThat(frontend).isNotEmpty().doesNotHaveDuplicates();
        assertThat(serverCodes()).containsAll(frontend);
    }

    private static Set<String> serverCodes() {
        return Arrays.stream(TrainingErrorCodes.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .map(TrainingErrorCodesTest::value)
                .collect(Collectors.toSet());
    }

    private static String value(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        }
    }
}
