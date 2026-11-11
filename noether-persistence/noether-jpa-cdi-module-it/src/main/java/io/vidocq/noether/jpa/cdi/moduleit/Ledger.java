package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.inject.Qualifier;
import java.lang.annotation.*;

/**
 * Keeps the {@code ledger} data source out of {@code @Default}: a bean whose only qualifier is {@code @Named} is also
 * {@code @Default} (CDI 4.1 §2.3.1), which would make the default data source ambiguous.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
public @interface Ledger {
}
