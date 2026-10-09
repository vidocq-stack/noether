package io.vidocq.mansart.jpa.cdi;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.*;
import java.util.*;

/** CDI 4.1 Lite persistence injection through portable enhancement and synthesis; no entity/member reflection. */
public final class MansartPersistenceExtension implements BuildCompatibleExtension {
    private record Context(String unit, String type, String synchronization, Map<String,String> properties) {}
    private final Set<String> units = new LinkedHashSet<>();
    private final Map<String, Context> contexts = new LinkedHashMap<>();

    @Enhancement(types = Object.class, withSubtypes = true)
    public void persistenceFields(ClassConfig clazz) {
        for (FieldConfig field : clazz.fields()) {
            AnnotationInfo context = field.info().annotation(PersistenceContext.class);
            AnnotationInfo unitAnnotation = field.info().annotation(PersistenceUnit.class);
            if (context == null && unitAnnotation == null) continue;
            if (!field.info().hasAnnotation(Inject.class)) field.addAnnotation(Inject.class);
            field.addAnnotation(AnnotationBuilder.of(PersistenceBinding.class).value(binding(context, unitAnnotation)).build());
        }
        for (MethodConfig method : clazz.methods()) {
            AnnotationInfo context = method.info().annotation(PersistenceContext.class);
            AnnotationInfo unit = method.info().annotation(PersistenceUnit.class);
            if (context == null && unit == null) continue;
            if (method.info().isStatic() || method.parameters().size() != 1) {
                throw new jakarta.enterprise.inject.spi.DefinitionException("Persistence injection requires a non-static one-parameter setter: "
                    + method.info().declaringClass().name() + "." + method.info().name());
            }
            if (!method.info().hasAnnotation(Inject.class)) method.addAnnotation(Inject.class);
            method.parameters().getFirst().addAnnotation(AnnotationBuilder.of(PersistenceBinding.class).value(binding(context, unit)).build());
        }
    }

    private String binding(AnnotationInfo context, AnnotationInfo unitAnnotation) {
        String unit = string(context != null ? context : unitAnnotation, "unitName", "");
        units.add(unit);
        if (context == null) return "unit:" + unit;
        String type = enumeration(context, "type", "TRANSACTION");
        String synchronization = enumeration(context, "synchronization", "SYNCHRONIZED");
        Map<String,String> properties = new TreeMap<>();
        if (context.hasMember("properties")) {
            for (var value : context.member("properties").asArray()) {
                AnnotationInfo property = value.asNestedAnnotation();
                properties.put(string(property, "name", ""), string(property, "value", ""));
            }
        }
        Context definition = new Context(unit, type, synchronization, Map.copyOf(properties));
        String binding = "context:" + unit + ":" + type + ":" + synchronization + ":" + properties;
        contexts.putIfAbsent(binding, definition);
        return binding;
    }

    @Synthesis
    public void persistenceBeans(SyntheticComponents components) {
        for (String unit : units) {
            components.addBean(EntityManagerFactory.class).type(EntityManagerFactory.class).scope(Singleton.class)
                .qualifier(AnnotationBuilder.of(PersistenceBinding.class).value("unit:" + unit).build())
                .withParam("unit", unit).createWith(PersistenceFactoryCreator.class).disposeWith(PersistenceFactoryDisposer.class);
        }
        contexts.forEach((binding, definition) -> components.addBean(EntityManager.class).type(EntityManager.class)
            .scope(Dependent.class).qualifier(AnnotationBuilder.of(PersistenceBinding.class).value(binding).build())
            .withParam("unit", definition.unit).withParam("contextType", definition.type)
            .withParam("synchronization", definition.synchronization)
            .withParam("propertyNames", definition.properties.keySet().toArray(String[]::new))
            .withParam("propertyValues", definition.properties.values().toArray(String[]::new))
            .createWith(PersistenceContextCreator.class).disposeWith(PersistenceContextDisposer.class));
    }

    private static String string(AnnotationInfo annotation, String member, String fallback) {
        return annotation.hasMember(member) ? annotation.member(member).asString() : fallback;
    }
    private static String enumeration(AnnotationInfo annotation, String member, String fallback) {
        return annotation.hasMember(member) ? annotation.member(member).asEnumConstant() : fallback;
    }
}
