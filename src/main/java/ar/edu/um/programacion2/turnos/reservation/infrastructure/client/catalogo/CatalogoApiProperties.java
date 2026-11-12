package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catalogo;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del acceso al servicio de catálogo ({@code catalogo.api.*}). La URL no tiene valor por defecto: sin ella
 * la aplicación no arranca. No hay token acá: el de servicio se firma en cada pedido ({@code ServiceTokenIssuer}).
 */
@Validated
@ConfigurationProperties(prefix = "catalogo.api")
@Getter
@Setter
@ToString
public class CatalogoApiProperties {

    /** URL base del servicio de catálogo (local: {@code http://localhost:8081}). */
    @NotNull
    private URI url;

    /** Tiempo máximo para establecer la conexión (DPT-13). */
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Tiempo máximo para recibir la respuesta completa (DPT-13): plazo total, como en la cátedra (ADR 0004). */
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(5);
}
