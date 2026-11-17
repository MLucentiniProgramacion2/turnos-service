package ar.edu.um.programacion2.turnos.reservation.domain.model;

/**
 * Si un proceso se puede cancelar (CU-10 pasos 4, 4a y 4b). Lo responde {@link ReservationProcess#canCancel()}: así CU-10
 * no pregunta por estados.
 */
public enum CancellationCheck {
    /** {@code CONFIRMED}: se pide la cancelación a la cátedra. */
    ALLOWED,
    /** 4a: ya está {@code CANCELLED}. La cancelación es idempotente: se responde el estado actual sin llamar a nadie. */
    ALREADY_CANCELLED,
    /** 4b: en curso u otro estado final. Solo se cancela una reserva confirmada. */
    NOT_CANCELLABLE,
}
