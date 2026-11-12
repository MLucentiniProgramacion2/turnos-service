package ar.edu.um.programacion2.turnos.reservation.domain.model;

/** Por qué una transición no cambió el proceso. Ninguno es un error: el evento se registra como procesado (DPT-15). */
public enum NoEffectReason {
    /** El proceso está en un estado final: ningún evento lo modifica (CU-07 2.3a). */
    FINAL_STATE,
    /** El proceso está en {@code CONFIRMED} y el hecho no es una cancelación (CU-07 2.3b, CU-04 13b). */
    CONFIRMED_ONLY_CANCELS,
    /** El proceso ya está en ese estado o más adelante: sería retroceder o repetir (CU-04 13a, CU-05 3.3c). */
    NOT_FORWARD,
    /** El hecho no corresponde a ese estado, aunque no sea retroceder (CU-07 2.3c y 2.3e, o desde CREATING_HOLD). */
    NOT_APPLICABLE,
}
