package io.vidocq.noether.jpa.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.persistence.Cache;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.SchemaManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.metamodel.Metamodel;

/**
 * The beans Jakarta EE 11 Platform §13.12.2 makes of a unit's container factory: the factory itself and five of its
 * parts. Each hands out what the unit's one container factory gives, the factory {@code @PersistenceUnit} injects, so
 * a unit never boots twice.
 */
public final class UnitBeans {

    private UnitBeans() {
    }

    private static EntityManagerFactory factory(Instance<Object> lookup, Parameters params) {
        return lookup.select(EntityManagerFactory.class,
            new PersistenceContextCreator.Binding("unit:" + params.get("unit", String.class))).get();
    }

    /** The {@code EntityManagerFactory} bean; the container closes the factory, not this bean. */
    public static final class Factory implements SyntheticBeanCreator<EntityManagerFactory> {
        @Override public EntityManagerFactory create(Instance<Object> lookup, Parameters params) {
            return factory(lookup, params);
        }
    }

    /** The {@code CriteriaBuilder} bean. */
    public static final class Criteria implements SyntheticBeanCreator<CriteriaBuilder> {
        @Override public CriteriaBuilder create(Instance<Object> lookup, Parameters params) {
            return factory(lookup, params).getCriteriaBuilder();
        }
    }

    /** The {@code PersistenceUnitUtil} bean. */
    public static final class Util implements SyntheticBeanCreator<PersistenceUnitUtil> {
        @Override public PersistenceUnitUtil create(Instance<Object> lookup, Parameters params) {
            return factory(lookup, params).getPersistenceUnitUtil();
        }
    }

    /** The {@code Cache} bean. */
    public static final class SecondLevelCache implements SyntheticBeanCreator<Cache> {
        @Override public Cache create(Instance<Object> lookup, Parameters params) {
            return factory(lookup, params).getCache();
        }
    }

    /** The {@code SchemaManager} bean. */
    public static final class Schema implements SyntheticBeanCreator<SchemaManager> {
        @Override public SchemaManager create(Instance<Object> lookup, Parameters params) {
            return factory(lookup, params).getSchemaManager();
        }
    }

    /** The {@code Metamodel} bean. */
    public static final class Model implements SyntheticBeanCreator<Metamodel> {
        @Override public Metamodel create(Instance<Object> lookup, Parameters params) {
            return factory(lookup, params).getMetamodel();
        }
    }
}
