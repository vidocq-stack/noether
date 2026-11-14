/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.processor;

import io.vidocq.noether.jpa.core.model.build.AccessPlanner;
import io.vidocq.noether.jpa.core.model.source.MethodInfo;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.Diagnostic;

/** §6.2.1.1 canonical classes, planned by AccessPlanner and emitted one round after entity accesses. */
final class MetamodelWriter {
    private record Field(String name,String kind,List<String> arguments) {}
    private record Plan(TypeElement type,List<AccessPlanner.Member> members,String superclass) {}
    /** The metamodel type of a managed class and the {@code Metamodel} method that returns it. */
    private enum ManagedKind {
        ENTITY("EntityType","entity"), EMBEDDABLE("EmbeddableType","embeddable"),
        MAPPED_SUPERCLASS("MappedSuperclassType","managedType");
        private final String type;
        private final String lookup;
        ManagedKind(String type,String lookup) { this.type=type; this.lookup=lookup; }
    }
    private final ProcessingEnvironment environment;
    private final Elements elements;
    private final Types types;
    private final Map<String,Plan> plans=new LinkedHashMap<>();
    private final Map<String,List<Field>> fields=new LinkedHashMap<>();
    private static final String MARKER = "NOETHER_JPA_GENERATED";
    private static final String SINGULAR_ATTRIBUTE = "SingularAttribute";
    MetamodelWriter(ProcessingEnvironment environment) {
        this.environment=environment; elements=environment.getElementUtils(); types=environment.getTypeUtils();
    }
    void plan(TypeElement type,List<AccessPlanner.Member> members,String superclass) {
        plans.putIfAbsent(type.getQualifiedName().toString(),new Plan(type,List.copyOf(members),superclass));
    }
    void emit() {
        for (var plan:plans.values()) {
            if (!fields.containsKey(plan.type.getQualifiedName().toString())) emit(plan);
        }
    }
    /** The canonical class of one planned type, unless another producer owns it, then its initialization helper. */
    private void emit(Plan plan) {
        String entity=plan.type.getQualifiedName().toString();
        List<Field> declared=plan.members.stream().filter(m -> m.owner().name().equals(elements.getBinaryName(plan.type).toString()))
            .map(this::field).toList();
        String canonical=canonical(plan.type);
        TypeElement existing=elements.getTypeElement(canonical);
        boolean own=existing==null || ElementFilter.fieldsIn(existing.getEnclosedElements()).stream()
            .anyMatch(f -> f.getSimpleName().contentEquals(MARKER) && Boolean.TRUE.equals(f.getConstantValue()));
        if (own) {
            writeSource(canonical,plan.type,canonicalSource(plan,entity,canonical,declared));
            fields.put(entity,declared);
        } else {
            fields.put(entity,usableFields(plan,canonical,existing));
        }
        writeHelper(plan,canonical,own);
    }
    /** The attributes of a canonical class another producer emitted that hold the type Noether expects. */
    private List<Field> usableFields(Plan plan,String canonical,TypeElement existing) {
        List<Field> usable=new ArrayList<>();
        for (VariableElement field:ElementFilter.fieldsIn(existing.getEnclosedElements())) {
            String type=metamodelType(field);
            var attribute=plan.members.stream().filter(m -> m.name().contentEquals(field.getSimpleName())).findFirst();
            if (type!=null && attribute.isPresent()) usableField(plan,canonical,field,type,attribute.get()).ifPresent(usable::add);
        }
        return usable;
    }
    /** The qualified name of the type of a field when it is a {@code jakarta.persistence.metamodel} type, else null. */
    private static String metamodelType(VariableElement field) {
        if (field.asType() instanceof DeclaredType d) {
            String name=((TypeElement) d.asElement()).getQualifiedName().toString();
            if (name.startsWith("jakarta.persistence.metamodel.")) return name;
        }
        return null;
    }
    /** The attribute an existing field holds, or a warning when that field was emitted with another metamodel type. */
    private Optional<Field> usableField(Plan plan,String canonical,VariableElement field,String type,AccessPlanner.Member attribute) {
        Field expected=field(attribute);
        if (type.endsWith("."+expected.kind)) return Optional.of(expected);
        environment.getMessager().printMessage(Diagnostic.Kind.WARNING,
            "Existing canonical metamodel "+canonical+"."+field.getSimpleName()+" was emitted with "+type
            +" instead of "+expected.kind+"; its producing processor owns that class and must be reconciled "
            +"before this attribute can be populated. Noether JPA does not overwrite it.",plan.type);
        return Optional.empty();
    }
    private String canonicalSource(Plan plan,String entity,String canonical,List<Field> declared) {
        String pkg=elements.getPackageOf(plan.type).getQualifiedName().toString();
        String simple=canonical.substring(pkg.isEmpty()?0:pkg.length()+1);
        StringBuilder text=new StringBuilder();
        if (!pkg.isEmpty()) text.append("package ").append(pkg).append(";\n\n");
        text.append("// Generated by noether-jpa-processor: do not edit.\n")
            .append("@java.lang.SuppressWarnings(\"rawtypes\")\n")
            .append("@jakarta.persistence.metamodel.StaticMetamodel(").append(entity).append(".class)\n")
            .append("public abstract class ").append(simple);
        if (plan.superclass!=null) text.append(" extends ").append(canonical(elements.getTypeElement(plan.superclass)));
        text.append(" {\n");
        text.append("    public static final boolean ").append(MARKER).append(" = true;\n");
        for (var f:declared) text.append("    public static volatile jakarta.persistence.metamodel.").append(f.kind)
            .append("<").append(entity).append(", ").append(String.join(", ",f.arguments)).append("> ").append(f.name).append(";\n");
        text.append("    public static volatile jakarta.persistence.metamodel.").append(managedKind(plan.type).type)
            .append("<").append(entity).append("> class_;\n");
        for (var f:declared) text.append("    public static final String ").append(constant(f.name)).append(" = \"").append(f.name).append("\";\n");
        return text.append("}\n").toString();
    }
    Set<String> helpers() {
        Set<String> result=new LinkedHashSet<>();
        fields.keySet().forEach(entity -> result.add(canonical(plans.get(entity).type)+"$$NoetherMetamodel"));
        return result;
    }
    String initializer(Collection<String> helpers) {
        StringBuilder source=new StringBuilder("""

            @java.lang.Override
            public java.util.Set<java.lang.Class<?>> populateMetamodel(jakarta.persistence.metamodel.Metamodel metamodel) {
                java.util.Set<java.lang.Class<?>> initialized = new java.util.LinkedHashSet<>();
            """);
        for (String helper:helpers) source.append("        initialized.addAll(").append(helper).append(".populate(metamodel));\n");
        return source.append("        return initialized;\n    }\n").toString();
    }
    private void writeHelper(Plan plan,String canonical,boolean own) {
        String entity=plan.type.getQualifiedName().toString();
        String pkg=elements.getPackageOf(plan.type).getQualifiedName().toString();
        String helper=canonical+"$$NoetherMetamodel";
        StringBuilder source=new StringBuilder(pkg.isEmpty()?"":"package "+pkg+";\n");
        source.append("public final class ").append(helper.substring(pkg.isEmpty()?0:pkg.length()+1)).append(" {\n");
        source.append(initialization(entity,canonical,own)).append("}\n");
        writeSource(helper,plan.type,source.toString());
    }
    private void writeSource(String name,TypeElement origin,String text) {
        try (Writer out=environment.getFiler().createSourceFile(name,origin).openWriter()) { out.write(text); }
        catch (IOException failure) {
            environment.getMessager().printMessage(Diagnostic.Kind.ERROR,"Cannot generate "+name+": "+failure.getMessage(),origin);
        }
    }
    private String initialization(String entity,String canonical,boolean own) {
        StringBuilder source=new StringBuilder("""

            @java.lang.SuppressWarnings({"unchecked", "rawtypes"})
            public static java.util.Set<java.lang.Class<?>> populate(jakarta.persistence.metamodel.Metamodel metamodel) {
                java.util.Set<java.lang.Class<?>> initialized = new java.util.LinkedHashSet<>();
            """);
        TypeElement existing=elements.getTypeElement(canonical);
        source.append("        if (metamodel.getManagedTypes().stream().anyMatch(t -> t.getJavaType() == ").append(entity).append(".class)) {\n");
        for (var field:fields.get(entity)) appendAttribute(source,entity,canonical,field,own);
        if (own || existing!=null && ElementFilter.fieldsIn(existing.getEnclosedElements()).stream()
                .anyMatch(f -> f.getSimpleName().contentEquals("class_"))) {
            appendManagedType(source,entity,canonical);
        }
        source.append("            initialized.add(").append(entity).append(".class);\n        }\n");
        return source.append("        return initialized;\n    }\n").toString();
    }
    /** The assignment of one attribute of the canonical class, from the runtime metamodel. */
    private static void appendAttribute(StringBuilder source,String entity,String canonical,Field field,boolean own) {
        source.append("            ").append(canonical).append(".").append(field.name).append(" = ");
        if (own) {
            source.append("metamodel.managedType(").append(entity).append(".class).getDeclared")
                .append(field.kind.replace("Attribute",""));
            if (field.kind.equals(SINGULAR_ATTRIBUTE)) source.append("Attribute");
            source.append("(\"").append(field.name).append("\"");
            for (String argument:field.arguments) source.append(", ").append(argument).append(AccessWriter.CLASS_LITERAL);
            source.append(");\n");
        } else {
            // A pre-existing producer may flatten inherited attributes; ownership stays in the runtime model.
            String type="jakarta.persistence.metamodel."+field.kind+"<"+entity+", "+String.join(", ",field.arguments)+">";
            source.append("(").append(type).append(") (jakarta.persistence.metamodel.Attribute<?, ?>) metamodel.managedType(")
                .append(entity).append(".class).getAttribute(\"").append(field.name).append("\");\n");
        }
    }
    /** The assignment of {@code class_}, the managed type of the canonical class. */
    private void appendManagedType(StringBuilder source,String entity,String canonical) {
        ManagedKind kind=managedKind(plans.get(entity).type);
        source.append("            ").append(canonical).append(".class_ = ");
        if (kind==ManagedKind.MAPPED_SUPERCLASS)
            source.append("(jakarta.persistence.metamodel.MappedSuperclassType<").append(entity).append(">) ");
        source.append("metamodel.").append(kind.lookup).append("(").append(entity).append(".class);\n");
    }
    private Field field(AccessPlanner.Member member) {
        TypeElement owner=elements.getTypeElement(member.owner().name().replace('$','.'));
        String name=member.element() instanceof MethodInfo method?method.name():member.name();
        Element element=owner.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals(name)).findFirst().orElseThrow();
        TypeMirror type=element instanceof ExecutableElement method?method.getReturnType():element.asType();
        String kind=attributeKind(types.erasure(type).toString());
        return new Field(member.name(),kind,typeArguments(element,type,kind));
    }
    private static String attributeKind(String erased) {
        return switch (erased) {
            case "java.util.Map" -> "MapAttribute";
            case "java.util.List" -> "ListAttribute";
            case "java.util.Set" -> "SetAttribute";
            case "java.util.Collection" -> "CollectionAttribute";
            default -> SINGULAR_ATTRIBUTE;
        };
    }
    /** The type arguments of an attribute after its owner: its value type, or its element (and key) types. */
    private List<String> typeArguments(Element element,TypeMirror type,String kind) {
        if (kind.equals(SINGULAR_ATTRIBUTE)) return List.of(box(type));
        List<? extends TypeMirror> arguments=type instanceof DeclaredType declared?declared.getTypeArguments():List.of();
        if (!arguments.isEmpty()) return arguments.stream().map(this::box).toList();
        return explicitTypeArguments(element,kind);
    }
    /** The type arguments of a raw collection attribute: the target its mapping annotations name, else Object. */
    private List<String> explicitTypeArguments(Element element,String kind) {
        String target=explicitType(element,"jakarta.persistence.ElementCollection","targetClass");
        if (target==null) target=explicitType(element,"jakarta.persistence.OneToMany","targetEntity");
        if (target==null) target=explicitType(element,"jakarta.persistence.ManyToMany","targetEntity");
        if (target==null) target="java.lang.Object";
        if (!kind.equals("MapAttribute")) return List.of(target);
        String key=explicitType(element,"jakarta.persistence.MapKeyClass","value");
        return List.of(key==null?"java.lang.Object":key,target);
    }
    private String explicitType(Element element,String annotation,String name) {
        for (AnnotationMirror mirror:element.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString().equals(annotation)) continue;
            for (var value:elements.getElementValuesWithDefaults(mirror).entrySet())
                if (value.getKey().getSimpleName().contentEquals(name) && value.getValue().getValue() instanceof TypeMirror target
                        && !target.toString().equals("void")) return box(target);
        }
        return null;
    }
    private String canonical(TypeElement type) {
        return elements.getBinaryName(type).toString()+"_";
    }
    private String box(TypeMirror type) {
        return type.getKind().isPrimitive()?types.boxedClass((PrimitiveType) type).getQualifiedName().toString():types.erasure(type).toString();
    }
    private static ManagedKind managedKind(TypeElement type) {
        if (annotated(type,"jakarta.persistence.Entity")) return ManagedKind.ENTITY;
        if (annotated(type,"jakarta.persistence.Embeddable")) return ManagedKind.EMBEDDABLE;
        return ManagedKind.MAPPED_SUPERCLASS;
    }
    private static boolean annotated(TypeElement type,String annotation) {
        return type.getAnnotationMirrors().stream().anyMatch(a -> a.getAnnotationType().toString().equals(annotation));
    }
    private String constant(String name) { return name.replaceAll("([a-z0-9])([A-Z])","$1_$2").toUpperCase(Locale.ROOT); }
}
