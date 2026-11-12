package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AppointmentSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.HoldConfirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PatientName;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.HoldGateway;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;

/**
 * Adaptador de salida: crea y confirma holds (contrato §9 y §10). Son operaciones con efectos: un solo intento, y una
 * respuesta exitosa que no se puede leer es "resultado desconocido" (el hold o el confirm pudo haberse aplicado), nunca
 * "respuesta inválida". El cuerpo del pedido se escribe con el {@code JsonMapper} propio, no con el de Spring.
 */
@Component
public class CatedraHoldClient implements HoldGateway {

    static final String HOLDS_PATH = "/api/appointment-holds";
    static final String CONFIRM_PATH = "/api/appointment-holds/{holdId}/confirm";

    private static final Logger LOG = LoggerFactory.getLogger(CatedraHoldClient.class);

    private final RestClient restClient;

    public CatedraHoldClient(@Qualifier(CatedraClientConfiguration.CATEDRA_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CatedraHold createHold(AppointmentSlot slot, String externalPatientId) {
        Objects.requireNonNull(slot, "Falta el turno");
        CatedraDtos.HoldRequest request = new CatedraDtos.HoldRequest(
            slot.professionalId(),
            slot.date().toString(),
            CatedraFormat.format(slot.startTime()),
            Objects.requireNonNull(externalPatientId, "Falta el externalPatientId")
        );
        byte[] body = post(restClient.post().uri(HOLDS_PATH), request, "al crear el hold", 201);
        return readSuccess(body, CatedraDtos.HoldResponse.class, CatedraMapper::toHold, "al crear el hold");
    }

    @Override
    public HoldConfirmation confirmHold(String holdId, String externalPatientId, PatientName patient) {
        Objects.requireNonNull(holdId, "Falta el holdId");
        Objects.requireNonNull(patient, "Falta el nombre del paciente");
        CatedraDtos.ConfirmRequest request = new CatedraDtos.ConfirmRequest(
            Objects.requireNonNull(externalPatientId, "Falta el externalPatientId"),
            patient.firstName(),
            patient.lastName()
        );
        // El holdId va como variable de la URI: RestClient lo codifica, así un valor raro no cambia la ruta.
        byte[] body = post(restClient.post().uri(CONFIRM_PATH, holdId), request, "al confirmar el hold", 202);
        return readSuccess(body, CatedraDtos.ConfirmResponse.class, CatedraMapper::toConfirmation, "al confirmar el hold");
    }

    private static byte[] post(RestClient.RequestBodySpec spec, Object request, String operation, int expectedStatus) {
        return CatedraCalls.call(
            spec.contentType(MediaType.APPLICATION_JSON).body(CatedraJson.MAPPER.writeValueAsBytes(request)),
            operation,
            true,
            expectedStatus
        );
    }

    private static <D, R> R readSuccess(byte[] body, Class<D> type, Function<D, R> mapper, String operation) {
        try {
            return mapper.apply(CatedraJson.MAPPER.readValue(body, type));
        } catch (JacksonException | IllegalArgumentException e) {
            String detail = e instanceof JacksonException jackson ? jackson.getOriginalMessage() : e.getMessage();
            LOG.warn("Respuesta exitosa ilegible {}: {}", operation, detail);
            throw new CatedraOutcomeUnknownException(
                "La cátedra respondió con éxito " + operation + ", pero la respuesta no respeta el contrato: resultado desconocido",
                e
            );
        }
    }
}
