package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Parámetros de la query de {@code GET /api/professionals/{id}/availability} (CU-03), juntos en un record para validarlos
 * con {@code @Valid}: si falta uno o no es una fecha {@code yyyy-MM-dd}, el {@code 400} lleva {@code code: VALIDATION_ERROR}
 * y un {@code fieldError} por parámetro, sin repetir el valor recibido.
 * <p>
 * Acá va solo lo que se puede validar sin saber qué día es: presencia y formato. Las reglas del rango (orden, 7 días, sin
 * pasado, horizonte) dependen de "hoy" y son del dominio ({@code AvailabilityPolicy}): responden
 * {@code INVALID_DATE_RANGE}. No sale de la web: el controller le pasa las dos fechas al caso de uso.
 */
public record AvailabilityParams(@NotNull LocalDate from, @NotNull LocalDate to) {}
