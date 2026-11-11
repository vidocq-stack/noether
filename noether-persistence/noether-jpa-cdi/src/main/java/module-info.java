/**
 * Persistence injection on a CDI 4.1 Lite container ({@code @PersistenceContext}, {@code @PersistenceUnit}), through a
 * build compatible extension. A JTA unit takes what any container with JTA has: its
 * {@code TransactionSynchronizationRegistry} bean, through {@code io.vidocq.noether.jpa.jta}, and a {@code DataSource}
 * bean whose connections the container enlists. No transaction manager implementation is required here: the Vidocq
 * runtime extension, Quarkus or a server brings it.
 */
module io.vidocq.noether.jpa.cdi {
    requires io.vidocq.noether.jpa.core;
    requires io.vidocq.noether.jpa.jta;
    requires jakarta.persistence;
    requires jakarta.transaction;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires java.sql;
    exports io.vidocq.noether.jpa.cdi;
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
        with io.vidocq.noether.jpa.cdi.NoetherPersistenceExtension;
}
