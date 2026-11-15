package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado de consultar los turnos disponibles (CU-03). Es {@code sealed}: el controller decide la respuesta con un
 * {@code switch} completo, y un resultado nuevo no compila hasta que alguien decida qué se le responde a la app.
 * <p>
 * Todo lo que acá figura es <strong>esperable</strong> (un rango inválido, un profesional que no existe, el catálogo
 * caído): por eso son resultados y no excepciones. Ninguno lleva el detalle técnico de la falla: ese va al log.
 */
public sealed interface AvailabilityResult {
    /** Flujo principal, y también 5a y 7a: una disponibilidad sin turnos es una respuesta válida. */
    record Available(ProfessionalAvailability availability) implements AvailabilityResult {
        public Available {
            Objects.requireNonNull(availability, "Falta la disponibilidad");
        }
    }

    /** 2b: el rango no cumple DPT-10. No se llamó a nadie. */
    record InvalidRange(RangeViolation violation) implements AvailabilityResult {
        public InvalidRange {
            Objects.requireNonNull(violation, "Falta la regla que no se cumple");
        }
    }

    /** 4a: el catálogo no conoce al profesional. */
    record ProfessionalNotFound() implements AvailabilityResult {}

    /** 4b: el profesional está deshabilitado. No se ofrecen turnos. */
    record ProfessionalDisabled() implements AvailabilityResult {}

    /**
     * 3a: sin agenda vigente no se calcula la disponibilidad (DPT-11). También cuando el catálogo rechaza el token de
     * servicio: es un error de configuración nuestro que no se le muestra al usuario (DPT-18).
     *
     * @param retryAfter cuándo conviene reintentar, si el catálogo lo informó; si no, {@code null}
     */
    record CatalogUnavailable(Duration retryAfter) implements AvailabilityResult {
        public Optional<Duration> retryAfterHint() {
            return Optional.ofNullable(retryAfter);
        }
    }

    /** 6a y 6c: la cátedra no respondió, o rechazó la credencial técnica (que tampoco se le muestra al usuario). */
    record CatedraUnavailable() implements AvailabilityResult {}

    /** El catálogo o la cátedra respondieron algo que no respeta su contrato. */
    record UpstreamInvalidResponse() implements AvailabilityResult {}

    /** 6b: la cátedra rechazó un pedido que turnos ya había validado. Es un error nuestro. */
    record InternalError() implements AvailabilityResult {}
}
