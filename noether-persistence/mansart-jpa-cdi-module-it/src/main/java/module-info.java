module io.vidocq.mansart.jpa.cdi.moduleit {
    requires io.vidocq.mansart.jpa.cdi;
    requires io.vidocq.mansart.jpa.core;
    requires io.vidocq.mansart.transactions.core;
    requires io.vidocq.vauban.core;
    requires jakarta.persistence;
    requires jakarta.transaction;
    requires jakarta.cdi;
    requires jakarta.inject;
    exports io.vidocq.mansart.jpa.cdi.moduleit;
    // Test entities deliberately exercise Class-File API bootstrap access, not entity reflection.
    opens io.vidocq.mansart.jpa.cdi.moduleit to io.vidocq.mansart.jpa.core;
    // Existing Vauban APT handles application bean creation without opening business classes to the container.
    provides io.vidocq.vauban.api.VaubanComponentProvider with io.vidocq.mansart.jpa.cdi.moduleit._VaubanComponents;
}
