module io.vidocq.mansart.jpa.cdi {
    requires java.xml;
    requires io.vidocq.mansart.jpa.core;
    requires io.vidocq.mansart.transactions.jdbc;
    requires io.vidocq.mansart.transactions.core;
    requires jakarta.persistence;
    requires jakarta.transaction;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires java.sql;
    requires java.transaction.xa;
    exports io.vidocq.mansart.jpa.cdi;
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
        with io.vidocq.mansart.jpa.cdi.MansartPersistenceExtension;
}
