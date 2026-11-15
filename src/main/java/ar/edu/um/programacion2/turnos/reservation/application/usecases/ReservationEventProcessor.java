package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.domain.model.EventOutcome;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * La secuencia común a todos los eventos de la cátedra (CU-05 y CU-07), <strong>en una sola transacción</strong>:
 * <ol>
 * <li>la barrera: registrar el {@code eventId}; si ya estaba, es una entrega repetida y no se hace nada más;</li>
 * <li>buscar el proceso por {@code reservationProcessId}; si no existe, el evento queda registrado sin efecto;</li>
 * <li>aplicar la transición que corresponde al tipo de evento (la decide cada caso de uso, no esta clase);</li>
 * <li>guardar el proceso si cambió.</li>
 * </ol>
 * Si algo falla (la base, o el bloqueo optimista porque un pedido HTTP cambió el mismo proceso), la transacción se deshace
 * entera, el {@code eventId} no queda registrado y la excepción sale hacia el {@code DefaultErrorHandler} del listener, que
 * reintenta: el siguiente intento lee el proceso de nuevo. El offset se confirma recién cuando esto vuelve bien.
 * <p>
 * La transacción tiene plazo ({@link EventTransactions}): un bloqueo trabado no retiene el hilo del listener sin límite.
 * <p>
 * Es la única copia de esta secuencia: los seis tipos de evento pasan por acá.
 */
@Component
@RequiredArgsConstructor
public class ReservationEventProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationEventProcessor.class);

    private final ProcessedEventRepository processedEvents;
    private final ReservationProcessRepository repository;
    private final EventTransactions transactions;
    private final Clock clock;

    /**
     * @param transition el hecho del modelo que corresponde al evento, con el instante actual
     */
    public EventOutcome process(ReservationEvent event, BiFunction<ReservationProcess, Instant, TransitionResult> transition) {
        Objects.requireNonNull(event, "Falta el evento");
        Objects.requireNonNull(transition, "Falta la transición");
        return transactions
            .template()
            .execute(status -> {
                Instant now = clock.instant();
                if (!processedEvents.registerIfAbsent(event.eventId(), event.eventType(), now)) {
                    return new EventOutcome.Duplicate();
                }
                Optional<ReservationProcess> found = repository.findByReservationProcessId(event.reservationProcessId());
                if (found.isEmpty()) {
                    return new EventOutcome.ProcessNotFound();
                }
                ReservationProcess process = found.orElseThrow();
                warnIfHoldDoesNotMatch(event, process);
                return switch (transition.apply(process, now)) {
                    case TransitionResult.Applied applied -> {
                        repository.save(process);
                        yield new EventOutcome.Applied(applied.from(), applied.to());
                    }
                    case TransitionResult.NoEffect noEffect -> new EventOutcome.NoEffect(noEffect.state(), noEffect.reason());
                };
            });
    }

    /**
     * El evento se aplica por {@code reservationProcessId}, que es la clave del contrato. Un {@code holdId} o un
     * {@code requestEventId} distinto del guardado no debería pasar (la cátedra los genera juntos): se deja una advertencia
     * para investigarlo, sin perder el evento. Un {@code requestEventId} distinto es justo lo que la cátedra informa como
     * {@code REQUEST_EVENT_MISMATCH} con {@code AppointmentProcessInvalid}: ese evento se aplica igual.
     */
    private static void warnIfHoldDoesNotMatch(ReservationEvent event, ReservationProcess process) {
        String savedHold = process.getHold().map(hold -> hold.holdId()).orElse(null);
        if (differ(event.holdId(), savedHold)) {
            warnMismatch(event, process, "holdId");
        }
        String savedRequest = process.getPhoneRequest().map(request -> request.requestEventId()).orElse(null);
        if (differ(event.requestEventId(), savedRequest)) {
            warnMismatch(event, process, "requestEventId");
        }
    }

    private static boolean differ(String received, String saved) {
        return received != null && saved != null && !saved.equals(received);
    }

    private static void warnMismatch(ReservationEvent event, ReservationProcess process, String field) {
        LOG.warn(
            "{} {} para el proceso {}: el {} no coincide con el guardado; se aplica por reservationProcessId",
            event.eventType(),
            event.eventId(),
            process.getId(),
            field
        );
    }
}
