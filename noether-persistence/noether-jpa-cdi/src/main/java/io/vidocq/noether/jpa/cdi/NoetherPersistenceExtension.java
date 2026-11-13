package io.vidocq.noether.jpa.cdi;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors;
import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.UnitInjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.*;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.metamodel.Metamodel;
import java.lang.annotation.Annotation;
import java.util.*;

/** CDI 4.1 Lite persistence injection through portable enhancement and synthesis; no entity/member reflection. */
public final class NoetherPersistenceExtension implements BuildCompatibleExtension {
    private static final System.Logger LOGGER = System.getLogger(NoetherPersistenceExtension.class.getName());
    /** The parameters of the synthetic beans, read by their creators; also the members of {@code @PersistenceContext}. */
    private static final String UNIT = "unit";
    private static final String CONTEXT_TYPE = "contextType";
    private static final String SYNCHRONIZATION = "synchronization";
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
        String synchronization = enumeration(context, SYNCHRONIZATION, "SYNCHRONIZED");
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
        ClassLoader loader = PersistenceFactoryCreator.loader();
        List<UnitInjection> declared = PersistenceDescriptors.injections(loader);
        Set<String> factories = new LinkedHashSet<>(units);
        for (UnitInjection unit : declared) {
            String key = factoryKey(unit.name(), units, declared.size());
            factories.add(key);
            injectableBeans(components, unit, key, loader);
        }
        for (String unit : factories) {
            components.addBean(EntityManagerFactory.class).type(EntityManagerFactory.class).scope(Singleton.class)
                .qualifier(AnnotationBuilder.of(PersistenceBinding.class).value("unit:" + unit).build())
                .name(factoryName(unit))
                .withParam(UNIT, unit).createWith(PersistenceFactoryCreator.class).disposeWith(PersistenceFactoryDisposer.class);
        }
        contexts.forEach((binding, definition) -> components.addBean(EntityManager.class).type(EntityManager.class)
            .scope(Dependent.class).qualifier(AnnotationBuilder.of(PersistenceBinding.class).value(binding).build())
            .withParam(UNIT, definition.unit).withParam(CONTEXT_TYPE, definition.type)
            .withParam(SYNCHRONIZATION, definition.synchronization)
            .withParam("propertyNames", definition.properties.keySet().toArray(String[]::new))
            .withParam("propertyValues", definition.properties.values().toArray(String[]::new))
            .createWith(PersistenceContextCreator.class).disposeWith(PersistenceContextDisposer.class));
    }

    /**
     * The key of the one factory a unit of {@code persistence.xml} runs on: the factory its injection points already
     * share, the one an injection point without a unit name reaches when the unit is the deployment's only one, otherwise
     * its own.
     */
    static String factoryKey(String unit, Set<String> injected, int declaredUnits) {
        if (injected.contains(unit)) return unit;
        return injected.contains("") && declaredUnits == 1 ? "" : unit;
    }

    /**
     * Jakarta EE 11 Platform §13.12: every unit of {@code persistence.xml} is injectable with {@code @Inject}, under the
     * qualifiers its {@code <qualifier>} elements name, or {@code @Default}. The {@code EntityManagerFactory} bean is
     * application scoped and named after the unit; five dependent beans give its parts. The {@code EntityManager} bean
     * takes the scope of {@code <scope>}, or {@code @TransactionScoped}; a resource-local unit has none, a
     * container-managed entity manager being a JTA one (Jakarta Persistence 3.2, §7.6).
     */
    private static void injectableBeans(SyntheticComponents components, UnitInjection unit, String key, ClassLoader loader) {
        List<Class<? extends Annotation>> qualifiers = UnitAnnotations.qualifiers(unit, loader);
        qualified(components.addBean(EntityManagerFactory.class).type(EntityManagerFactory.class), qualifiers)
            .scope(ApplicationScoped.class).name(unit.name()).withParam(UNIT, key).createWith(UnitBeans.Factory.class);
        part(components, CriteriaBuilder.class, UnitBeans.Criteria.class, qualifiers, key);
        part(components, PersistenceUnitUtil.class, UnitBeans.Util.class, qualifiers, key);
        part(components, Cache.class, UnitBeans.SecondLevelCache.class, qualifiers, key);
        part(components, SchemaManager.class, UnitBeans.Schema.class, qualifiers, key);
        part(components, Metamodel.class, UnitBeans.Model.class, qualifiers, key);
        if (unit.jta()) {
            qualified(components.addBean(EntityManager.class).type(EntityManager.class), qualifiers)
                .scope(UnitAnnotations.scope(unit, loader)).withParam(UNIT, key)
                .withParam(CONTEXT_TYPE, PersistenceContextType.TRANSACTION.name())
                .withParam(SYNCHRONIZATION, SynchronizationType.SYNCHRONIZED.name())
                .createWith(PersistenceContextCreator.class).disposeWith(PersistenceContextDisposer.class);
        } else if (unit.scopeName() != null) {
            LOGGER.log(System.Logger.Level.WARNING, "Persistence unit ''{0}'' names the scope {1}, but it is resource-local:"
                + " only a JTA unit is an injectable EntityManager (Jakarta Persistence 3.2, §7.6)", unit.name(), unit.scopeName());
        }
    }

    private static <T> void part(SyntheticComponents components, Class<T> type, Class<? extends SyntheticBeanCreator<T>> creator,
            List<Class<? extends Annotation>> qualifiers, String key) {
        qualified(components.addBean(type).type(type), qualifiers).scope(Dependent.class).withParam(UNIT, key).createWith(creator);
    }

    private static <T> SyntheticBeanBuilder<T> qualified(SyntheticBeanBuilder<T> bean, List<Class<? extends Annotation>> qualifiers) {
        if (qualifiers.isEmpty()) bean.qualifier(Default.class);
        qualifiers.forEach(bean::qualifier);
        return bean;
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
