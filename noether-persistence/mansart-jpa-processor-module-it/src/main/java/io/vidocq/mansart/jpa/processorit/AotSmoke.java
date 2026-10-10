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
 * https://joinup.ec.europa.eu/collection/eupl/eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.processorit;

import io.vidocq.mansart.jpa.processorit.closed.Ledger;
import jakarta.persistence.PersistenceConfiguration;
import io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider;

/** Small modular application used to train and consume a JDK Leyden AOT cache. */
public final class AotSmoke {
    private AotSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Module application = AotSmoke.class.getModule();
        Module provider = ModuleLayer.boot().findModule("io.vidocq.mansart.jpa.core").orElseThrow();
        String entityPackage = Ledger.class.getPackageName();
        if (application.isOpen(entityPackage, provider) || application.isExported(entityPackage, provider)) {
            throw new IllegalStateException("The AOT application must keep entity packages closed to Mansart");
        }

        Class<?> generatedAccess = Class.forName(
            "io.vidocq.mansart.jpa.processorit.closed._MansartJpaAccess", false, AotSmoke.class.getClassLoader());
        boolean declaredProvider = application.getDescriptor().provides().stream()
            .filter(provides -> provides.service().equals(ManagedAccessProvider.class.getName()))
            .anyMatch(provides -> provides.providers().contains(generatedAccess.getName()));
        if (!declaredProvider || generatedAccess.getModule() != application) {
            throw new IllegalStateException("APT-generated access provider is not registered in the application module");
        }

        var configuration = new PersistenceConfiguration("aot-smoke")
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Ledger.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:aot-smoke;DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create");
        try (var factory = configuration.createEntityManagerFactory()) {
            factory.runInTransaction(em -> em.persist(new Ledger(9001)));
            factory.runInTransaction(em -> {
                if (em.find(Ledger.class, 9001L) == null
                    || em.createQuery("select l from Ledger l where l.id = 9001", Ledger.class)
                        .getResultList().size() != 1) {
                    throw new IllegalStateException("APT-only persistence CRUD/query smoke failed");
                }
                em.remove(em.find(Ledger.class, 9001L));
            });
            factory.runInTransaction(em -> {
                if (em.find(Ledger.class, 9001L) != null) {
                    throw new IllegalStateException("APT-only persistence delete smoke failed");
                }
            });
        }
        System.out.println("AOT_SMOKE_OK provider=Mansart generatedAccess=" + generatedAccess.getName());
    }
}
