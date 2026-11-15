package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Un proceso de reserva como lo ve la app (contrato en {@code docs/api-rest.md}): lo devuelven CU-04 y CU-06, y lo va a
 * devolver CU-08.
 * <p>
 * <strong>No expone nada de la integración con la cátedra</strong> ({@code holdId}, {@code reservationProcessId},
 * {@code reservationId}, los {@code eventId}) ni datos personales (teléfono, nombres del paciente): la app opera con el
 * {@code id} local y no necesita ninguno. Los bloques que no corresponden al estado no se mandan ({@code NON_NULL}).
 *
 * @param expiresAt hasta cuándo se puede completar el proceso, según la cátedra; no viene antes de tener el hold
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReservationProcessResponse(
    UUID id,
    String status,
    Instant expiresAt,
    Appointment appointment,
    Professional professional,
    PhoneRequest phoneRequest,
    Rejection rejection,
    Confirmation confirmation,
    Cancellation cancellation,
    Closing closing
) {
    public record Appointment(
        long professionalId,
        LocalDate date,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime endTime
    ) {}

    /** Datos del profesional tal como eran al iniciar la reserva (DPT-12): solo para mostrar. */
    public record Professional(String firstName, String lastName, String categoryName, int slotDurationMinutes) {}

    /** La cátedra pidió el teléfono; {@code message} es su texto para el usuario, si lo mandó. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhoneRequest(String message) {}

    /** El último rechazo del teléfono. No es final: se puede volver a enviar hasta {@code expiresAt}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rejection(String reason, String message) {}

    public record Confirmation(Instant confirmedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cancellation(String reason, Instant cancelledAt) {}

    /** Por qué terminó en {@code EXPIRED}, {@code INVALID} o {@code FAILED}: un código, no un texto para mostrar. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Closing(String reason, String message) {}
}
