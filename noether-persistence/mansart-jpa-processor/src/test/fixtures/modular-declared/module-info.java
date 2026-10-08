/** An application module that declares the accesses generated for it: its entity package stays closed. */
module app {
    requires jakarta.persistence;
    requires io.vidocq.mansart.jpa.core;

    provides io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider with app._MansartJpaAccess;
}
