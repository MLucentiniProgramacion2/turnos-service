package ar.edu.um.programacion2.turnos.reservation.domain.model;

/**
 * Si un proceso acepta el teléfono en este momento (CU-06 pasos 3 y 3a a 3d). Lo responde
 * {@link ReservationProcess#canSubmitPhone}: así CU-06 no pregunta por estados y nunca llega a
 * {@link ReservationProcess#phoneSubmitted} con un proceso que no tiene pedido (eso es un error de programación).
 */
public enum PhoneSubmissionCheck {
    /** {@code PHONE_REQUESTED} o {@code PHONE_REJECTED}, antes de {@code expiresAt}. */
    ALLOWED,
    /** 3a: la cátedra todavía no pidió el teléfono ({@code CREATING_HOLD}, {@code HOLD_CREATED}, {@code WAITING_FOR_PHONE_REQUEST}). */
    NOT_REQUESTED_YET,
    /** 3b: el teléfono ya se envió y falta el resultado. */
    ALREADY_SUBMITTED,
    /** 3c: {@code CONFIRMED} o un estado final. */
    CLOSED,
    /** 3d: pasó {@code expiresAt}. El proceso no se modifica: lo cierra el evento de vencimiento o la reconciliación. */
    DEADLINE_PASSED,
}
