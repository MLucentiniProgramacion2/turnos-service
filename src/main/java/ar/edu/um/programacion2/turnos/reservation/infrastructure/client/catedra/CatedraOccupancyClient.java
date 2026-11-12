package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import ar.edu.um.programacion2.turnos.reservation.domain.model.OccupancyReport;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.OccupancyProvider;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;

/**
 * Adaptador de salida: {@code GET /api/appointment-occupancies} (contrato §8). Es una consulta sin efectos, así que una
 * falla de red o un {@code 5xx} es "no disponible", nunca "resultado desconocido".
 * <p>
 * El cliente HTTP del JDK repite <strong>solo</strong> un {@code GET} una vez si el servidor cierra la conexión sin
 * responder (medido en el #10; con un {@code POST} no lo hace). No se desactiva: es una consulta sin efectos (ADR 0004).
 */
@Component
public class CatedraOccupancyClient implements OccupancyProvider {

    static final String PATH = "/api/appointment-occupancies";

    private static final Logger LOG = LoggerFactory.getLogger(CatedraOccupancyClient.class);
    private static final String OPERATION = "al pedir las ocupaciones";

    private final RestClient restClient;

    public CatedraOccupancyClient(@Qualifier(CatedraClientConfiguration.CATEDRA_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public OccupancyReport fetchOccupancies(long professionalId, LocalDate from, LocalDate to) {
        byte[] body = CatedraCalls.call(
            restClient
                .get()
                .uri(uri -> uri.path(PATH).queryParam("professionalId", professionalId).queryParam("from", from).queryParam("to", to).build()),
            OPERATION,
            false,
            200
        );
        try {
            return CatedraMapper.toReport(CatedraJson.MAPPER.readValue(body, CatedraDtos.OccupanciesResponse.class), professionalId, from, to);
        } catch (JacksonException e) {
            // El detalle de Jackson va solo al log: nombra tipos de Java.
            LOG.warn("Ocupaciones inválidas: {}", e.getOriginalMessage());
            throw new CatedraInvalidResponseException("Ocupaciones inválidas: el cuerpo no es el JSON esperado", e);
        } catch (IllegalArgumentException e) {
            throw new CatedraInvalidResponseException("Ocupaciones inválidas: " + e.getMessage(), e);
        }
    }
}
