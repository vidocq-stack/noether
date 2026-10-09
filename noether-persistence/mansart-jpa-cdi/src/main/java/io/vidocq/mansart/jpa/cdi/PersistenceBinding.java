package io.vidocq.mansart.jpa.cdi;

import jakarta.inject.Qualifier;
import java.lang.annotation.*;

/** Internal binding synthesized from each persistence injection declaration. */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
public @interface PersistenceBinding { String value(); }
