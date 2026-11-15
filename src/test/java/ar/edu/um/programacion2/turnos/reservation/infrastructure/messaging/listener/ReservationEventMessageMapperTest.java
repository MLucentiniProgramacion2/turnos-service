package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Lectura de los mensajes del topic de acciones. Los JSON salen de los ejemplos del contrato (§15.4). */
class ReservationEventMessageMapperTest {

    /** El ejemplo de §15.4, con un campo nuevo que el contrato permite agregar. */
    static final String PEDIDO = """
        {"eventId":"request-event-8a31","eventType":"AdditionalInformationRequested","reservationProcessId":"process-a92b47d0",
         "holdId":"hold-6c0c2f41","requiredField":"PHONE_NUMBER","message":"Ingrese un numero de telefono para continuar.",
         "expiresAt":"2026-09-10T14:44:50Z","occurredAt":"2026-09-10T14:35:10Z","schemaVersion":1,"campoNuevo":{"x":1}}
        """;

    @Test
    void elPedidoDeTelefonoDelContratoSeLeeEntero() {
        assertThat(ReservationEventMessageMapper.parse(PEDIDO)).isEqualTo(
            new ParsedEvent.Event(
                new ReservationEvent.PhoneRequested(
                    "request-event-8a31",
                    "process-a92b47d0",
                    "hold-6c0c2f41",
                    "Ingrese un numero de telefono para continuar.",
                    Instant.parse("2026-09-10T14:44:50Z")
                )
            )
        );
    }

    @Test
    void sinMensajeOConElMensajeEnBlancoSeLeeIgual() {
        assertThat(ReservationEventMessageMapper.parse(PEDIDO.replace("\"Ingrese un numero de telefono para continuar.\"", "\"  \"")))
            .isInstanceOfSatisfying(ParsedEvent.Event.class, evento -> assertThat(((ReservationEvent.PhoneRequested) evento.event()).message()).isNull());
        assertThat(ReservationEventMessageMapper.parse(PEDIDO.replace("\"message\":", "\"otro\":"))).isInstanceOf(ParsedEvent.Event.class);
    }

    static Stream<Arguments> malFormados() {
        return Stream.of(
            Arguments.of("sin eventId", PEDIDO.replace("\"eventId\":\"request-event-8a31\",", ""), null, "falta eventId"),
            Arguments.of("eventId en blanco", PEDIDO.replace("\"request-event-8a31\"", "\" \""), null, "falta eventId"),
            Arguments.of("sin eventType", PEDIDO.replace("\"eventType\":\"AdditionalInformationRequested\",", ""), "request-event-8a31", "falta eventType"),
            Arguments.of("sin reservationProcessId", PEDIDO.replace("\"reservationProcessId\":\"process-a92b47d0\",", ""), "request-event-8a31", "falta reservationProcessId"),
            Arguments.of("sin holdId", PEDIDO.replace("\"holdId\":\"hold-6c0c2f41\",", ""), "request-event-8a31", "falta holdId"),
            Arguments.of("sin expiresAt", PEDIDO.replace("\"expiresAt\":\"2026-09-10T14:44:50Z\",", ""), "request-event-8a31", "falta expiresAt o no es un instante ISO-8601"),
            Arguments.of("expiresAt ilegible", PEDIDO.replace("2026-09-10T14:44:50Z", "mañana"), "request-event-8a31", "falta expiresAt o no es un instante ISO-8601"),
            Arguments.of("eventId que no es texto", PEDIDO.replace("\"request-event-8a31\"", "{\"a\":1}"), null, "no es el JSON esperado"),
            Arguments.of("JSON roto", "{\"eventId\": rotoInventado", null, "no es el JSON esperado"),
            Arguments.of("el JSON null", "null", null, "no es el JSON esperado"),
            Arguments.of("un array", "[]", null, "no es el JSON esperado"),
            Arguments.of("vacío", "", null, "mensaje vacío"),
            Arguments.of("tombstone", null, null, "mensaje vacío")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malFormados")
    void unMensajeSinUnCampoObligatorioEsMalFormadoYNoRepiteElValor(String caso, String mensaje, String eventId, String detalle) {
        assertThat(ReservationEventMessageMapper.parse(mensaje)).isEqualTo(new ParsedEvent.Malformed(eventId, detalle));
    }

    @ParameterizedTest
    @ValueSource(strings = { "AppointmentConfirmed", "AdditionalInformationRejected", "AppointmentProcessExpired", "AppointmentProcessInvalid", "AppointmentCancelled" })
    void losCincoResultadosTodaviaNoSeProcesan(String tipo) {
        assertThat(ReservationEventMessageMapper.parse("{\"eventId\":\"e-1\",\"eventType\":\"" + tipo + "\"}")).isEqualTo(new ParsedEvent.NotYetSupported(tipo, "e-1"));
    }

    @Test
    void unTipoQueElContratoNoDefineEsDesconocido() {
        assertThat(ReservationEventMessageMapper.parse("{\"eventId\":\"e-1\",\"eventType\":\"CatalogUpdated\"}")).isEqualTo(new ParsedEvent.UnknownType("e-1"));
        assertThat(ReservationEventMessageMapper.parse("{\"eventId\":\"e-1\",\"eventType\":\"additionalinformationrequested\"}")).isEqualTo(new ParsedEvent.UnknownType("e-1"));
    }
}
