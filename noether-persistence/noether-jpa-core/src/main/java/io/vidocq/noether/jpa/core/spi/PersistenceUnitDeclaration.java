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
package io.vidocq.noether.jpa.core.spi;

import jakarta.persistence.PersistenceUnitTransactionType;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A persistence unit as a {@link PersistenceUnitSource} declares it. Its attributes fall in two groups:
 *
 * <ul>
 *   <li>those that <em>declare</em> the unit, what a {@code persistence.xml} would say: its root, the packages and the
 *       classes it manages, its transaction type, qualifiers and scope. A declaration that sets one of them declares
 *       the unit, and a unit of the same name in a {@code persistence.xml} or another source fails the start as a
 *       duplicate;</li>
 *   <li>those that <em>override</em> it: its data sources and its properties, which differ between deployments. A
 *       declaration that sets only these overrides the unit of that name when a {@code persistence.xml} declares it,
 *       and declares it otherwise, at its {@linkplain Builder#defaultRoot default root} and with its
 *       {@linkplain Builder#defaultTransactionType default transaction type}.</li>
 * </ul>
 *
 * <p>A unit with neither a root nor classes manages nothing. One that lists its classes manages those alone, as a
 * {@code persistence.xml} unit with {@code <exclude-unlisted-classes>}.
 */
public final class PersistenceUnitDeclaration {

    private final String name;
    private final String origin;
    private final URL root;
    private final URL defaultRoot;
    private final List<String> packages;
    private final List<String> classes;
    private final PersistenceUnitTransactionType transactionType;
    private final PersistenceUnitTransactionType defaultTransactionType;
    private final List<String> qualifiers;
    private final String scope;
    private final String dataSource;
    private final String jtaDataSource;
    private final String nonJtaDataSource;
    private final Map<String, Object> properties;
    private final boolean whenAlone;

    private PersistenceUnitDeclaration(Builder builder) {
        this.name = builder.name;
        this.origin = builder.origin;
        this.root = builder.root;
        this.defaultRoot = builder.defaultRoot;
        this.packages = List.copyOf(builder.packages);
        this.classes = List.copyOf(builder.classes);
        this.transactionType = builder.transactionType;
        this.defaultTransactionType = builder.defaultTransactionType;
        this.qualifiers = List.copyOf(builder.qualifiers);
        this.scope = builder.scope;
        this.dataSource = builder.dataSource;
        this.jtaDataSource = builder.jtaDataSource;
        this.nonJtaDataSource = builder.nonJtaDataSource;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(builder.properties));
        this.whenAlone = builder.whenAlone;
    }

    /**
     * Starts the declaration of a unit.
     *
     * @param name the name of the unit
     * @param origin where the declaration comes from, as an error message names it (the
     *        {@code vidocq.persistence.ledger.*} keys, for instance)
     */
    public static Builder builder(String name, String origin) {
        return new Builder(name, origin);
    }

    /** The name of the unit. */
    public String name() {
        return name;
    }

    /** Where the declaration comes from, as an error message names it. */
    public String origin() {
        return origin;
    }

    /** Whether this declaration declares the unit, rather than only overriding its data sources and properties. */
    public boolean declares() {
        return whenAlone || Stream.of(root, transactionType, scope).anyMatch(Objects::nonNull)
            || Stream.of(packages, classes, qualifiers).anyMatch(list -> !list.isEmpty());
    }

    /** The root whose classes the unit manages, if the declaration names one. */
    public Optional<URL> root() {
        return Optional.ofNullable(root);
    }

    /** The root of the unit when this declaration declares it without naming one. */
    public Optional<URL> defaultRoot() {
        return Optional.ofNullable(defaultRoot);
    }

    /** The packages of the root the unit is restricted to, with their sub-packages; empty for the whole root. */
    public List<String> packages() {
        return packages;
    }

    /** The classes the unit manages, alone; empty when it manages the classes of its root. */
    public List<String> classes() {
        return classes;
    }

    /** The transaction type of the unit, if the declaration sets one. */
    public Optional<PersistenceUnitTransactionType> transactionType() {
        return Optional.ofNullable(transactionType);
    }

    /** The transaction type of the unit when this declaration declares it without setting one. */
    public Optional<PersistenceUnitTransactionType> defaultTransactionType() {
        return Optional.ofNullable(defaultTransactionType);
    }

    /** The qualifier annotations of the unit's beans, by their qualified names (§9.4.1). */
    public List<String> qualifiers() {
        return qualifiers;
    }

    /** The scope annotation of the unit's entity manager bean, by its qualified name, if the declaration sets one. */
    public Optional<String> scope() {
        return Optional.ofNullable(scope);
    }

    /**
     * The name of the data source of the unit by its transaction type, if the declaration sets one: the JTA data source
     * of a JTA unit, the non-JTA one of a resource-local unit. {@link #jtaDataSource} and {@link #nonJtaDataSource} win
     * over it.
     */
    public Optional<String> dataSource() {
        return Optional.ofNullable(dataSource);
    }

    /** The name of the JTA data source, if the declaration sets one. */
    public Optional<String> jtaDataSource() {
        return Optional.ofNullable(jtaDataSource);
    }

    /** The name of the non-JTA data source, if the declaration sets one. */
    public Optional<String> nonJtaDataSource() {
        return Optional.ofNullable(nonJtaDataSource);
    }

    /** The properties of the unit, which win over those of a {@code persistence.xml} that declares it. */
    public Map<String, Object> properties() {
        return properties;
    }

    /**
     * Whether the unit is declared only when the application declares no other unit, and its root holds a managed
     * class: the default unit of a runtime.
     */
    public boolean whenAlone() {
        return whenAlone;
    }

    @Override
    public String toString() {
        return "persistence unit " + name + " (" + origin + ")";
    }

    /** Collects the attributes of a declaration. */
    public static final class Builder {

        private final String name;
        private final String origin;
        private URL root;
        private URL defaultRoot;
        private final List<String> packages = new ArrayList<>();
        private final List<String> classes = new ArrayList<>();
        private PersistenceUnitTransactionType transactionType;
        private PersistenceUnitTransactionType defaultTransactionType;
        private final List<String> qualifiers = new ArrayList<>();
        private String scope;
        private String dataSource;
        private String jtaDataSource;
        private String nonJtaDataSource;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private boolean whenAlone;

        private Builder(String name, String origin) {
            this.name = Objects.requireNonNull(name, "name");
            this.origin = Objects.requireNonNull(origin, "origin");
        }

        /** Declares the unit at this root: a directory or an archive of class files. */
        public Builder root(URL root) {
            this.root = Objects.requireNonNull(root, "root");
            return this;
        }

        /** The root of the unit if the declaration declares it without a {@link #root}; it declares nothing. */
        public Builder defaultRoot(URL root) {
            this.defaultRoot = Objects.requireNonNull(root, "root");
            return this;
        }

        /** Restricts the unit to these packages of its root, and their sub-packages. */
        public Builder packages(List<String> packages) {
            this.packages.addAll(packages);
            return this;
        }

        /** The classes the unit manages, alone, by their binary names. */
        public Builder classes(List<String> classes) {
            this.classes.addAll(classes);
            return this;
        }

        public Builder transactionType(PersistenceUnitTransactionType transactionType) {
            this.transactionType = Objects.requireNonNull(transactionType, "transactionType");
            return this;
        }

        /**
         * The transaction type of the unit if the declaration declares it without a {@link #transactionType}; it
         * declares nothing, and leaves the type of a {@code persistence.xml} unit alone.
         */
        public Builder defaultTransactionType(PersistenceUnitTransactionType transactionType) {
            this.defaultTransactionType = Objects.requireNonNull(transactionType, "transactionType");
            return this;
        }

        /** The qualifier annotations of the unit's beans, by their qualified names. */
        public Builder qualifiers(List<String> qualifiers) {
            this.qualifiers.addAll(qualifiers);
            return this;
        }

        /** The scope annotation of the unit's entity manager bean, by its qualified name. */
        public Builder scope(String scope) {
            this.scope = Objects.requireNonNull(scope, "scope");
            return this;
        }

        /**
         * The data source of the unit by its transaction type, which the declaration may not know (a unit a
         * {@code persistence.xml} declares): its JTA data source if it is a JTA unit, its non-JTA one otherwise.
         */
        public Builder dataSource(String name) {
            this.dataSource = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder jtaDataSource(String name) {
            this.jtaDataSource = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder nonJtaDataSource(String name) {
            this.nonJtaDataSource = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder property(String key, Object value) {
            properties.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Declares the unit only if the application declares no other one and its root holds a managed class. */
        public Builder whenAlone() {
            this.whenAlone = true;
            return this;
        }

        public PersistenceUnitDeclaration build() {
            return new PersistenceUnitDeclaration(this);
        }
    }
}
