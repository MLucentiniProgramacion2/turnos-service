package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * Un proceso como lo ve un administrador (CU-12, DPT-14): todo lo que sirve para diagnosticar, incluidos los
 * identificadores de la integración con la cátedra.
 * <p>
 * <strong>Sin datos personales:</strong> ni el teléfono ni los nombres del paciente, ni el texto del motivo de cancelación
 * que escribió el usuario. Para diagnosticar no hacen falta (el paciente es el titular de la cuenta, DPT-02, y sus datos
 * están en {@code /api/admin/users}), y el enunciado pide no exponer datos en respuestas que no los necesitan (§9).
 *
 * @param owner el dueño: su {@code id} (el que autoriza, DPT-21) y el login que tenía al iniciar la reserva (puede haber
 *     cambiado: el actual está en {@code /api/admin/users})
 * @param integration los identificadores de la cátedra y de los eventos, para cruzar con la cátedra y con Kafka
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminReservationProcessResponse(
    UUID id,
    Owner owner,
    String status,
    Instant createdAt,
    Instant statusChangedAt,
    Instant expiresAt,
    ReservationProcessResponse.Appointment appointment,
    ReservationProcessResponse.Professional professional,
    Integration integration,
    ReservationProcessResponse.Rejection rejection,
    ReservationProcessResponse.Confirmation confirmation,
    Cancellation cancellation,
    ReservationProcessResponse.Closing closing
) {
    public record Owner(long userId, String loginAtStart) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Integration(
        String holdId,
        String reservationProcessId,
        Long reservationId,
        String requestEventId,
        String lastSubmittedEventId
    ) {}

    /** Cuándo se canceló y si el usuario dejó un motivo, sin el texto. */
    public record Cancellation(Instant cancelledAt, boolean withReason) {}
}
