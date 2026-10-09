/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Canonical output belongs to the application's module, including partial recompilations (§6.2.1.1). */
class CanonicalLifecycleTest {
    @Test
    void packageLocalEmbeddablesAndExplicitRawTargetsCompile(@TempDir Path out) throws Exception {
        Path source = Files.createDirectories(out.resolve("source"));
        Files.writeString(source.resolve("One.java"), """
            package one;
            @jakarta.persistence.Entity public class One { @jakarta.persistence.Id public long id; }
            """);
        Files.writeString(source.resolve("Two.java"), """
            package two;
            @jakarta.persistence.Entity public class Two {
                @jakarta.persistence.Id public long id;
                @jakarta.persistence.Embedded Detail detail;
                @jakarta.persistence.ElementCollection(targetClass=String.class) java.util.Set values;
            }
            @jakarta.persistence.Embeddable class Detail { public String name; }
            """);
        Compilation compiled = Compilation.onClassPath(out.resolve("build"), source);
        assertThat(compiled.success()).as("%s", compiled.diagnostics()).isTrue();
        assertThat(compiled.source("two/Two_.java")).contains("SetAttribute<two.Two, java.lang.String> values");
    }

    @Test
    void ownedCanonicalOutputIsRegeneratedAndRetainedHelpersAreRegistered(@TempDir Path out) throws Exception {
        Path source = Files.createDirectories(out.resolve("source"));
        Path first = source.resolve("First.java");
        Files.writeString(first, entity("First", ""));
        Files.writeString(source.resolve("Second.java"), entity("Second", ""));
        Compilation original = Compilation.onClassPath(out.resolve("first"), source);
        assertThat(original.success()).as("%s", original.diagnostics()).isTrue();
        Files.writeString(first, entity("First", "public String added;"));
        Compilation rebuilt = Compilation.incrementally(out.resolve("second"), original, first);
        assertThat(rebuilt.success()).as("%s", rebuilt.diagnostics()).isTrue();
        assertThat(rebuilt.source("sample/First_.java")).contains("SingularAttribute<sample.First, java.lang.String> added");
        assertThat(rebuilt.source("sample/_MansartJpaAccess.java")).contains("Second_$$MansartMetamodel.populate");
    }

    private static String entity(String name, String extra) {
        return "package sample; @jakarta.persistence.Entity public class " + name
            + " { @jakarta.persistence.Id public long id; " + extra + " }";
    }
}
