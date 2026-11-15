package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import ar.edu.um.programacion2.turnos.reservation.application.service.ReservationService;
import ar.edu.um.programacion2.turnos.reservation.domain.model.EventOutcome;
import ar.edu.um.programacion2.turnos.reservation.domain.model.NoEffectReason;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada del topic de acciones de la cátedra (contrato §15.2): <strong>un solo listener para los seis tipos
 * de evento</strong>. Lee lo común, despacha por tipo a la fachada y deja el resultado en el log.
 * <p>
 * Recibe el {@link ConsumerRecord} y no un {@code @Payload}: un tombstone (valor {@code null}) con {@code @Payload String}
 * falla antes de llegar acá. Además da la partición, el offset y la clave para el log.
 * <p>
 * <strong>El offset se confirma cuando este método vuelve bien</strong> ({@code AckMode.RECORD}), y eso pasa después de que
 * el caso de uso confirmó su transacción. Todos los casos esperados vuelven bien: un mensaje roto o de un tipo desconocido
 * no se reintenta (DPT-22), porque reintentarlo no lo arregla. Solo sale una excepción si pasó algo inesperado (la base,
 * un choque de versión con un pedido HTTP), y la toma el {@code DefaultErrorHandler}.
 * <p>
 * En el log van el tipo, el {@code eventId}, la partición, el offset y la clave ({@code reservationProcessId}): ninguno es
 * un dato personal. Nunca el cuerpo ni el {@code message} de la cátedra, que es para la app.
 */
@Component
@RequiredArgsConstructor
public class ReservationEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationEventListener.class);

    private final ReservationService reservationService;

    @KafkaListener(topics = "${catedra.kafka.actions-topic}")
    public void onEvent(ConsumerRecord<String, String> message) {
        String where = "(partición " + message.partition() + ", offset " + message.offset() + ", clave " + message.key() + ")";
        switch (ReservationEventMessageMapper.parse(message.value())) {
            case ParsedEvent.Event parsed -> handle(parsed.event(), where);
            case ParsedEvent.NotYetSupported pending -> LOG.info(
                "{} {} {}: los resultados todavía no se procesan; no se registra y se confirma el offset",
                pending.eventType(),
                pending.eventId(),
                where
            );
            case ParsedEvent.UnknownType unknown -> LOG.warn(
                "Evento {} {}: eventType desconocido para el contrato v1; se descarta y se confirma el offset",
                unknown.eventId(),
                where
            );
            case ParsedEvent.Malformed malformed -> LOG.warn(
                "Evento {} {}: mal formado ({}); se descarta sin reintento y se confirma el offset",
                malformed.eventId(),
                where,
                malformed.detail()
            );
        }
    }

    private void handle(ReservationEvent event, String where) {
        EventOutcome outcome = switch (event) {
            case ReservationEvent.PhoneRequested requested -> reservationService.handlePhoneRequested(requested);
        };
        log(event, outcome, where);
    }

    /**
     * El nivel sale del resultado: lo normal (aplicado, duplicado, tardío sobre un cerrado) va en INFO; lo que no debería
     * pasar y conviene mirar, en WARN.
     */
    private static void log(ReservationEvent event, EventOutcome outcome, String where) {
        switch (outcome) {
            case EventOutcome.Applied applied -> LOG.info(
                "{} {} {}: proceso {} -> {}",
                event.eventType(),
                event.eventId(),
                where,
                applied.from(),
                applied.to()
            );
            case EventOutcome.Duplicate duplicate -> LOG.info("{} {} {}: ya procesado, no se repite", event.eventType(), event.eventId(), where);
            case EventOutcome.ProcessNotFound notFound -> LOG.warn(
                "{} {} {}: no hay un proceso local con ese reservationProcessId; se registra sin efecto",
                event.eventType(),
                event.eventId(),
                where
            );
            case EventOutcome.NoEffect noEffect when expected(noEffect.reason()) -> LOG.info(
                "{} {} {}: sin efecto, el proceso está en {} ({})",
                event.eventType(),
                event.eventId(),
                where,
                noEffect.state(),
                noEffect.reason()
            );
            case EventOutcome.NoEffect noEffect -> LOG.warn(
                "{} {} {}: sin efecto, el proceso está en {} ({}); se registra",
                event.eventType(),
                event.eventId(),
                where,
                noEffect.state(),
                noEffect.reason()
            );
        }
    }

    /** Un tardío sobre un cerrado o un confirmado es normal con Kafka; un segundo pedido o un hecho que no corresponde, no. */
    private static boolean expected(NoEffectReason reason) {
        return switch (reason) {
            case FINAL_STATE, CONFIRMED_ONLY_CANCELS -> true;
            case NOT_FORWARD, NOT_APPLICABLE -> false;
        };
    }
}
