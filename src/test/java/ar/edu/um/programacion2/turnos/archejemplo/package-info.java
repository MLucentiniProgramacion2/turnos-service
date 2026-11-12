/**
 * Feature de ejemplo que existe solo en tests. La usa {@code ArquitecturaDetectaViolacionesTest} para comprobar
 * que las reglas de ArchUnit detectan cada violación y no marcan las clases que cumplen.
 * <p>
 * Está directamente bajo {@code turnos} para que los patrones reales ({@code turnos.*.domain..}, etc.) la
 * incluyan. Las clases no llevan anotaciones de Spring ni {@code @Entity}: si las tuvieran, los tests de
 * integración las registrarían en el contexto al escanear {@code turnos..}.
 * <p>
 * Las clases {@code DominioQueUsa*}, {@code ApplicationQueUsa*}, {@code WebQueUsa*}, {@code ListenerQueUsa*},
 * {@code StartupQueUsa*}, {@code PeriodicQueUsa*}, {@code MessagingSuelto} y {@code FeatureQueUsa*} violan una regla
 * cada una; el resto cumple todas.
 */
package ar.edu.um.programacion2.turnos.archejemplo;
