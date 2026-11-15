package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.producer;

import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneSubmission;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionNotSentException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionPublisher;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionUnconfirmedException;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.CatedraKafkaProperties;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador de salida: publica {@code AdditionalInformationSubmitted} en el topic de teléfono (contrato §15.5), con clave
 * = {@code reservationProcessId}, y <strong>espera la confirmación del broker</strong> (CU-06 paso 6).
 * <p>
 * <strong>Por qué esperar y no solo {@code whenComplete} como en el Tema 13:</strong> el caso de uso guarda
 * {@code PHONE_SUBMITTED} recién cuando el broker confirmó, y le tiene que responder al usuario si se envió o no. Con un
 * callback, el pedido HTTP terminaría antes de saberlo. Se espera el futuro con {@code get} y un plazo
 * ({@link CatedraKafkaProperties.Producer#sendTimeout()}) mayor que {@code max.block.ms} + {@code delivery.timeout.ms}:
 * el productor siempre termina el futuro antes, con éxito o con su error. El {@code whenComplete} queda solo para el log.
 * <p>
 * <strong>No se publica sin haber comprobado que el topic existe</strong> ({@link PhoneTopicCheck}). El productor no
 * tiene {@code allow.auto.create.topics}: si el broker crea topics solo, un nombre mal configurado crearía uno nuevo, el
 * broker confirmaría y el teléfono se perdería sin ningún error (medido con Testcontainers, ADR 0006). Normalmente ya la
 * hizo la comprobación de fondo del arranque; si todavía no salió bien, el envío la espera con el plazo del usuario (5 s).
 * Si no se pudo hacer, no se publica.
 * <p>
 * El teléfono y el cuerpo del evento nunca van al log.
 */
@Component
public class KafkaPhoneSubmissionPublisher implements PhoneSubmissionPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaPhoneSubmissionPublisher.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final KafkaTemplate<String, String> template;
    private final CatedraKafkaProperties properties;
    private final PhoneTopicCheck topicCheck;

    public KafkaPhoneSubmissionPublisher(
        KafkaTemplate<String, String> phoneKafkaTemplate,
        CatedraKafkaProperties properties,
        PhoneTopicCheck topicCheck
    ) {
        this.template = phoneKafkaTemplate;
        this.properties = properties;
        this.topicCheck = topicCheck;
    }

    @Override
    public void publish(PhoneSubmission submission) {
        ensureTopicExists();
        String topic = properties.getPhoneTopic();
        String key = submission.reservationProcessId();
        CompletableFuture<SendResult<String, String>> future;
        try {
            future = template.send(topic, key, toJson(submission));
        } catch (RuntimeException e) {
            throw unconfirmed(submission, e);
        }
        future.whenComplete((result, failure) -> {
            if (failure == null) {
                LOG.info(
                    "AdditionalInformationSubmitted {} publicado (proceso {}, partición {}, offset {})",
                    submission.eventId(),
                    key,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset()
                );
            }
        });
        try {
            future.get(properties.getProducer().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw unconfirmed(submission, e.getCause() != null ? e.getCause() : e);
        } catch (TimeoutException e) {
            throw unconfirmed(submission, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unconfirmed(submission, e);
        }
    }

    /** El evento del contrato. {@code occurredAt} va en segundos, como en los ejemplos de §15.5. */
    String toJson(PhoneSubmission submission) {
        return JSON.writeValueAsString(
            new AdditionalInformationSubmittedMessage(
                submission.eventId(),
                AdditionalInformationSubmittedMessage.EVENT_TYPE,
                properties.getGroupId(),
                submission.reservationProcessId(),
                submission.holdId(),
                submission.requestEventId(),
                new AdditionalInformationSubmittedMessage.Information(submission.phone().value()),
                submission.occurredAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                AdditionalInformationSubmittedMessage.SCHEMA_VERSION
            )
        );
    }

    /** Si la comprobación de fondo todavía no salió bien, la espera con el plazo del usuario. */
    private void ensureTopicExists() {
        try {
            topicCheck.ensureChecked(properties.getProducer().getTopicCheckTimeout());
        } catch (PhoneTopicCheckException e) {
            if (e.topicMissing()) {
                // Error de configuración nuestro: el nombre de CATEDRA_KAFKA_PHONE_TOPIC no es el de la cátedra.
                LOG.error("El topic de teléfono configurado no existe en el broker; revisar CATEDRA_KAFKA_PHONE_TOPIC. No se publica");
                throw new PhoneSubmissionNotSentException("El topic de teléfono no existe", e.getCause());
            }
            LOG.warn("No se pudo comprobar el topic de teléfono ({}): no se publica", e.reason());
            throw new PhoneSubmissionNotSentException("No se pudo comprobar el topic de teléfono", e.getCause());
        }
    }

    private static PhoneSubmissionUnconfirmedException unconfirmed(PhoneSubmission submission, Throwable failure) {
        LOG.warn(
            "AdditionalInformationSubmitted {} sin confirmación del broker (proceso {}): {}; pudo haber llegado",
            submission.eventId(),
            submission.reservationProcessId(),
            describe(failure)
        );
        return new PhoneSubmissionUnconfirmedException("El broker no confirmó la publicación del teléfono", failure);
    }

    /**
     * La clase y el mensaje de la falla real. {@code KafkaProducerException} de Spring envuelve la de Kafka con un mensaje
     * fijo ("Failed to send"): se desenvuelve. Los mensajes de Kafka nombran el topic y la partición, nunca el valor; el
     * registro que falló, que sí lo tiene, no se usa.
     */
    static String describe(Throwable failure) {
        Throwable real = failure instanceof KafkaProducerException && failure.getCause() != null ? failure.getCause() : failure;
        return real.getMessage() == null ? real.getClass().getName() : real.getClass().getName() + ": " + real.getMessage();
    }
}
