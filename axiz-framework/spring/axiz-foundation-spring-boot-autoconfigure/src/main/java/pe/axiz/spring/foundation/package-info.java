/**
 * Cableado compartido de Spring que necesitan las capacidades que no pueden depender unas de otras.
 * Los auxiliares tecnológicos son opcionales y solo los carga la capacidad que selecciona ese
 * backend. Los tipos son no nulos salvo que se anoten con {@code @Nullable}.
 */
@NullMarked
package pe.axiz.spring.foundation;

import org.jspecify.annotations.NullMarked;
