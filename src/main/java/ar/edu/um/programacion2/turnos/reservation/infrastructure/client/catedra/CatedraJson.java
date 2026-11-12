package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * El {@link JsonMapper} con el que se leen y escriben los JSON de la cátedra. Es propio y no el de Spring: el resultado no
 * depende de {@code spring.jackson.*}. Como en el catálogo, {@code ACCEPT_FLOAT_AS_INT} va apagado (prendido, Jackson
 * trunca {@code 7.5} a {@code 7} en silencio) y los campos desconocidos se ignoran (contrato §4: compatibles con v1).
 */
final class CatedraJson {

    static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private CatedraJson() {}
}
