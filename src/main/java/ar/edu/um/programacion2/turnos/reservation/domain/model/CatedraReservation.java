package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Una reserva de la cátedra tal como la lista {@code GET /api/appointments} (contrato §11), con lo que turnos usa para
 * cruzarla con un proceso local y converger (CU-09, CU-11).
 * <p>
 * <strong>No tiene el teléfono ni los nombres del paciente</strong> que trae la respuesta: turnos no los necesita para
 * nada, así que no entran al modelo (enunciado §9). Lo obligatorio es lo que hace falta para cruzarla: el
 * {@code reservationProcessId}, la fecha y la hora. El resto puede faltar y lo resuelve la convergencia.
 *
 * @param reservationId el id de la reserva en la cátedra; {@code null} si no vino. Que exista <strong>no</strong> quiere
 *     decir que se confirmó: la cátedra crea la reserva en {@code PHONE_PENDING}
 * @param cancellationReason tal cual vino, sin recortar (lo recorta la convergencia)
 */
public record CatedraReservation(
    String reservationProcessId,
    String externalPatientId,
    Long reservationId,
    LocalDate date,
    LocalTime startTime,
    CatedraReservationStatus status,
    Instant confirmedAt,
    Instant cancelledAt,
    String cancellationReason
) {
    public CatedraReservation {
        Texts.requireText(reservationProcessId, "el reservationProcessId");
        Objects.requireNonNull(date, "Falta la fecha del turno");
        Objects.requireNonNull(startTime, "Falta la hora del turno");
        Objects.requireNonNull(status, "Falta el estado");
    }

    /** Sin el motivo de cancelación: lo escribe el usuario (va a la cátedra, no al log). */
    @Override
    public String toString() {
        return "CatedraReservation{reservationProcessId=" + reservationProcessId + ", reservationId=" + reservationId + ", status=" + status + "}";
    }
}
