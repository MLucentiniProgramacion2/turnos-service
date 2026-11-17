package ar.edu.um.programacion2.turnos.reservation.domain.model;

/**
 * Estado de una reserva en la cátedra (contrato §11). No es el estado del proceso local: la cátedra crea la reserva al
 * confirmar el hold ({@code PHONE_PENDING}) y la pasa a {@code FAILED} si vence sin teléfono (medido el 16/11: la 5858).
 * <p>
 * {@link #UNRECOGNIZED} es un valor que el contrato no lista. El ítem <strong>se conserva</strong> con este estado en vez
 * de descartarse: para la reconciliación (CU-11), una reserva que está en un estado desconocido no es lo mismo que una que
 * no está, y tomarla como ausente podría cerrar como vencido un proceso que en la cátedra sigue vivo.
 */
public enum CatedraReservationStatus {
    PHONE_PENDING,
    CONFIRMED,
    CANCELLED,
    FAILED,
    UNRECOGNIZED;

    /** El {@code status} recibido como texto; {@code null} o un valor desconocido da {@link #UNRECOGNIZED}. */
    public static CatedraReservationStatus from(String status) {
        for (CatedraReservationStatus value : values()) {
            if (value != UNRECOGNIZED && value.name().equals(status)) {
                return value;
            }
        }
        return UNRECOGNIZED;
    }
}
