package ar.edu.um.programacion2.turnos.reservation.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
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

    /** La reconciliación de procesos pendientes (CU-11). */
    @Valid
    @NotNull
    private Reconciliation reconciliation = new Reconciliation();

    /**
     * {@code reservation.reconciliation.*} (CU-11, DPT-09, DPT-24). Se valida al arrancar: un valor fuera de rango no
     * levanta la aplicación.
     */
    @Getter
    @Setter
    @ToString
    public static class Reconciliation {

        /** Si se programa la reconciliación. Solo {@code src/test} la apaga: los tests la prenden o llaman al caso de uso. */
        private boolean enabled = true;

        /**
         * Cada cuánto corre, desde que termina la anterior ({@code fixedDelay}). La primera corre al arrancar. Menos de 10 s
         * martillaría a la cátedra; más de 10 min dejaría procesos trabados más que un hold entero.
         */
        @NotNull
        @DurationMin(seconds = 10)
        @DurationMax(minutes = 10)
        private Duration interval = Duration.ofSeconds(60);

        /**
         * DPT-24: {@code CREATING_HOLD} y {@code HOLD_CREATED} se toman solo con más de esto sin cambios. Tiene que superar
         * los 14 s que puede esperar CU-04 (hold y confirm, 7 s cada uno) con margen.
         */
        @NotNull
        @DurationMin(seconds = 30)
        @DurationMax(minutes = 10)
        private Duration minAge = Duration.ofMinutes(1);

        /** DPT-09: margen sobre {@code expiresAt} antes de preguntarle a la cátedra por un proceso vencido. */
        @NotNull
        @DurationMin(seconds = 0)
        @DurationMax(minutes = 10)
        private Duration expiryMargin = Duration.ofMinutes(1);

        /** DPT-09: cuánto se espera, desde {@code expiresAt}, a una reserva que la cátedra sigue teniendo en {@code PHONE_PENDING}. */
        @NotNull
        @DurationMax(hours = 1)
        private Duration phonePendingMaxWait = Duration.ofMinutes(10);

        /** DPT-08: las esperas entre reintentos en segundo plano. */
        @NotNull
        @Size(max = 5)
        private List<@NotNull @DurationMin(seconds = 0) @DurationMax(minutes = 1) Duration> retryDelays = List.of(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4)
        );

        /** Cuántos procesos le preguntan a la cátedra como máximo en una ejecución. */
        @Min(1)
        @Max(500)
        private int maxProcessesPerRun = 50;

        /** Después de este tiempo, una ejecución no empieza otro proceso. */
        @NotNull
        @DurationMin(seconds = 30)
        @DurationMax(minutes = 10)
        private Duration runDeadline = Duration.ofMinutes(2);

        @AssertTrue(message = "reservation.reconciliation.phone-pending-max-wait tiene que ser mayor que expiry-margin")
        public boolean isPhonePendingMaxWaitAfterMargin() {
            return phonePendingMaxWait == null || expiryMargin == null || phonePendingMaxWait.compareTo(expiryMargin) > 0;
        }
    }
}
