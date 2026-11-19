package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.spi.LoadState;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.ProviderUtil;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Another provider than Noether, as the unit {@code xml-recorded} names it (Jakarta Persistence 3.2, §9.1): this module
 * provides it, so the container finds it through the {@code PersistenceProviderResolver}. It records the unit the
 * container hands it; its factory answers {@code getName}, {@code isOpen} and {@code close} only.
 */
public final class RecordingProvider implements PersistenceProvider {

    private static final AtomicReference<PersistenceUnitInfo> LAST = new AtomicReference<>();

    /** The unit the container last handed this provider. */
    public static PersistenceUnitInfo last() {
        return LAST.get();
    }

    @Override
    public EntityManagerFactory createContainerEntityManagerFactory(PersistenceUnitInfo unit, Map properties) {
        LAST.set(unit);
        boolean[] open = {true};
        return (EntityManagerFactory) Proxy.newProxyInstance(RecordingProvider.class.getClassLoader(),
            new Class<?>[] {EntityManagerFactory.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> unit.getPersistenceUnitName();
                case "isOpen" -> open[0];
                case "close" -> {
                    open[0] = false;
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    /** Units of a Java SE bootstrap are Noether's here: this provider declines them all. */
    @Override public EntityManagerFactory createEntityManagerFactory(String name, Map properties) { return null; }
    @Override public EntityManagerFactory createEntityManagerFactory(PersistenceConfiguration configuration) { return null; }
    @Override public boolean generateSchema(String name, Map properties) { return false; }

    @Override
    public void generateSchema(PersistenceUnitInfo unit, Map properties) {
        throw new UnsupportedOperationException("generateSchema");
    }

    @Override
    public ProviderUtil getProviderUtil() {
        return new ProviderUtil() {
            @Override public LoadState isLoadedWithoutReference(Object entity, String attribute) { return LoadState.UNKNOWN; }
            @Override public LoadState isLoadedWithReference(Object entity, String attribute) { return LoadState.UNKNOWN; }
            @Override public LoadState isLoaded(Object entity) { return LoadState.UNKNOWN; }
        };
    }
}
