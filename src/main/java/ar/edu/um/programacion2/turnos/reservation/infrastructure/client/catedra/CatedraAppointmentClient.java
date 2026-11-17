package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraCancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationList;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.AppointmentGateway;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.LocalTimeFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;

/**
 * Adaptador de salida: las reservas de la cátedra (contrato §11 y §12): listarlas (CU-09, CU-11) y cancelarlas (CU-10).
 * <p>
 * <strong>El listado recorre todas las páginas</strong>, con estas reglas (CU-09 paso 4, medido el 16/11):
 * <ul>
 * <li><strong>No sigue el {@code Link}.</strong> Sus URL son absolutas y apuntan a un host que no elige turnos: seguirlas
 * mandaría el JWT técnico adonde diga la respuesta. La página siguiente se calcula con {@code page + 1}.</li>
 * <li><strong>El fin sale de {@code X-Total-Count}</strong>, no de comparar con el {@code size} pedido: la cátedra recorta el
 * tamaño a 2000 sin avisar, y con un {@code size} mayor una página llena parecería la última.</li>
 * <li><strong>El total no puede cambiar entre páginas.</strong> Si cambia (alguien reservó o canceló mientras se leía), los
 * ítems se corrieron y la lista puede tener huecos: es {@link CatedraUnavailableException}, y repetir es seguro.</li>
 * <li><strong>Cotas:</strong> hasta {@code list-max-pages} páginas (más es una respuesta inválida) y un plazo total
 * ({@code list-deadline}). Antes de pedir otra página se mira que quede al menos el peor caso de un pedido: así nunca se
 * empieza una que no puede terminar a tiempo, y el plazo es un techo de verdad. Se mide con {@link System#nanoTime()}, no
 * con el {@code Clock} de la reserva: ese es la hora del negocio (y los tests lo fijan), no un cronómetro.</li>
 * <li><strong>Un ítem que no se puede leer no tira la lista</strong> (como los eventos del #12): sin
 * {@code reservationProcessId}, o con la fecha o la hora ilegibles, se ignora con una advertencia y se cuenta; un
 * {@code status} desconocido se conserva como {@code UNRECOGNIZED}; un instante ilegible queda en {@code null} (lo
 * reemplaza la convergencia).</li>
 * </ul>
 * El cuerpo no se loguea nunca: trae el teléfono y los nombres de los pacientes, que el DTO ni siquiera declara.
 * <p>
 * El cliente del JDK repite un {@code GET} una vez si el servidor cierra la conexión sin responder (ADR 0004): para una
 * consulta es inofensivo.
 */
@Component
public class CatedraAppointmentClient implements AppointmentGateway {

    static final String APPOINTMENTS_PATH = "/api/appointments";
    static final String CANCEL_PATH = "/api/appointments/{reservationProcessId}/cancel";
    static final String TOTAL_COUNT = "X-Total-Count";

    private static final Logger LOG = LoggerFactory.getLogger(CatedraAppointmentClient.class);
    private static final String LIST_OPERATION = "al listar las reservas";
    private static final String CANCEL_OPERATION = "al cancelar la reserva";

    private final RestClient restClient;
    private final CatedraApiProperties properties;
    private final LongSupplier nanoTime;

    @Autowired
    public CatedraAppointmentClient(@Qualifier(CatedraClientConfiguration.CATEDRA_REST_CLIENT) RestClient restClient, CatedraApiProperties properties) {
        this(restClient, properties, System::nanoTime);
    }

