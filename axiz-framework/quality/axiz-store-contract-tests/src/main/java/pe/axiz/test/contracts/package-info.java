/**
 * Verificaciones de contrato para implementaciones de almacenes, escritas en Java plano para que se
 * ejecuten en cualquier motor de pruebas: cada verificación es un {@link
 * pe.axiz.test.contracts.ContractCheck} con nombre; una prueba JUnit las convierte en pruebas
 * dinámicas. Las mismas verificaciones se ejecutan contra fakes en memoria en las pruebas unitarias
 * y contra backends reales (PostgreSQL, Valkey, DynamoDB) en las pruebas de integración.
 */
@NullMarked
package pe.axiz.test.contracts;

import org.jspecify.annotations.NullMarked;
