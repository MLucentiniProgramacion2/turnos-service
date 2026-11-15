package ar.edu.um.programacion2.turnos.reservation.infrastructure.config;

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
}
