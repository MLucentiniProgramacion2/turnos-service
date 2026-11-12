package ar.edu.um.programacion2.turnos.security;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Vida del token técnico de servicio que turnos manda al catálogo ({@code security.service-token.*}, DPT-16, ADR 0005).
 * <p>
 * Es configurable, pero <strong>no puede pasar de 5 minutos</strong>: el catálogo rechaza con {@code 403} un token con
 * {@code exp − iat} mayor (ADR 0007 del catálogo), así que una vida mayor haría fallar cada pedido de agenda. Se valida al
 * arrancar ({@link DurationMax}): con más de 5 minutos, la aplicación no arranca. Tampoco con menos de 30 s (ni con 0 ni
 * negativa): el token tiene que llegar vigente después de hasta 7 s de llamada (DPT-13) y con relojes que no coinciden
 * exactamente.
 */
@Validated
@ConfigurationProperties(prefix = "security.service-token")
public class ServiceTokenProperties {

    /** La vida máxima que acepta el catálogo. */
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(5);

    @NotNull
    @DurationMin(seconds = 30)
    @DurationMax(minutes = 5)
    private Duration lifetime = MAX_LIFETIME;

    public Duration getLifetime() {
        return lifetime;
    }

    public void setLifetime(Duration lifetime) {
        this.lifetime = lifetime;
    }
}
