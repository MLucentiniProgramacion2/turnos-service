package ar.edu.um.programacion2.turnos.reservation.domain.model;

/** Por qué un rango de fechas no sirve para consultar turnos (DPT-10). No es un error de programación: lo manda el usuario. */
public enum RangeViolation {
    /** {@code from} es posterior a {@code to}. */
    FROM_AFTER_TO,
    /** El rango tiene más de {@link AvailabilityPolicy#MAX_RANGE_DAYS} días. */
    TOO_LONG,
    /** {@code from} es anterior a hoy. */
    IN_THE_PAST,
    /** {@code to} es posterior a hoy más el horizonte. */
    BEYOND_HORIZON,
}
