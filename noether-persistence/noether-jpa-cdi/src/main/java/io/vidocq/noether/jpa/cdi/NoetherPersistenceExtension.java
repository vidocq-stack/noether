package io.vidocq.noether.jpa.cdi;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.*;
import java.util.*;

/** CDI 4.1 Lite persistence injection through portable enhancement and synthesis; no entity/member reflection. */
public final class NoetherPersistenceExtension implements BuildCompatibleExtension {
    private record Context(String unit, String type, String synchronization, Map<String,String> properties) {}
    private final Set<String> units = new LinkedHashSet<>();
    private final Map<String, Context> contexts = new LinkedHashMap<>();

    /**
     * The binding qualifier lives in this jar, which a container need not index (Quarkus indexes the application, not
     * every dependency): without this declaration it would not know the annotation that {@link #persistenceFields}
     * adds to each injection point is a qualifier, and would resolve every persistence injection as {@code @Default}.
     */
    @Discovery
    public void bindingQualifier(MetaAnnotations meta) {
        meta.addQualifier(PersistenceBinding.class);
    }

    /** Only the classes that declare persistence injection, not every class of the deployment. */
    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = {PersistenceContext.class, PersistenceUnit.class})
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
        String binding = contextBinding(unit, type, synchronization, properties);
        contexts.putIfAbsent(binding, definition);
        return binding;
    }

    /**
     * One key per configuration, never shared by two (BUG-20261018-03): every part is written as its length, a colon and
     * itself, so no character inside a unit name, a property name or a value can be read as a separator. Properties are
     * sorted, so the same configuration always gives the same key.
     */
    static String contextBinding(String unit, String type, String synchronization, Map<String, String> properties) {
        StringBuilder key = new StringBuilder("context:");
        part(key, unit);
        part(key, type);
        part(key, synchronization);
        new TreeMap<>(properties).forEach((name, value) -> {
            part(key, name);
            part(key, value);
        });
        return key.toString();
    }

    private static void part(StringBuilder key, String value) {
        key.append(value.length()).append(':').append(value);
    }

    /**
     * One factory per unit, shared by the unit's contexts: their creator looks it up programmatically. A container that
     * removes the beans no injection point uses (Quarkus ArC) would remove the factory of a unit only reached through
     * {@code @PersistenceContext}; a bean with a name is never removed, so each factory gets one. The name is internal.
     */
    @Synthesis
    public void persistenceBeans(SyntheticComponents components) {
        for (String unit : units) {
            components.addBean(EntityManagerFactory.class).type(EntityManagerFactory.class).scope(Singleton.class)
                .qualifier(AnnotationBuilder.of(PersistenceBinding.class).value("unit:" + unit).build())
                .name(factoryName(unit))
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

    /**
     * Named after the unit alone, never after its rank among the units (BUG-20261113-01): the boot of an application may
     * run this extension again over classes it sees in another order than the build did, and a second name registered
     * a second factory of the same unit. Unique and without dots (a dotted name would clash with any bean named after
     * its first segment): an ASCII letter or digit stays, any other character becomes its code point in hexadecimal
     * between underscores, so two units never share a name.
     */
    static String factoryName(String unit) {
        StringBuilder name = new StringBuilder("noetherPersistenceUnit_");
        unit.codePoints().forEach(c -> {
            if (c < 128 && Character.isLetterOrDigit(c)) {
                name.appendCodePoint(c);
            } else {
                name.append('_').append(Integer.toHexString(c)).append('_');
            }
        });
        return name.toString();
    }

    private static String string(AnnotationInfo annotation, String member, String fallback) {
        return annotation.hasMember(member) ? annotation.member(member).asString() : fallback;
    }
    private static String enumeration(AnnotationInfo annotation, String member, String fallback) {
        return annotation.hasMember(member) ? annotation.member(member).asEnumConstant() : fallback;
    }
}
