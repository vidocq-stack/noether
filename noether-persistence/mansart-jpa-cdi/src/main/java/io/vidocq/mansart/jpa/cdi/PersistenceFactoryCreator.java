package io.vidocq.mansart.jpa.cdi;

import io.vidocq.mansart.jpa.core.spi.TransactionIntegration;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.persistence.*;
import jakarta.transaction.TransactionManager;
import java.util.*;
import javax.xml.stream.*;

/** Factory ownership is the CDI deployment's, not a static per-JVM cache. */
public final class PersistenceFactoryCreator implements SyntheticBeanCreator<EntityManagerFactory> {
    @Override public EntityManagerFactory create(Instance<Object> lookup, Parameters params) {
        String unit = params.get("unit", String.class);
        JtaIntegration integration = new JtaIntegration(lookup.select(TransactionManager.class).get());
        var bootstrap = lookup.select(PersistenceUnitBootstrap.class);
        if (bootstrap.isUnsatisfied() && unit.isEmpty()) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            unit = defaultUnitName(loader == null ? PersistenceFactoryCreator.class.getClassLoader() : loader);
        }
        EntityManagerFactory factory = bootstrap.isUnsatisfied() ? Persistence.createEntityManagerFactory(unit, Map.of(TransactionIntegration.PROPERTY, integration))
            : bootstrap.get().create(unit, integration);
        return new ContainerEntityManagerFactory(factory);
    }

    static String defaultUnitName(ClassLoader loader) {
        List<String> units = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        XMLInputFactory xml = XMLInputFactory.newDefaultFactory();
        xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xml.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("External XML resources are forbidden");
        });
        try {
            var descriptors = loader.getResources("META-INF/persistence.xml");
            while (descriptors.hasMoreElements()) {
                var descriptor = descriptors.nextElement();
                if (!seen.add(descriptor.toExternalForm())) continue;
                var connection = descriptor.openConnection();
                connection.setUseCaches(false);
                try (var input = connection.getInputStream()) {
                    var reader = xml.createXMLStreamReader(input);
                    try {
                        while (reader.hasNext()) {
                            int event = reader.next();
                            if (event == XMLStreamConstants.DTD) throw new XMLStreamException("Persistence descriptors must not contain a DTD");
                            if (event == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("persistence-unit")) {
                                units.add(reader.getAttributeValue(null, "name"));
                            }
                        }
                    } finally { reader.close(); }
                }
            }
        } catch (java.io.IOException | XMLStreamException failure) {
            throw new PersistenceException("Unable to resolve the deployment's default persistence unit", failure);
        }
        if (units.size() != 1 || units.getFirst() == null || units.getFirst().isEmpty()) {
            throw new PersistenceException("Default persistence injection requires exactly one named persistence unit; found " + units);
        }
        return units.getFirst();
    }
}