    /** Para los tests: un cronómetro que se puede adelantar sin esperar. */
    CatedraAppointmentClient(RestClient restClient, CatedraApiProperties properties, LongSupplier nanoTime) {
        this.restClient = restClient;
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    @Override
    public CatedraReservationList listReservations(String externalPatientId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(externalPatientId, "Falta el externalPatientId");
        Objects.requireNonNull(from, "Falta la fecha desde");
        Objects.requireNonNull(to, "Falta la fecha hasta");
        int size = properties.getListPageSize();
        int maxPages = properties.getListMaxPages();
        long deadline = nanoTime.getAsLong() + properties.getListDeadline().toNanos();
        Duration worstCase = properties.requestWorstCase();

        Map<String, CatedraReservation> found = new LinkedHashMap<>();
        int ignored = 0;
        long received = 0;
        Long total = null;
        for (int page = 0; ; page++) {
            if (page >= maxPages) {
                LOG.error("La cátedra tiene más de {} páginas de {} reservas para un usuario y un rango: no se sigue", maxPages, size);
                throw new CatedraInvalidResponseException("El listado de reservas supera las " + maxPages + " páginas");
            }
            if (page > 0 && deadline - nanoTime.getAsLong() < worstCase.toNanos()) {
                LOG.warn("El listado de reservas no terminó en {}: no se pide la página {}", properties.getListDeadline(), page);
                throw new CatedraUnavailableException("El listado de reservas no terminó en el plazo");
            }
            CatedraCalls.Response response = CatedraCalls.callForResponse(request(externalPatientId, from, to, page, size), LIST_OPERATION, false, 200);
            long pageTotal = totalCount(response);
            if (total == null) {
                total = pageTotal;
            } else if (total != pageTotal) {
                LOG.warn("El total de reservas cambió entre páginas ({} y después {}): la lista no es consistente", total, pageTotal);
                throw new CatedraUnavailableException("El total de reservas cambió mientras se recorrían las páginas");
            }
            List<CatedraDtos.AppointmentItem> items = items(response.body());
            if (items.size() > size) {
                throw new CatedraInvalidResponseException("La página " + page + " trae más reservas que el tamaño pedido");
            }
            received += items.size();
            for (CatedraDtos.AppointmentItem item : items) {
                CatedraReservation reservation = toReservation(item);
                if (reservation == null) {
                    ignored++;
                } else {
                    // Un corrimiento puede repetir un ítem en dos páginas: cuenta una vez.
                    found.putIfAbsent(reservation.reservationProcessId(), reservation);
                }
            }
            if (received >= total) {
                return new CatedraReservationList(new ArrayList<>(found.values()), ignored);
            }
            if (items.isEmpty()) {
                LOG.error("La página {} de reservas vino vacía y faltan {} de {}", page, total - received, total);
                throw new CatedraInvalidResponseException("Una página de reservas vino vacía antes de llegar al total");
            }
        }
    }

    /**
     * {@code POST /api/appointments/{reservationProcessId}/cancel} (contrato §12). Con efectos: la misma clasificación que
     * el hold (solo no haber podido conectar prueba que no se aplicó). Sin motivo no se manda cuerpo (medido el 16/11: la
     * cátedra lo acepta). Un {@code 200} que no dice {@code CANCELLED} para ese proceso, o que no se puede leer, es
     * resultado desconocido: la cátedra dijo "éxito", así que la cancelación pudo haberse aplicado (ADR 0004). El motivo
     * nunca va al log.
     */
    @Override
    public CatedraCancellation cancel(String reservationProcessId, String reason) {
        Objects.requireNonNull(reservationProcessId, "Falta el reservationProcessId");
        // El reservationProcessId va como variable de la URI: RestClient lo codifica, así un valor raro no cambia la ruta.
        RestClient.RequestBodySpec post = restClient.post().uri(CANCEL_PATH, reservationProcessId);
        RestClient.RequestHeadersSpec<?> request = reason == null
            ? post
            : post.contentType(MediaType.APPLICATION_JSON).body(CatedraJson.MAPPER.writeValueAsBytes(new CatedraDtos.CancelRequest(reason)));
        byte[] body = CatedraCalls.call(request, CANCEL_OPERATION, true, 200);
        try {
            CatedraDtos.CancelResponse response = CatedraJson.MAPPER.readValue(body, CatedraDtos.CancelResponse.class);
            CatedraFormat.required(response, "el cuerpo");
            if (!"CANCELLED".equals(response.status())) {
                throw new IllegalArgumentException("status no es CANCELLED");
            }
            if (!reservationProcessId.equals(response.reservationProcessId())) {
                throw new IllegalArgumentException("reservationProcessId no es el pedido");
            }
            return new CatedraCancellation(reservationProcessId, response.reservationId(), CatedraFormat.instant(response.cancelledAt(), "cancelledAt"));
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            String detail = e instanceof JacksonException jackson ? jackson.getOriginalMessage() : e.getMessage();
            LOG.warn("Respuesta exitosa ilegible {}: {}", CANCEL_OPERATION, detail);
            throw new CatedraOutcomeUnknownException(
                "La cátedra respondió con éxito " + CANCEL_OPERATION + ", pero la respuesta no respeta el contrato: resultado desconocido",
                e
            );
        }
    }

    private RestClient.RequestHeadersSpec<?> request(String externalPatientId, LocalDate from, LocalDate to, int page, int size) {
        return restClient
            .get()
            .uri(uri ->
                uri
                    .path(APPOINTMENTS_PATH)
                    .queryParam("externalPatientId", externalPatientId)
                    .queryParam("from", from)
                    .queryParam("to", to)
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .build()
            );
    }

    private static long totalCount(CatedraCalls.Response response) {
        String value = response.headers().getFirst(TOTAL_COUNT);
        try {
            long total = Long.parseLong(Objects.requireNonNull(value));
            if (total < 0) {
                throw new NumberFormatException();
            }
            return total;
        } catch (NullPointerException | NumberFormatException e) {
            LOG.error("El listado de reservas vino sin un {} válido", TOTAL_COUNT);
            throw new CatedraInvalidResponseException("El listado de reservas no trae " + TOTAL_COUNT + " o no es un número");
        }
    }

    private static List<CatedraDtos.AppointmentItem> items(byte[] body) {
        try {
            CatedraDtos.AppointmentItem[] items = CatedraJson.MAPPER.readValue(body, CatedraDtos.AppointmentItem[].class);
            if (items == null) {
                throw new CatedraInvalidResponseException("Listado de reservas inválido: el cuerpo es null");
            }
            return List.of(items);
        } catch (JacksonException e) {
            // El detalle de Jackson nombra tipos de Java; el cuerpo nunca va al log.
            LOG.error("Listado de reservas ilegible: {}", e.getOriginalMessage());
            throw new CatedraInvalidResponseException("Listado de reservas inválido: el cuerpo no es el JSON esperado", e);
        } catch (NullPointerException e) {
            throw new CatedraInvalidResponseException("Listado de reservas inválido: un ítem es null", e);
        }
    }

    /** El ítem como reserva del dominio, o {@code null} si no se puede cruzar con un proceso (se ignora con WARN). */
    private static CatedraReservation toReservation(CatedraDtos.AppointmentItem item) {
        try {
            CatedraReservationStatus status = CatedraReservationStatus.from(item.status());
            String reservationProcessId = CatedraFormat.required(item.reservationProcessId(), "reservationProcessId");
            if (status == CatedraReservationStatus.UNRECOGNIZED) {
                LOG.warn("La reserva {} tiene un status que el contrato no lista: se conserva como desconocido", reservationProcessId);
            }
            return new CatedraReservation(
                reservationProcessId,
                item.externalPatientId(),
                item.reservationId(),
                CatedraFormat.date(item.appointmentDate(), "appointmentDate"),
                LocalTimeFormat.parse(item.startTime(), "startTime"),
                status,
                optionalInstant(item.confirmedAt(), "confirmedAt", reservationProcessId),
                optionalInstant(item.cancelledAt(), "cancelledAt", reservationProcessId),
                item.cancellationReason()
            );
        } catch (IllegalArgumentException e) {
            LOG.warn("Una reserva del listado no se puede leer ({}): se ignora", e.getMessage());
            return null;
        }
    }

    private static Instant optionalInstant(String value, String field, String reservationProcessId) {
        if (value == null) {
            return null;
        }
        try {
            return CatedraFormat.instant(value, field);
        } catch (IllegalArgumentException e) {
            LOG.warn("La reserva {} tiene {} ilegible: se toma como ausente", reservationProcessId, field);
            return null;
        }
    }
}
