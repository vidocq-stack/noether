package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.inject.Qualifier;
import java.lang.annotation.*;

/** The qualifier the {@code xml-archive} unit names in {@code persistence.xml} (Jakarta Persistence 3.2, §8.2.1.5). */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
public @interface Archive {
}
