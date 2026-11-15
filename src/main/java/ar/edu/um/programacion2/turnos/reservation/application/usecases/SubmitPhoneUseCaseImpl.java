package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcessUpdater.Updated;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneNumber;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneSubmission;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SubmitPhoneCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SubmitPhoneResult;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.SubmitPhoneUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.EventIdGenerator;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionNotSentException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionPublisher;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionUnconfirmedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-06: valida el pedido, publica {@code AdditionalInformationSubmitted} y después guarda el envío.
 * <pre>
 * [buscar el proceso del usuario] → ¿acepta el teléfono? → normalizar → publicar en Kafka (afuera) → [releer, aplicar, guardar]
 * </pre>
 * <ul>
 * <li><strong>El dueño:</strong> el proceso se busca por su id y el {@code userId} del token. Uno ajeno responde lo mismo
 * que uno inexistente (DPT-19).</li>
 * <li><strong>El estado y el plazo los decide el modelo</strong> ({@code canSubmitPhone}); el caso de uso solo mira la
 * respuesta. Así nunca llama a {@code phoneSubmitted} sin pedido.</li>
 * <li><strong>Publicar afuera de una transacción y guardar después:</strong> la publicación puede tardar hasta 8 s, y el
 * proceso se guarda en {@code PHONE_SUBMITTED} solo si el broker confirmó. Si no confirmó (6a), el proceso no cambia.</li>
 * <li><strong>El guardado relee</strong> ({@link ReservationProcessUpdater}) y siempre guarda el {@code eventId} enviado,
 * aunque el resultado ya haya llegado por Kafka y el estado no cambie (7a).</li>
 * <li><strong>El teléfono nunca va al log</strong> ni a un mensaje de excepción.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class SubmitPhoneUseCaseImpl implements SubmitPhoneUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(SubmitPhoneUseCaseImpl.class);

    private final ReservationProcessRepository repository;
    private final PhoneSubmissionPublisher publisher;
    private final ReservationProcessUpdater updater;
    private final EventIdGenerator eventIdGenerator;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Override
    public SubmitPhoneResult submit(SubmitPhoneCommand command) {
        // Pasos 2 y 3.
        Optional<ReservationProcess> found = transactionTemplate.execute(status -> repository.findByIdAndOwner(command.processId(), command.userId()));
        if (found.isEmpty()) {
            return new SubmitPhoneResult.NotFound();
        }
        ReservationProcess process = found.orElseThrow();
        Instant now = clock.instant();
        SubmitPhoneResult notAllowed = switch (process.canSubmitPhone(now)) {
            case ALLOWED -> null;
            case NOT_REQUESTED_YET -> new SubmitPhoneResult.NotRequestedYet();
            case ALREADY_SUBMITTED -> new SubmitPhoneResult.AlreadySubmitted();
            case CLOSED -> new SubmitPhoneResult.Closed(process.getStatus());
            case DEADLINE_PASSED -> new SubmitPhoneResult.DeadlinePassed();
        };
        if (notAllowed != null) {
            return notAllowed;
        }

        // Paso 4.
        PhoneNumber phone;
        try {
            phone = PhoneNumber.of(command.rawPhone());
        } catch (IllegalArgumentException e) {
            return new SubmitPhoneResult.InvalidPhone();
        }

        // Pasos 5 y 6.
        CatedraHold hold = process.getHold().orElseThrow();
        String eventId = eventIdGenerator.next();
        PhoneSubmission submission = new PhoneSubmission(
            eventId,
            hold.reservationProcessId(),
            hold.holdId(),
            process.getPhoneRequest().orElseThrow().requestEventId(),
            phone,
            now
        );
        try {
            publisher.publish(submission);
        } catch (PhoneSubmissionException e) {
            String outcome = switch (e) {
                case PhoneSubmissionNotSentException notSent -> "no se publicó";
                case PhoneSubmissionUnconfirmedException unconfirmed -> "sin confirmación: pudo haber llegado";
            };
            LOG.warn("CU-06: el teléfono del proceso {} no se confirmó ({}); el proceso no cambia", process.getId(), outcome);
            return new SubmitPhoneResult.Unconfirmed();
        }

        // Paso 7.
        try {
            Updated updated = updater.apply(
                process.getId(),
                command.userId(),
                current -> current.phoneSubmitted(phone, eventId, clock.instant()),
                true
            );
            return new SubmitPhoneResult.Submitted(updated.process());
        } catch (RuntimeException e) {
            // 7b: el evento ya está en la cátedra. El proceso avanza cuando llegue el resultado (DPT-05).
            LOG.error(
                "CU-06: el teléfono se publicó pero no se pudo guardar el envío. proceso={} eventId={} reservationProcessId={}",
                process.getId(),
                eventId,
                hold.reservationProcessId(),
                e
            );
            return new SubmitPhoneResult.SaveFailed();
        }
    }
}
