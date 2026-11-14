/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.tests.architecture;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.ReflectionExceptions;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Noether's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Noether's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.noether.jpa.dialect} and {@code .dialect.sql}
 * (noether-jpa-dialect-spi), {@code .dialect.h2} and {@code .dialect.postgresql} (the dialects),
 * {@code io.vidocq.noether.jpa.core} (noether-jpa-core), {@code .pool.mansart} (noether-jpa-pool-mansart),
 * {@code .processor} (noether-jpa-processor), {@code .jta} (noether-jpa-jta) and {@code .cdi} (noether-jpa-cdi).
 *
 * <p>Beyond the JDK runtime packages, the modules read {@code javax.sql} (module {@code java.sql}) and
 * {@code javax.naming} ({@code java.naming}), listed where they are used.
 */
final class ArchitectureRules {

    private static final String AGENTS = "noether-persistence/AGENTS.md";

    private static final String[] DIALECT_SPI = {"io.vidocq.noether.jpa.dialect", "io.vidocq.noether.jpa.dialect.sql"};

    private static final String[] DIALECTS = {
        "io.vidocq.noether.jpa.dialect.h2..", "io.vidocq.noether.jpa.dialect.postgresql.."
    };

    private static final String[] CORE = {"io.vidocq.noether.jpa.core.."};

    private static final String[] POOL_MANSART = {"io.vidocq.noether.jpa.pool.mansart.."};

    private static final String[] PROCESSOR = {"io.vidocq.noether.jpa.processor.."};

    private static final String[] JTA = {"io.vidocq.noether.jpa.jta.."};

    private static final String[] CDI = {"io.vidocq.noether.jpa.cdi.."};

