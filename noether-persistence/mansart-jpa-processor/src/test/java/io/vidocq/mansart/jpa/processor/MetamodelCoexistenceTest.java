/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.processor;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetamodelCoexistenceTest {
    @Test
    void incompatibleProducerFieldsAreDiagnosedWithoutOverwritingItsClass(@TempDir Path out) { // §6.2.1.1
        var compilation=Compilation.withProcessors(out,List.of(new EarlyCanonical(true),new MansartJpaProcessor()),
            Compilation.FIXTURES.resolve("coexist"));
        assertThat(compilation.success()).as("%s",compilation.diagnostics()).isTrue();
        assertThat(compilation.messages(javax.tools.Diagnostic.Kind.WARNING)).anySatisfy(message ->
            assertThat(message).contains("Book_.tags").contains("ListAttribute").contains("does not overwrite"));
        assertThat(compilation.source("coexist/Book_.java")).contains("SingularAttribute<Book,java.util.List<String>> tags");
        assertThat(compilation.source("coexist/Book_$$MansartMetamodel.java")).doesNotContain("Book_.tags =");
    }
    @Test
    void aClaimingProducerCanOwnTheCanonicalClassBeforeOrAfterJpa(@TempDir Path out) { // §6.2.1.1, Data coexistence
        for (boolean first:List.of(true,false)) {
            Processor other=new EarlyCanonical();
            Processor jpa=new MansartJpaProcessor();
            var compilation=Compilation.withProcessors(out.resolve(Boolean.toString(first)),
                first?List.of(other,jpa):List.of(jpa,other),Compilation.FIXTURES.resolve("coexist"));
            assertThat(compilation.success()).as("%s",compilation.diagnostics()).isTrue();
            assertThat(compilation.source("coexist/Book_.java")).contains("Owned by the other producer");
            assertThat(compilation.generated().resolve("coexist/Book$$MansartAccess.java")).exists();
            assertThat(compilation.source("coexist/_MansartJpaAccess.java")).contains("populateMetamodel");
        }
    }
    private static final class EarlyCanonical extends AbstractProcessor {
        boolean emitted;
        final boolean plural;
        EarlyCanonical() { this(false); }
        EarlyCanonical(boolean plural) { this.plural=plural; }
        @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("jakarta.persistence.Entity"); }
        @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
        @Override public boolean process(Set<? extends TypeElement> annotations,RoundEnvironment round) {
            if (!emitted && !round.processingOver()) {
                emitted=true;
                try (var writer=processingEnv.getFiler().createSourceFile("coexist.Book_").openWriter()) {
                    writer.write("""
                        package coexist;
                        // Owned by the other producer.
                        @jakarta.persistence.metamodel.StaticMetamodel(Book.class)
                        public abstract class Book_ {
                            public static volatile jakarta.persistence.metamodel.SingularAttribute<Book,Long> id;
                            public static volatile jakarta.persistence.metamodel.SingularAttribute<Book,String> title;
                            %s
                        }
                        """.formatted(plural?"public static volatile jakarta.persistence.metamodel.SingularAttribute<Book,java.util.List<String>> tags;":""));
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }
            return true;
        }
    }
}
