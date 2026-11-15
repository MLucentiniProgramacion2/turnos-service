package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcessUpdater.Updated;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityPolicy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Closing;
import ar.edu.um.programacion2.turnos.reservation.domain.model.HoldConfirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PatientName;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalSnapshot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ScheduledSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SlotGenerator;
import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.UserProfile;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.StartReservationUseCase;
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
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.HoldGateway;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.IntermediateProcessConflictException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProfessionalAgendaProvider;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessIdGenerator;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.UserProfileProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-04: valida el pedido, crea el hold en la cátedra y lo confirma inicialmente, en una sola acción (DPT-03).
 * <p>
 * <strong>Ningún pedido HTTP va adentro de una transacción de base.</strong> La secuencia intercala transacciones cortas
 * ({@link TransactionTemplate}, programáticas porque un mismo método necesita varias) con las llamadas:
 * <pre>
 * validar → [buscar duplicados y perfil] → catálogo → [guardar CREATING_HOLD] → POST hold → [guardar HOLD_CREATED]
 *         → POST confirm → [guardar WAITING_FOR_PHONE_REQUEST]
 * </pre>
 * Con una sola transacción alrededor de todo, la conexión a la base quedaría tomada durante los timeouts de la cátedra, y
 * un rollback borraría el registro de un hold que en la cátedra sí existe. {@code Propagation.NEVER} lo hace cumplir: si
 * alguien llamara a este caso de uso con una transacción abierta, falla antes de pedir nada.
 * <p>
 * <strong>Los duplicados se buscan antes de llamar al catálogo</strong> (pasos 3 y 4): repetir un pedido es seguro aunque
 * el catálogo esté caído.
 * <p>
 * <strong>El proceso se guarda antes de cada llamada con efectos</strong> (DPT-03): en {@code CREATING_HOLD} antes del POST
 * del hold, y con los datos del {@code 201} antes del confirm. Así, pase lo que pase después, queda registrado qué se le
 * pidió a la cátedra.
 * <p>
 * <strong>El POST del hold nunca se repite</strong> (DPT-06). Cada falla de la cátedra se decide con un {@code switch}
 * completo sobre las excepciones {@code sealed} del puerto; la tabla está en {@code docs/use-cases/CU-04}.
 * <p>
 * Los guardados posteriores al alta pasan por {@link ReservationProcessUpdater} (releer, aplicar, guardar): si un evento de
 * Kafka cambió el proceso mientras se esperaba a la cátedra, la máquina de estados devuelve "sin efecto" y se responde con
 * el estado actual (13a, 13b). Este caso de uso no pregunta en qué estado está el proceso: llama a la transición y mira el
 * resultado.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class StartReservationUseCaseImpl implements StartReservationUseCase {

    /** Motivos propios del cierre en {@code FAILED}, cuando no hay un {@code code} de la cátedra que guardar. */
    static final String HOLD_NOT_SENT = "HOLD_NOT_SENT";
    static final String HOLD_OUTCOME_UNKNOWN = "HOLD_OUTCOME_UNKNOWN";
    static final String CATEDRA_ACCESS_DENIED = "CATEDRA_ACCESS_DENIED";
    static final String CATEDRA_INVALID_RESPONSE = "CATEDRA_INVALID_RESPONSE";
    /** El {@code code} con que la cátedra informa el vencimiento (contrato §13.1 y §15.8). */
    static final String HOLD_EXPIRED = "HOLD_EXPIRED";

    private static final Logger LOG = LoggerFactory.getLogger(StartReservationUseCaseImpl.class);

    private final ReservationProcessRepository repository;
    private final UserProfileProvider userProfiles;
    private final ProfessionalAgendaProvider agendaProvider;
    private final HoldGateway holdGateway;
    private final ReservationProcessUpdater updater;
    private final ReservationProcessIdGenerator idGenerator;
    private final AvailabilityPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    /** Lo que se averigua en la primera transacción: o ya hay una respuesta, o el perfil para seguir. */
    private record Preconditions(StartReservationResult answer, UserProfile profile, PatientName patient) {
        static Preconditions answered(StartReservationResult answer) {
            return new Preconditions(answer, null, null);
        }
    }

    @Override
    public StartReservationResult start(StartReservationCommand command) {
        // Paso 2: lo que se puede rechazar sin consultar a nadie.
        LocalDateTime now = policy.localDateTime(clock.instant());
        if (!policy.isBookableDate(command.date(), now.toLocalDate())) {
            return new StartReservationResult.InvalidDate();
        }
        if (command.professionalId() <= 0) {
            return new StartReservationResult.ProfessionalNotFound();
        }
        // La cátedra recibe la hora como HH:mm: un turno con segundos o fracciones no existe.
        boolean wholeMinute = command.startTime().getSecond() == 0 && command.startTime().getNano() == 0;
        if (!wholeMinute || !command.date().atTime(command.startTime()).isAfter(now)) {
            return new StartReservationResult.InvalidSlot();
        }

        // Pasos 3 y 4, y el perfil: una transacción, solo de lectura, antes de llamar al catálogo.
        Preconditions preconditions = transactionTemplate.execute(status -> checkPreconditions(command));
        if (preconditions.answer() != null) {
            return preconditions.answer();
        }

        // Pasos 5 y 6.
        Optional<ProfessionalAgenda> found;
        try {
            found = agendaProvider.fetchAgenda(command.professionalId(), command.login());
        } catch (CatalogException e) {
            return catalogFailure(e);
        }
        if (found.isEmpty()) {
            return new StartReservationResult.ProfessionalNotFound();
        }
        ProfessionalAgenda agenda = found.orElseThrow();
        if (!agenda.professional().enabled()) {
            return new StartReservationResult.ProfessionalDisabled();
        }
        Optional<ScheduledSlot> scheduled = SlotGenerator.find(agenda, command.date(), command.startTime());
        if (scheduled.isEmpty()) {
            return new StartReservationResult.InvalidSlot();
        }

        // Paso 7.
        ReservationProcess draft = ReservationProcess.start(
            idGenerator.next(),
            preconditions.profile().owner(),
            scheduled.orElseThrow().slot(),
            snapshot(agenda, scheduled.orElseThrow()),
            preconditions.patient(),
            clock.instant()
        );
        ReservationProcess creating;
        try {
            creating = transactionTemplate.execute(status -> repository.insert(draft));
        } catch (IntermediateProcessConflictException e) {
            return afterConflict(command, draft);
        }
        return holdAndConfirm(creating);
    }

    private Preconditions checkPreconditions(StartReservationCommand command) {
        Optional<ReservationProcess> sameSlot = repository.findActiveBySlot(
            command.userId(),
            command.professionalId(),
            command.date(),
            command.startTime()
        );
        if (sameSlot.isPresent()) {
            return Preconditions.answered(new StartReservationResult.AlreadyExists(sameSlot.orElseThrow()));
        }
        Optional<ReservationProcess> inProgress = repository.findIntermediateByUser(command.userId());
        if (inProgress.isPresent()) {
            return Preconditions.answered(new StartReservationResult.AnotherInProgress(inProgress.orElseThrow().getId()));
        }
        Optional<UserProfile> profile = userProfiles.findById(command.userId());
        if (profile.isEmpty()) {
            return Preconditions.answered(new StartReservationResult.UserNotFound());
        }
        Optional<PatientName> patient = profile.orElseThrow().patientName();
        if (patient.isEmpty()) {
            return Preconditions.answered(new StartReservationResult.ProfileIncomplete());
        }
        return new Preconditions(null, profile.orElseThrow(), patient.orElseThrow());
    }

    /**
     * 7a: el alta chocó con el índice único parcial, porque otro pedido del mismo usuario se guardó entre la búsqueda y el
     * alta. Se busca al que ganó en una transacción nueva (la del alta quedó deshecha). Si no aparece (terminó justo en el
     * medio), el alta se intenta una vez más; si vuelve a chocar, se responde que hay una reserva en curso.
     */
    private StartReservationResult afterConflict(StartReservationCommand command, ReservationProcess draft) {
        Preconditions winner = transactionTemplate.execute(status -> checkPreconditions(command));
        if (winner.answer() != null) {
            return winner.answer();
        }
        try {
            return holdAndConfirm(transactionTemplate.execute(status -> repository.insert(draft)));
        } catch (IntermediateProcessConflictException e) {
            Preconditions second = transactionTemplate.execute(status -> checkPreconditions(command));
            return second.answer() != null ? second.answer() : new StartReservationResult.AnotherInProgress(null);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Pasos 8 a 14
    // ---------------------------------------------------------------------------------------------------------------

    private StartReservationResult holdAndConfirm(ReservationProcess creating) {
        String externalPatientId = creating.getOwner().externalPatientId();

        // Paso 8. Un solo intento: nunca se repite (DPT-06).
        CatedraHold hold;
        try {
            hold = holdGateway.createHold(creating.getSlot(), externalPatientId);
        } catch (CatedraException e) {
            return holdFailed(creating, e);
        }

        // Paso 10: los datos del 201 se guardan antes de confirmar.
        Updated withHold;
        try {
            withHold = update(creating, process -> process.holdCreated(hold, clock.instant()));
        } catch (RuntimeException e) {
            // Sin el hold guardado no se confirma: nadie podría reconciliar una reserva de la que no hay registro.
            LOG.error(
                "CU-04: el hold se creó pero no se pudo guardar; no se confirma y vence solo. proceso={} holdId={} reservationProcessId={}",
                creating.getId(),
                hold.holdId(),
                hold.reservationProcessId(),
                e
            );
            return new StartReservationResult.HoldNotRecorded();
        }
        if (!withHold.applied()) {
            // Alguien cerró el proceso mientras se pedía el hold: no se confirma.
            return new StartReservationResult.Started(withHold.process());
        }

        // Paso 11.
        HoldConfirmation confirmation;
        try {
            confirmation = holdGateway.confirmHold(hold.holdId(), externalPatientId, creating.getPatient());
        } catch (CatedraException e) {
            return confirmFailed(withHold.process(), e);
        }

        // Paso 13.
        return confirmAccepted(withHold.process(), confirmation.expiresAt());
    }

    /**
     * El confirm se aplicó ({@code 202} o {@code 409 RESERVATION_PROCESS_ALREADY_CONFIRMED}). Si el pedido de teléfono
     * llegó antes (13a) o el proceso ya se cerró (13b), la transición no tiene efecto y se responde el estado actual.
     *
     * @param expiresAt el vencimiento que informó la cátedra, o {@code null} si no informó uno
     */
    private StartReservationResult confirmAccepted(ReservationProcess withHold, Instant expiresAt) {
        try {
            return new StartReservationResult.Started(update(withHold, process -> process.confirmAccepted(expiresAt, clock.instant())).process());
        } catch (RuntimeException e) {
            // El confirm ya está en la cátedra: el pedido de teléfono hace avanzar el proceso desde HOLD_CREATED (CU-05 3.3a).
            CatedraHold hold = withHold.getHold().orElseThrow();
            LOG.error(
                "CU-04: el confirm se aplicó pero no se pudo guardar; el proceso queda en HOLD_CREATED. proceso={} holdId={} reservationProcessId={}",
                withHold.getId(),
                hold.holdId(),
                hold.reservationProcessId(),
                e
            );
            return new StartReservationResult.Started(withHold);
        }
    }

    /** Falló el POST del hold: el proceso se cierra en {@code FAILED} con el motivo, y se le responde al usuario. */
    private StartReservationResult holdFailed(ReservationProcess creating, CatedraException failure) {
        record Outcome(String reason, StartReservationResult result) {}
        Outcome outcome = switch (failure) {
            // No se llegó a conectar: el hold no se creó. Se cierra igual, para dejar registro y liberar al usuario.
            case CatedraUnavailableException unavailable -> new Outcome(HOLD_NOT_SENT, new StartReservationResult.CatedraUnavailable());
            // 8a: pudo haberse creado. No se repite: un hold huérfano vence solo y nunca es una reserva.
            case CatedraOutcomeUnknownException unknown -> new Outcome(HOLD_OUTCOME_UNKNOWN, new StartReservationResult.HoldOutcomeUnknown());
            case CatedraRejectedException rejected -> new Outcome(rejected.code().name(), rejection(rejected, "crear el hold"));
            case CatedraAccessDeniedException denied -> {
                LOG.error("CU-04: la cátedra rechazó la credencial técnica al crear el hold: {}", denied.getMessage());
                yield new Outcome(CATEDRA_ACCESS_DENIED, new StartReservationResult.CatedraUnavailable());
            }
            case CatedraInvalidResponseException invalid -> {
                LOG.error("CU-04: la cátedra respondió fuera de su contrato al crear el hold: {}", invalid.getMessage());
                yield new Outcome(CATEDRA_INVALID_RESPONSE, new StartReservationResult.UpstreamInvalidResponse());
            }
        };
        LOG.warn("CU-04: no se creó el hold del proceso {} ({}): pasa a FAILED", creating.getId(), outcome.reason());
        try {
            update(creating, process -> process.failed(new Closing(outcome.reason(), null), clock.instant()));
        } catch (RuntimeException e) {
            LOG.error("CU-04: no se pudo guardar el proceso {} como FAILED; queda en CREATING_HOLD", creating.getId(), e);
        }
        return outcome.result();
    }

    /** Falló el POST del confirm. El proceso tiene el hold guardado ({@code HOLD_CREATED}). */
    private StartReservationResult confirmFailed(ReservationProcess withHold, CatedraException failure) {
        return switch (failure) {
            // 11a: no se sabe si se aplicó, o no se pudo ni conectar. Queda en HOLD_CREATED y se responde 202 (DPT-07).
            case CatedraUnavailableException unavailable -> {
                LOG.warn("CU-04: no se pudo confirmar el hold del proceso {}: queda en HOLD_CREATED", withHold.getId());
                yield new StartReservationResult.Started(withHold);
            }
            case CatedraOutcomeUnknownException unknown -> {
                LOG.warn("CU-04: resultado desconocido al confirmar el hold del proceso {}: queda en HOLD_CREATED", withHold.getId());
                yield new StartReservationResult.Started(withHold);
            }
            case CatedraRejectedException rejected -> switch (rejected.code()) {
                // 11d: ya estaba confirmado. Es un éxito; la cátedra no informa un vencimiento nuevo.
                case RESERVATION_PROCESS_ALREADY_CONFIRMED -> confirmAccepted(withHold, null);
                // 11b (contrato §18.3).
                case HOLD_EXPIRED -> closeAfterConfirm(
                    withHold,
                    process -> process.expired(new Closing(HOLD_EXPIRED, null), clock.instant()),
                    new StartReservationResult.Expired()
                );
                default -> closeAfterConfirm(
                    withHold,
                    process -> process.failed(new Closing(rejected.code().name(), null), clock.instant()),
                    rejection(rejected, "confirmar el hold")
                );
            };
            case CatedraAccessDeniedException denied -> {
                LOG.error("CU-04: la cátedra rechazó la credencial técnica al confirmar el hold: {}", denied.getMessage());
                yield closeAfterConfirm(
                    withHold,
                    process -> process.failed(new Closing(CATEDRA_ACCESS_DENIED, null), clock.instant()),
                    new StartReservationResult.CatedraUnavailable()
                );
            }
            case CatedraInvalidResponseException invalid -> {
                LOG.error("CU-04: la cátedra respondió fuera de su contrato al confirmar el hold: {}", invalid.getMessage());
                yield closeAfterConfirm(
                    withHold,
                    process -> process.failed(new Closing(CATEDRA_INVALID_RESPONSE, null), clock.instant()),
                    new StartReservationResult.UpstreamInvalidResponse()
                );
            }
        };
    }

    /**
     * Cierra el proceso después de un rechazo del confirm. Si la transición no tiene efecto es porque un evento de Kafka ya
     * lo movió (llegó el pedido de teléfono, o se cerró): entonces el rechazo no manda y se responde el estado actual.
     */
    private StartReservationResult closeAfterConfirm(
        ReservationProcess withHold,
        Function<ReservationProcess, TransitionResult> closing,
        StartReservationResult whenClosed
    ) {
        Updated updated;
        try {
            updated = update(withHold, closing);
        } catch (RuntimeException e) {
            LOG.error("CU-04: no se pudo cerrar el proceso {} después del rechazo del confirm; queda en HOLD_CREATED", withHold.getId(), e);
            return whenClosed;
        }
        return updated.applied() ? whenClosed : new StartReservationResult.Started(updated.process());
    }

    /** Qué se le responde al usuario por un rechazo de la cátedra con {@code code} (8b a 8e y 11c, 11e). */
    private static StartReservationResult rejection(CatedraRejectedException rejected, String operation) {
        return switch (rejected.code()) {
            case SLOT_ALREADY_HELD, SLOT_ALREADY_RESERVED -> new StartReservationResult.SlotNotAvailable();
            case INVALID_SLOT -> new StartReservationResult.InvalidSlot();
            case PROFESSIONAL_DISABLED -> new StartReservationResult.ProfessionalDisabled();
            case PROFESSIONAL_NOT_FOUND -> new StartReservationResult.ProfessionalNotFound();
            // Lo demás (VALIDATION_ERROR, HOLD_NOT_FOUND, HOLD_OWNER_MISMATCH, EXTERNAL_PATIENT_MISMATCH...) solo se explica
            // por un error nuestro: no se le muestra al usuario (DPT-18).
            default -> {
                LOG.error("CU-04: la cátedra rechazó {} con {} {}: es un error propio", operation, rejected.status(), rejected.code());
                yield new StartReservationResult.InternalError();
            }
        };
    }

    private Updated update(ReservationProcess process, Function<ReservationProcess, TransitionResult> transition) {
        return updater.apply(process.getId(), process.getOwner().userId(), transition);
    }

    private static StartReservationResult catalogFailure(CatalogException failure) {
        return switch (failure) {
            case CatalogUnavailableException unavailable -> {
                LOG.warn("CU-04: el catálogo no está disponible: {}", unavailable.getMessage());
                yield new StartReservationResult.CatalogUnavailable(unavailable.retryAfter().orElse(null));
            }
            case CatalogAccessDeniedException denied -> {
                LOG.error("CU-04: el catálogo rechazó el token de servicio: {}", denied.getMessage());
                yield new StartReservationResult.CatalogUnavailable(null);
            }
            case CatalogInvalidResponseException invalid -> {
                LOG.error("CU-04: el catálogo respondió fuera de su contrato: {}", invalid.getMessage());
                yield new StartReservationResult.UpstreamInvalidResponse();
            }
        };
    }

    /** Los datos históricos del profesional (DPT-12). Son solo para mostrar: un texto más largo que la columna se recorta. */
    private static ProfessionalSnapshot snapshot(ProfessionalAgenda agenda, ScheduledSlot scheduled) {
        return new ProfessionalSnapshot(
            truncate(agenda.professional().firstName()),
            truncate(agenda.professional().lastName()),
            truncate(agenda.professional().category().name()),
            scheduled.slotDurationMinutes()
        );
    }

    private static String truncate(String text) {
        return text.length() <= ProfessionalSnapshot.MAX_LENGTH ? text : text.substring(0, ProfessionalSnapshot.MAX_LENGTH);
    }
}
