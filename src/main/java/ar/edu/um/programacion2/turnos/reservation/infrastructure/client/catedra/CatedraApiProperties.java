package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    /**
     * Tamaño de página al listar reservas ({@code GET /api/appointments}, CU-09 y CU-11). La cátedra recorta a 2000 sin
     * avisar (medido el 16/11), así que no se puede pedir más. Con {@code externalPatientId} y un rango de fechas, lo normal
     * es una página con pocos ítems: 200 deja margen sin traer respuestas de más de 1 MB por ZeroTier.
     */
    @NotNull
    @Min(1)
    @Max(2000)
    private Integer listPageSize = 200;

    /** Cuántas páginas se leen como máximo en un listado: más es una respuesta fuera de lo esperable. */
    @NotNull
    @Min(1)
    @Max(50)
    private Integer listMaxPages = 5;

    /**
     * Plazo total de un listado, con todas sus páginas. No se empieza una página que no podría terminar a tiempo (el peor
     * caso de un pedido es {@code connect-timeout + read-timeout}), así que es un techo real.
     */
    @NotNull
    private Duration listDeadline = Duration.ofSeconds(10);

    /** El peor caso de un solo pedido: conectar y leer la respuesta entera. */
    public Duration requestWorstCase() {
        return connectTimeout.plus(readTimeout);
    }

    /**
     * La primera página se pide siempre; las siguientes, solo si queda al menos el peor caso de un pedido. Con un plazo
     * menor que ese peor caso, la primera ya podría pasarlo y el plazo dejaría de ser un techo.
     */
    @AssertTrue(message = "catedra.api.list-deadline tiene que ser al menos connect-timeout + read-timeout")
    public boolean isListDeadlineEnoughForOneRequest() {
        return connectTimeout == null || readTimeout == null || listDeadline == null || listDeadline.compareTo(requestWorstCase()) >= 0;
    }

    @Override
    public String toString() {
        return (
            "CatedraApiProperties{url=" +
            url +
            ", token=[oculto], connectTimeout=" +
            connectTimeout +
            ", readTimeout=" +
            readTimeout +
            ", listPageSize=" +
            listPageSize +
            ", listMaxPages=" +
            listMaxPages +
            ", listDeadline=" +
            listDeadline +
            "}"
        );
    }
}
