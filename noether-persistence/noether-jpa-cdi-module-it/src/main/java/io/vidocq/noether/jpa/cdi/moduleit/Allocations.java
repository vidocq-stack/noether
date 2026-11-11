package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.inject.Qualifier;
import java.lang.annotation.*;

/** Keeps the {@code allocations} data source out of {@code @Default}, as {@link Ledger} does for its own. */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
public @interface Allocations {
}