    /** noether-jpa-dialect-spi: the SQL dialect contract and its neutral SQL model, on JDBC (module-info.java). */
    static final ArchRule DIALECT_SPI_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            DIALECT_SPI,
            Packages.of(Jdk.runtimePackages(), "javax.sql..", "io.vidocq.noether.jpa.dialect",
                    "io.vidocq.noether.jpa.dialect.sql"),
            "workspace AGENTS.md, zero external dependency; module-info.java of noether-jpa-dialect-spi");

    /** The two dialects: the dialect SPI alone (module-info.java). */
    static final ArchRule DIALECT_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            DIALECTS,
            Packages.of(Jdk.runtimePackages(), "javax.sql..", "io.vidocq.noether.jpa.dialect",
                    "io.vidocq.noether.jpa.dialect.sql", "io.vidocq.noether.jpa.dialect.h2..",
                    "io.vidocq.noether.jpa.dialect.postgresql.."),
            "workspace AGENTS.md, zero external dependency; module-info.java of the dialect modules");

    /**
     * noether-jpa-core: the Jakarta Persistence API, the Bean Validation API ({@code requires static}: the
     * validation of an entity runs only when a provider is present), JDBC, the JDK's XML parser for
     * {@code persistence.xml} and {@code orm.xml}, and the dialect SPI (module-info.java).
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            CORE,
            Packages.of(Jdk.runtimePackages(), "javax.sql..", "jakarta.persistence..", "jakarta.validation..",
                    "io.vidocq.noether.jpa.dialect", "io.vidocq.noether.jpa.dialect.sql",
                    "io.vidocq.noether.jpa.core.."),
            "workspace AGENTS.md, a brick depends on the APIs it implements; module-info.java of noether-jpa-core");

    /** noether-jpa-pool-mansart: noether-jpa-core's pool SPI over Mansart's pool, the one Vidocq brick it reads. */
    static final ArchRule POOL_MANSART_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            POOL_MANSART,
            Packages.of(Jdk.runtimePackages(), "javax.sql..", "io.vidocq.mansart.pool..",
                    "io.vidocq.noether.jpa.core..", "io.vidocq.noether.jpa.pool.mansart.."),
            "workspace AGENTS.md, dependency graph; module-info.java of noether-jpa-pool-mansart");

    /** noether-jpa-processor: a build-time tool, {@code java.compiler} and the model of noether-jpa-core. */
    static final ArchRule PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            PROCESSOR,
            Packages.of(Jdk.compilerPackages(), "io.vidocq.noether.jpa.core..", "io.vidocq.noether.jpa.processor.."),
            "workspace AGENTS.md, maximum static code generation; module-info.java of noether-jpa-processor");

    /** noether-jpa-jta: the Jakarta Transactions bridge of noether-jpa-core, found through JNDI or given. */
    static final ArchRule JTA_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            JTA,
            Packages.of(Jdk.runtimePackages(), "javax.sql..", "javax.naming..", "jakarta.persistence..",
                    "jakarta.transaction..", "io.vidocq.noether.jpa.core..", "io.vidocq.noether.jpa.jta.."),
            "workspace AGENTS.md, a brick depends on APIs; module-info.java of noether-jpa-jta");

    /**
     * noether-jpa-cdi: the CDI, Inject, Persistence and Transactions APIs, noether-jpa-core and noether-jpa-jta.
     * Container-neutral: no Vauban, which the Vidocq runtime extension brings (module-info.java).
     */
    static final ArchRule CDI_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            CDI,
            Packages.of(Jdk.runtimePackages(), "javax.sql..", "jakarta.enterprise..", "jakarta.inject..",
                    "jakarta.annotation..", "jakarta.persistence..", "jakarta.transaction..",
                    "io.vidocq.noether.jpa.core..", "io.vidocq.noether.jpa.jta..", "io.vidocq.noether.jpa.cdi.."),
            "workspace AGENTS.md, a brick depends on APIs; module-info.java of noether-jpa-cdi");

    /**
     * The module descriptors: the dialect SPI is the bottom, read by the core and the dialects; the core is read by
     * the adapters (pool, processor, JTA, CDI); the JTA bridge by the CDI integration alone; the dialects, the
     * pool adapter, the processor and the CDI integration by nothing.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "module-info.java files; " + AGENTS,
            Layer.named("Dialect SPI").in(DIALECT_SPI).accessesNoLayer().accessibleOnlyBy("Dialects", "Core",
                    "Pool adapter", "Processor", "JTA", "CDI"),
            Layer.named("Dialects").in(DIALECTS).accessibleByNone(),
            Layer.named("Core").in(CORE).accessibleOnlyBy("Pool adapter", "Processor", "JTA", "CDI"),
            Layer.named("Pool adapter").in(POOL_MANSART).accessibleByNone(),
            Layer.named("Processor").in(PROCESSOR).accessibleByNone(),
            Layer.named("JTA").in(JTA).accessibleOnlyBy("CDI"),
            Layer.named("CDI").in(CDI).accessibleByNone());

    /**
     * Workspace AGENTS.md, dependency graph: Noether reads no foundational brick. noether-jpa-cdi stays
     * container-neutral (no Vauban at all: the Vidocq runtime extension assembles it with Vauban), and nothing
     * reads Chappe or Champollion.
     */
    static final ArchRule FOUNDATIONAL_BRICKS = VidocqArchRules.neverDependOn(
            new String[] {"io.vidocq.noether.."},
            new String[] {"io.vidocq.chappe..", "io.vidocq.champollion..", "io.vidocq.vauban.."},
            "workspace AGENTS.md, dependency graph; a brick depends on APIs, its Vidocq runtime extension assembles");

    /** Workspace AGENTS.md, dependency graph: Mansart's pool is read by its adapter alone. */
    static final ArchRule MANSART_THROUGH_ITS_ADAPTER = VidocqArchRules.neverDependOn(
            join(DIALECT_SPI, DIALECTS, CORE, PROCESSOR, JTA, CDI),
            new String[] {"io.vidocq.mansart.."},
            "workspace AGENTS.md, dependency graph; module-info.java of noether-jpa-pool-mansart");

    private static final String START_TIME_PATH = "the start-time path, which serves the managed classes"
            + " noether-jpa-processor did not (an archive it did not process, an orm.xml that changes a mapping;"
            + " internals.adoc, Generated code)";

    /**
     * The documented reflective fallbacks, each with its reason (internals.adoc, "Reflective fallbacks"). Nested
     * classes share the entry of their top-level class. The first group is the start-time path, which the code
     * noether-jpa-processor generates replaces; the others read a class that only a name designates, in a query, a
     * configuration property or a serialized value, or hand a {@code java.lang.reflect} type over because the API
     * requires it. The shipped classes outside this list use no reflection.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.ofEntries(
            Map.entry("io.vidocq.noether.jpa.core.access.AccessGenerator", START_TIME_PATH
                    + ": it defines the lazy subclass of an entity once per class loader, which a later or a concurrent"
                    + " factory finds by name; a processed entity instantiates the subclass the processor wrote"),
            Map.entry("io.vidocq.noether.jpa.core.model.build.RuntimeMetamodel", START_TIME_PATH
                    + ": it fills by name the canonical metamodel class X_ of an entity that has no generated"
                    + " X_$$NoetherMetamodel helper; and Attribute.getJavaMember() returns the java.lang.reflect.Member"
                    + " the API declares"),
            Map.entry("io.vidocq.noether.jpa.core.model.build.Types", "turns the class names of the model, read from"
                    + " the class files of the unit (ClassFileSource), into the classes of the unit's class loader"),
            Map.entry("io.vidocq.noether.jpa.core.query.Translator", "a JPQL string names the class of a constructor"
                    + " expression and of an enum literal (§4.8.2), and reaches the factory at run time"),
            Map.entry("io.vidocq.noether.jpa.core.query.ResultConstructors", "a constructor expression, a"
                    + " @ConstructorResult or a query typed with a non-entity class names a result class; its public"
                    + " constructor is picked by the types of the selected values (§4.8.2)"),
            Map.entry("io.vidocq.noether.jpa.core.bootstrap.BeanValidation", "probes by name whether the Bean"
                    + " Validation API, a requires static of noether-jpa-core, and a provider are present: validation"
                    + " mode AUTO validates only then (§3.7.1)"),
            Map.entry("io.vidocq.noether.jpa.core.bootstrap.ValidationProviderBridge", "loads the validation groups"
                    + " that the jakarta.persistence.validation.group.pre-persist, pre-update and pre-remove"
                    + " properties name"),
            Map.entry("io.vidocq.noether.jpa.core.jdbc.ConnectionSources", "uses the driver that"
                    + " jakarta.persistence.jdbc.driver names directly: DriverManager refuses a driver that its"
                    + " caller's class loader cannot see, the case of a driver in an application's own loader"
                    + " (Javadoc of instantiate)"),
            Map.entry("io.vidocq.noether.jpa.core.jdbc.type.ValueBinders", "reads a Serializable basic value back:"
                    + " the serialized form names its classes, resolved against the unit's class loader"
                    + " (UnitObjectInputStream)"),
            Map.entry("io.vidocq.noether.jpa.cdi.UnitAnnotations", "loads the qualifiers and the scope that the"
                    + " <qualifier> and <scope> elements of persistence.xml name, and checks that they are"
                    + " annotated @Qualifier, @Scope or @NormalScope"));

    /** Workspace AGENTS.md, "Maximum static code generation"; CLAUDE.md, entity access. */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            inClasses(REFLECTIVE_FALLBACKS),
            "workspace AGENTS.md, static code generation; internals.adoc, Reflective fallbacks");

    /**
     * The classes beyond the reflective fallbacks that reach a member of user code through {@code java.lang.invoke},
     * each with its reason (internals.adoc, "Reflective fallbacks"). Nested classes share the entry of their
     * top-level class.
     */
    static final Map<String, String> DEEP_LOOKUP_ADAPTERS = Map.of(
            "io.vidocq.noether.jpa.core.access.Handles", "creates the converter that @Convert names (§3.9) and reads"
                    + " the value field of an @EnumeratedValue enum through a private lookup in their package, which"
                    + " the application opens to noether-jpa-core");

    /**
     * Workspace AGENTS.md, "Maximum static code generation": the {@code java.lang.invoke} route to the members of
     * user code, which {@link #NO_REFLECTION} does not see (Vidocq/vidocq-parent#34). A private lookup
     * ({@code MethodHandles.privateLookupIn}), a member or class found by name ({@code Lookup.find*}) or a
     * reflective member turned into a handle ({@code Lookup.unreflect*}) is reflection under another name: only
     * the reflective fallbacks and the {@link #DEEP_LOOKUP_ADAPTERS} use one. Defining a class
     * ({@code Lookup.defineHiddenClass}) and combining or invoking handles stay free. Goes once the shared rule
     * covers this route.
     */
    static final ArchRule NO_DEEP_LOOKUP = noDeepLookup(
            Stream.concat(REFLECTIVE_FALLBACKS.keySet().stream(), DEEP_LOOKUP_ADAPTERS.keySet().stream())
                    .collect(Collectors.toUnmodifiableSet()));

    /** The deep-lookup rule, sparing the given top-level classes and their nested classes. */
    static ArchRule noDeepLookup(Set<String> adapters) {
        DescribedPredicate<JavaClass> notAnAdapter = DescribedPredicate.describe(
                "are not a documented deep-lookup adapter", type -> !adapters.contains(topLevel(type).getName()));
        return noClasses()
                .that(notAnAdapter)
                .should()
                .callMethodWhere(target(owner(type(MethodHandles.class))).and(target(name("privateLookupIn"))))
                .orShould()
                .callMethodWhere(target(owner(type(MethodHandles.Lookup.class)))
                        .and(target(nameMatching("find.*|unreflect.*"))))
                .because("a private lookup, a member found by name or an unreflected member is reflection on user"
                        + " code, kept to the documented adapters (" + String.join(", ", new TreeSet<>(adapters))
                        + "); to document one, add it with its reason to DEEP_LOOKUP_ADAPTERS (workspace AGENTS.md,"
                        + " static code generation; internals.adoc, Reflective fallbacks; local until"
                        + " Vidocq/vidocq-parent#34)");
    }

    private static JavaClass topLevel(JavaClass type) {
        JavaClass top = type;
        Optional<JavaClass> enclosing = type.getEnclosingClass();
        while (enclosing.isPresent()) {
            top = enclosing.get();
            enclosing = top.getEnclosingClass();
        }
        return top;
    }

    /** Workspace AGENTS.md, "Virtual Threads everywhere for I/O". */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads");

    /** Workspace AGENTS.md, "Virtual Threads": an entity manager is not thread-safe and keeps no ThreadLocal. */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal neither follows a task across virtual threads nor caches anything; a persistence context"
                    + " belongs to its entity manager (Jakarta Persistence 3.2 §7.2)",
            "workspace AGENTS.md, Virtual Threads");

    private ArchitectureRules() {
        // Rules only.
    }

    private static ReflectionExceptions inClasses(Map<String, String> fallbacks) {
        ReflectionExceptions.Builder exceptions = ReflectionExceptions.builder();
        fallbacks.forEach(exceptions::inClass);
        return exceptions.build();
    }

    private static String[] join(String[]... packages) {
        return Stream.of(packages).flatMap(Stream::of).toArray(String[]::new);
    }
}
