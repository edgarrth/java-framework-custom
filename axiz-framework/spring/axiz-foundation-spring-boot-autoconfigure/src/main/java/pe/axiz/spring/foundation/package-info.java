/**
 * Shared Spring wiring needed by capabilities that cannot depend on one another. Technology helpers
 * are optional and are loaded only by the capability selecting that backend. Types are non-null
 * unless annotated {@code @Nullable}.
 */
@NullMarked
package pe.axiz.spring.foundation;

import org.jspecify.annotations.NullMarked;
