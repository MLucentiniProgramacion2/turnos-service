package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AppointmentSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityPolicy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.OccupancyReport;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAvailability;
import ar.edu.um.programacion2.turnos.reservation.domain.model.RangeViolation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ScheduledSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SlotGenerator;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.GetAvailabilityUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.OccupancyProvider;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProfessionalAgendaProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CU-03: turnos libres = turnos de la agenda vigente del catálogo, menos los que ya empezaron, menos las ocupaciones de la
 * cátedra. No guarda nada y no abre ninguna transacción: no toca la base.
 * <p>
 * El orden importa, porque cada paso evita los siguientes:
 * <ol>
 * <li>El rango (DPT-10), contra el "hoy" de la zona de atención. Si no sirve, no se llama a nadie (2b).</li>
 * <li>El catálogo. Si falla, el profesional no existe o está deshabilitado, no se llama a la cátedra (3a, 4a, 4b).</li>
 * <li>Los turnos de la agenda, sin los que ya empezaron. Si no queda ninguno (5a), tampoco se llama a la cátedra: no hay
 * nada que descontar. El caso de uso lista los ya iniciados después de las ocupaciones; se descartan antes porque el
 * resultado es el mismo y ahorra una llamada.</li>
 * <li>Las ocupaciones de la cátedra, en una sola llamada para todo el rango, y sin reintentos: el usuario está esperando
 * (DPT-08).</li>
 * </ol>
 * Las fallas de los dos servicios llegan como excepciones {@code sealed} de los puertos y se traducen a un resultado con un
 * {@code switch} completo: si mañana un puerto suma una falla, esto no compila hasta decidir qué se responde.
 */
@Component
@RequiredArgsConstructor
public class GetAvailabilityUseCaseImpl implements GetAvailabilityUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(GetAvailabilityUseCaseImpl.class);

    private final ProfessionalAgendaProvider agendaProvider;
    private final OccupancyProvider occupancyProvider;
    private final AvailabilityPolicy policy;
    private final Clock clock;

    @Override
    public AvailabilityResult getAvailability(long professionalId, LocalDate from, LocalDate to, String requestedBy) {
        LocalDateTime now = policy.localDateTime(clock.instant());
        Optional<RangeViolation> violation = policy.checkRange(from, to, now.toLocalDate());
        if (violation.isPresent()) {
            return new AvailabilityResult.InvalidRange(violation.orElseThrow());
        }
        if (professionalId <= 0) {
            // Ningún profesional tiene un id así: no hace falta preguntarle al catálogo.
            return new AvailabilityResult.ProfessionalNotFound();
        }

        Optional<ProfessionalAgenda> found;
        try {
            found = agendaProvider.fetchAgenda(professionalId, requestedBy);
        } catch (CatalogException e) {
            return catalogFailure(e);
        }
        if (found.isEmpty()) {
            return new AvailabilityResult.ProfessionalNotFound();
        }
        ProfessionalAgenda agenda = found.orElseThrow();
        if (!agenda.professional().enabled()) {
            return new AvailabilityResult.ProfessionalDisabled();
        }

        List<AppointmentSlot> upcoming = SlotGenerator.generate(agenda, from, to)
            .stream()
            .map(ScheduledSlot::slot)
            .filter(slot -> !slot.hasStartedAt(now))
            .toList();
        if (upcoming.isEmpty()) {
            return available(agenda, from, to, upcoming);
        }

        OccupancyReport occupancies;
        try {
            occupancies = occupancyProvider.fetchOccupancies(professionalId, from, to);
        } catch (CatedraException e) {
            return catedraFailure(e);
        }
        return available(agenda, from, to, upcoming.stream().filter(slot -> !occupancies.isOccupied(slot)).toList());
    }

    private AvailabilityResult available(ProfessionalAgenda agenda, LocalDate from, LocalDate to, List<AppointmentSlot> freeSlots) {
        return new AvailabilityResult.Available(ProfessionalAvailability.of(agenda.professional(), from, to, policy.zone(), freeSlots));
    }

    private static AvailabilityResult catalogFailure(CatalogException failure) {
        return switch (failure) {
            case CatalogUnavailableException unavailable -> {
                LOG.warn("CU-03: el catálogo no está disponible: {}", unavailable.getMessage());
                yield new AvailabilityResult.CatalogUnavailable(unavailable.retryAfter().orElse(null));
            }
            case CatalogAccessDeniedException denied -> {
                // Error de configuración nuestro: el usuario ve lo mismo que con el catálogo caído (DPT-18).
                LOG.error("CU-03: el catálogo rechazó el token de servicio: {}", denied.getMessage());
                yield new AvailabilityResult.CatalogUnavailable(null);
            }
            case CatalogInvalidResponseException invalid -> {
                LOG.error("CU-03: el catálogo respondió fuera de su contrato: {}", invalid.getMessage());
                yield new AvailabilityResult.UpstreamInvalidResponse();
            }
        };
    }

    private static AvailabilityResult catedraFailure(CatedraException failure) {
        return switch (failure) {
            case CatedraUnavailableException unavailable -> {
                LOG.warn("CU-03: la cátedra no está disponible: {}", unavailable.getMessage());
                yield new AvailabilityResult.CatedraUnavailable();
            }
            // Una consulta no tiene efectos, así que el puerto nunca lanza esta; si pasara, es "no disponible".
            case CatedraOutcomeUnknownException unknown -> {
                LOG.warn("CU-03: la cátedra no respondió las ocupaciones: {}", unknown.getMessage());
                yield new AvailabilityResult.CatedraUnavailable();
            }
            case CatedraAccessDeniedException denied -> {
                // 6c: JWT técnico vencido o cuenta técnica mal configurada. No se le muestra al usuario (DPT-18).
                LOG.error("CU-03: la cátedra rechazó la credencial técnica: {}", denied.getMessage());
                yield new AvailabilityResult.CatedraUnavailable();
            }
            case CatedraRejectedException rejected -> {
                // 6b: INVALID_DATE_RANGE (o cualquier otro rechazo) después de validar el rango acá: el error es nuestro.
                LOG.error("CU-03: la cátedra rechazó un pedido de ocupaciones ya validado ({} {})", rejected.status(), rejected.code());
                yield new AvailabilityResult.InternalError();
            }
            case CatedraInvalidResponseException invalid -> {
                LOG.error("CU-03: la cátedra respondió fuera de su contrato: {}", invalid.getMessage());
                yield new AvailabilityResult.UpstreamInvalidResponse();
            }
        };
    }
}
