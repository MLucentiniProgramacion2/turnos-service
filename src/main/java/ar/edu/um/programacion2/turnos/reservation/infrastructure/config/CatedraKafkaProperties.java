package ar.edu.um.programacion2.turnos.reservation.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de Kafka de la cátedra ({@code catedra.kafka.*}, ADR 0006): el consumidor del topic de acciones
 * (CU-05 y CU-07). Está en {@code infrastructure.config} y no en {@code messaging}: la usan el listener y el productor,
 * y en {@code messaging} no puede haber clases sueltas (ArchUnit).
 * <p>
 * Bootstrap, topic y grupo no tienen valor por defecto: si faltan o están vacíos, la aplicación no arranca. Hace falta
 * validarlos acá porque Kafka solo se queja al crear el consumidor, y con el listener apagado eso nunca pasa. Un broker
 * que no responde, en cambio, no frena el arranque (enunciado §8): turnos sigue atendiendo lo que no depende de Kafka.
 */
@Validated
@ConfigurationProperties(prefix = "catedra.kafka")
@Getter
@Setter
@ToString
public class CatedraKafkaProperties {

    /** Brokers de la cátedra (contrato §3), solo accesibles por ZeroTier. */
    @NotBlank
    private String bootstrapServers;

    /** Topic de acciones: {@code kafkaAppointmentActionsTopic} de {@code integration} (contrato §15.2). */
    @NotBlank
    private String actionsTopic;

    /**
     * Grupo de este servicio: {@code alumnos-<groupId>-turnos}. Es propio y no el del catálogo, ni el sugerido sin sufijo
     * (ADR 0005 del catálogo): un rebalanceo espera a todos los miembros del grupo.
     */
    @NotBlank
    private String consumerGroup;

    /** Topic de teléfono: {@code kafkaAppointmentPhoneTopic} de {@code integration} (contrato §15.2). Ahí publica CU-06. */
    @NotBlank
    private String phoneTopic;

    /** {@code groupId} de la integración: va en cada {@code AdditionalInformationSubmitted} (contrato §15.5). */
    @NotBlank
    private String groupId;

    /** Los plazos del productor de CU-06, con el usuario esperando la respuesta. */
    @Valid
    @NotNull
    private Producer producer = new Producer();

    /**
     * Si el listener arranca. Solo se apaga en {@code src/test}, para que cada contexto no se conecte a un broker que no
     * existe. No tiene variable de entorno: en dev no puede quedar sordo sin querer.
     */
    private boolean listenerEnabled = true;

    /**
     * {@code max.poll.interval.ms}: cuánto puede tardar <strong>una entrega</strong> antes de que Kafka saque al consumidor
     * del grupo. Cada reintento del {@code DefaultErrorHandler} vuelve por otro {@code poll}, así que lo que cuenta es un
     * intento, no el mensaje completo. La cuenta está en el ADR 0006 y la fija {@code ReservationEventWorstCaseTest}.
     */
    @NotNull
    @DurationMin(seconds = 1)
    private Duration maxPollInterval = Duration.ofMinutes(2);

    /**
     * Cada cuánto se reintenta después de una falla de autorización. Sin esto, spring-kafka detiene el contenedor para
     * siempre y la aplicación queda viva y sorda (medido en el catálogo, ADR 0005 del catálogo).
     */
    @NotNull
    @DurationMin(seconds = 1)
    private Duration authRetryInterval = Duration.ofMinutes(1);

    /**
     * Las esperas entre intentos cuando procesar un evento falla de forma inesperada (DPT-22): 1, 2 y 4 s, o sea hasta 3
     * reintentos. Después se confirma el offset y el proceso lo recupera CU-11.
     */
    @NotEmpty
    private List<@NotNull @DurationMin(millis = 1) Duration> retryDelays = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

    /**
     * Los plazos del productor (ADR 0006). Por defecto, Kafka espera hasta 60 s en {@code max.block.ms} y 120 s en
     * {@code delivery.timeout.ms}: con el usuario esperando, se acotan a los valores de DPT-08 (2 s y 5 s).
     * <ul>
     * <li>{@code max.block.ms}: cuánto puede bloquear {@code send} esperando la metadata del topic;</li>
     * <li>{@code request.timeout.ms}: cuánto se espera la respuesta de un pedido al broker;</li>
     * <li>{@code delivery.timeout.ms}: cuánto puede tardar la confirmación desde que el registro entra al buffer, con los
     * reintentos de Kafka incluidos. Kafka exige que sea al menos {@code linger.ms} + {@code request.timeout.ms}, o el
     * productor no se construye; {@code linger.ms} es 0.</li>
     * <li>{@code topic-check-timeout}: el plazo de la comprobación de que el topic existe, antes del primer envío.</li>
     * </ul>
     */
    @Getter
    @Setter
    @ToString
    public static class Producer {

        @NotNull
        @DurationMin(millis = 100)
        private Duration maxBlock = Duration.ofSeconds(2);

        @NotNull
        @DurationMin(millis = 100)
        private Duration requestTimeout = Duration.ofSeconds(3);

        @NotNull
        @DurationMin(millis = 100)
        private Duration deliveryTimeout = Duration.ofSeconds(5);

        @NotNull
        @DurationMin(millis = 100)
        private Duration topicCheckTimeout = Duration.ofSeconds(2);

        /** La regla de Kafka, validada al arrancar en vez de fallar al construir el productor en el primer envío. */
        @AssertTrue(message = "delivery-timeout tiene que ser mayor o igual que request-timeout (linger.ms es 0)")
        public boolean isDeliveryTimeoutCoversRequestTimeout() {
            return deliveryTimeout == null || requestTimeout == null || deliveryTimeout.compareTo(requestTimeout) >= 0;
        }

        /**
         * Cuánto se espera el futuro del envío: más que {@code max.block.ms} + {@code delivery.timeout.ms}, así el productor
         * siempre lo termina antes (con éxito o con su error), y un 503 nunca deja un envío "en vuelo" sin resolver.
         */
        public Duration sendTimeout() {
            return maxBlock.plus(deliveryTimeout).plusSeconds(1);
        }
    }
}
