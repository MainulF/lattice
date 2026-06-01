package io.github.mainulf.lattice.ecs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a LatticeSystem's phase and scheduling priority. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemDef {
    Phase phase();
    /** Lower value = earlier in deterministic order = lower precedence for last-writer-wins. */
    int priority() default 0;
}
