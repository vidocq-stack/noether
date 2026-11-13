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

import io.vidocq.noether.jpa.core.bootstrap.PersistenceUnits;
import io.vidocq.noether.jpa.core.bootstrap.UnitSettings;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The persistence units a deployment declares, read by the provider's own {@code persistence.xml} parser, for the
 * container integrations: they never parse a descriptor themselves.
 */
public final class PersistenceDescriptors {

    /**
     * How a declared unit meets transactions (§8.2.1.2, §8.2.1.5).
     *
     * @param type the effective transaction type: the {@code jakarta.persistence.transactionType} property of the unit,
     *        else its {@code transaction-type} attribute
     * @param jtaDataSourceName the {@code jta-data-source} element, or {@code null} when the unit names none
     * @param nonJtaDataSourceName the {@code non-jta-data-source} element, or {@code null} when the unit names none:
     *        where a JTA unit allocates its TABLE identifiers
     */
    public record UnitTransactions(PersistenceUnitTransactionType type, String jtaDataSourceName,
            String nonJtaDataSourceName) {
        public boolean jta() {
            return type == PersistenceUnitTransactionType.JTA;
        }
    }

    /**
     * How a container makes a declared unit injectable with {@code @Inject} (§8.2.1.5, §8.2.1.6; Jakarta EE 11
     * Platform §13.12).
     *
     * @param name the name of the unit
     * @param qualifierNames the {@code qualifier} elements, the fully qualified names of the unit's qualifier
     *        annotations, in their order; empty when the unit names none, and its beans are then {@code @Default}
     * @param scopeName the {@code scope} element, the fully qualified name of the scope of the unit's entity manager,
     *        or {@code null} when the unit names none
     * @param jta whether the effective transaction type of the unit is JTA: a container-managed entity manager is a
     *        JTA entity manager (§7.6)
     */
    public record UnitInjection(String name, List<String> qualifierNames, String scopeName, boolean jta) {
        public UnitInjection {
            qualifierNames = List.copyOf(qualifierNames);
        }
    }

    private PersistenceDescriptors() {
    }

    /**
     * Every unit the {@code META-INF/persistence.xml} files of {@code loader} declare, as a container injects it, in
     * class path order.
     *
     * @throws PersistenceException if a file cannot be read
     */
    public static List<UnitInjection> injections(ClassLoader loader) {
        return PersistenceUnits.all(loader).stream().map(unit -> new UnitInjection(unit.name(),
            unit.qualifierAnnotationNames(), unit.scopeAnnotationName(),
            UnitSettings.of(unit, Map.of(), Map.of()).transactionType() == PersistenceUnitTransactionType.JTA)).toList();
    }

    /**
     * The transactions of the unit {@code name}, as the {@code META-INF/persistence.xml} files of {@code loader} declare
     * them: what a container needs to hand a JTA unit its data source and transaction bridge before it creates the
     * factory. Empty if no file declares the unit.
     *
     * @throws PersistenceException if a file cannot be read
     */
    public static Optional<UnitTransactions> transactions(String name, ClassLoader loader) {
        return PersistenceUnits.find(name, loader).map(unit -> new UnitTransactions(
            UnitSettings.of(unit, Map.of(), Map.of()).transactionType(), unit.jtaDataSourceName(),
            unit.nonJtaDataSourceName()));
    }

    /**
     * The name of the only persistence unit declared by the {@code META-INF/persistence.xml} files of {@code loader}:
     * the unit a container uses when an injection point names none.
     *
     * @throws PersistenceException if the files declare no unit or several, or cannot be read
     */
    public static String defaultUnitName(ClassLoader loader) {
        List<String> units = PersistenceUnits.names(loader);
        if (units.size() != 1) {
            throw new PersistenceException("Default persistence injection requires exactly one named persistence unit; found "
                + units);
        }
        return units.getFirst();
    }
}
