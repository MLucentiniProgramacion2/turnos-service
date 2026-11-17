package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;

/** Resultado de cancelar una reserva propia (CU-10). Es {@code sealed}: el controller decide con un {@code switch} completo. */
public sealed interface CancelReservationResult {
    /**
     * Paso 8 y 4a: la reserva está cancelada. El proceso es el actual: lo canceló este pedido, ya estaba cancelado, o llegó
     * {@code AppointmentCancelled} antes de guardar (7a).
     */
    record Cancelled(ReservationProcess process) implements CancelReservationResult {
        public Cancelled {
            Objects.requireNonNull(process, "Falta el proceso");
        }
    }

    /** 3a: el proceso no existe o es de otro usuario: la misma respuesta (DPT-19). */
    record NotFound() implements CancelReservationResult {}

    /** 4b y 6a: no se puede cancelar. {@code status} es el estado local actual. */
    record NotCancellable(ReservationProcessStatus status) implements CancelReservationResult {
        public NotCancellable {
            Objects.requireNonNull(status, "Falta el estado");
        }
    }

    /** 5a: no se pudo conectar con la cátedra (o rechazó la credencial técnica): seguro que no se canceló. */
    record CatedraUnavailable() implements CancelReservationResult {}

    /** 5a: la cátedra no respondió después de recibir el pedido: puede haberse cancelado. El proceso sigue confirmado. */
    record OutcomeUnknown() implements CancelReservationResult {}

    /** 6b: la cátedra respondió fuera de su contrato. */
    record UpstreamInvalidResponse() implements CancelReservationResult {}

    /** 6b: un rechazo que solo se explica por un error propio, o la cancelación se aplicó y no se pudo guardar. */
    record InternalError() implements CancelReservationResult {}
}
