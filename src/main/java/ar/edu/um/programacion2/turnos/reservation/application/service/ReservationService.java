package ar.edu.um.programacion2.turnos.reservation.application.service;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityResult;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.GetAvailabilityUseCase;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Fachada de la reserva: los adaptadores de entrada (controllers y, más adelante, el listener de Kafka) llaman acá y nunca
 * a los casos de uso ni a los adaptadores de salida.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final GetAvailabilityUseCase getAvailabilityUseCase;

    /** CU-03. */
    public AvailabilityResult getAvailability(long professionalId, LocalDate from, LocalDate to, String requestedBy) {
        return getAvailabilityUseCase.getAvailability(professionalId, from, to, requestedBy);
    }
}
