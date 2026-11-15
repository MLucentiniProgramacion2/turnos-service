package ar.edu.um.programacion2.turnos.reservation.domain.ports.in;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityResult;
import java.time.LocalDate;

/** CU-03: los turnos libres de un profesional en un rango de fechas. No guarda nada. */
public interface GetAvailabilityUseCase {
    /**
     * @param requestedBy el login del usuario autenticado, solo para la trazabilidad en el catálogo; puede ser {@code null}
     */
    AvailabilityResult getAvailability(long professionalId, LocalDate from, LocalDate to, String requestedBy);
}
