package ar.edu.um.programacion2.turnos.reservation.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import lombok.Getter;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de las reglas de fechas de la reserva ({@code reservation.*}, DPT-10). Los dos valores tienen su valor por
 * defecto acá y no solo en {@code application.yml}: el YAML de {@code src/test} tapa al principal.
 * <p>
 * Se valida al arrancar: una zona que no existe (no se puede convertir a {@link ZoneId}) o un horizonte fuera de rango
 * impiden levantar la aplicación, en vez de fallar en la primera consulta.
 */
@Validated
@ConfigurationProperties(prefix = "reservation")
@Getter
@Setter
@ToString
public class ReservationProperties {

    /** Zona horaria de atención: las fechas y horas de los turnos son locales de esta zona (contrato §4). */
    @NotNull
    private ZoneId timeZone = ZoneId.of("America/Argentina/Mendoza");

    /** Cuántos días hacia adelante se ofrecen y se reservan turnos: el último día es hoy más este valor. */
    @NotNull
    @Min(1)
    @Max(365)
    private Integer bookingHorizonDays = 60;

    /**
     * Plazo de la transacción que procesa un evento de Kafka (CU-05 y CU-07): acota cuánto puede quedar trabado un intento
     * esperando un bloqueo de la base. Entra en la cuenta del peor caso del listener (ADR 0006).
     */
    @NotNull
    @DurationMin(seconds = 1)
    @DurationMax(seconds = 60)
    private Duration eventTransactionTimeout = Duration.ofSeconds(10);
}
