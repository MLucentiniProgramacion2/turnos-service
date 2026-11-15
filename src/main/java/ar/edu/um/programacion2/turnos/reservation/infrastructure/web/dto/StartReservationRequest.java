package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Cuerpo de {@code POST /api/reservation-processes} (CU-04): el turno que el usuario eligió en CU-03. No trae ningún dato
 * del usuario: la identidad sale del token y el nombre del paciente, de su perfil (DPT-02, DPT-21).
 * <p>
 * Acá se valida presencia y formato ({@code 400 VALIDATION_ERROR}); si el turno existe, no empezó y está dentro del
 * horizonte lo decide el caso de uso. {@code startTime} acepta {@code HH:mm} y {@code HH:mm:ss} (contrato §4).
 */
public record StartReservationRequest(@NotNull @Positive Long professionalId, @NotNull LocalDate date, @NotNull LocalTime startTime) {}
