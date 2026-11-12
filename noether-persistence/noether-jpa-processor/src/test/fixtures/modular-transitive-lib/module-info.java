/** A library that hands the provider SPI on: a module that requires it reads Noether's core too (JLS §7.7.1). */
module lib {
    requires transitive jakarta.persistence;
    requires transitive io.vidocq.noether.jpa.core;

    exports lib;
}
