/** An application module that reads the provider through a library's {@code requires transitive}, not on its own. */
module app {
    requires lib;

    provides io.vidocq.noether.jpa.core.spi.ManagedAccessProvider with app._NoetherJpaAccess;
}
