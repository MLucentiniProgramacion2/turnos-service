package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del acceso a la API REST de la cátedra ({@code catedra.api.*}, ADR 0004 de turnos y del catálogo).
 * <p>
 * Usa un prefijo propio y no {@code application.*}: {@code ApplicationProperties} de JHipster rechaza las propiedades que
 * no declara ({@code ignoreUnknownFields = false}), y así la configuración del adaptador queda en su feature.
 * <p>
 * La URL y el token no tienen valor por defecto: si faltan o están vacíos, la aplicación no arranca. Es una clase y no un
 * record porque el {@code toString()} de un record imprimiría el token.
 */
@Validated
@ConfigurationProperties(prefix = "catedra.api")
@Getter
@Setter
public class CatedraApiProperties {

    /** URL base de la API REST de la cátedra (contrato §3), solo accesible por ZeroTier. */
    @NotNull
    private URI url;

    /** JWT técnico de la cuenta del proyecto (contrato §4). Nunca se loguea ni se muestra. */
    @NotBlank
    private String token;

    /** Tiempo máximo para establecer la conexión (DPT-08). */
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    /**
     * Tiempo máximo para recibir la respuesta completa, cuerpo incluido (DPT-08): con el cliente HTTP del JDK es un plazo
     * total, no de inactividad. Las respuestas de estas operaciones son chicas, así que alcanza con 5 s.
     */
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(5);

    @Override
    public String toString() {
        return (
            "CatedraApiProperties{url=" +
            url +
            ", token=[oculto], connectTimeout=" +
            connectTimeout +
            ", readTimeout=" +
            readTimeout +
            "}"
        );
    }
}
