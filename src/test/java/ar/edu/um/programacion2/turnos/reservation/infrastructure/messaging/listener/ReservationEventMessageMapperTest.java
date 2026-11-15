package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Lectura de los mensajes del topic de acciones. Los JSON salen de los ejemplos del contrato (§15.4 a §15.10). */
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
                ),
                List.of()
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

    @Test
    void unTipoQueElContratoNoDefineEsDesconocido() {
        assertThat(ReservationEventMessageMapper.parse("{\"eventId\":\"e-1\",\"eventType\":\"CatalogUpdated\"}")).isEqualTo(new ParsedEvent.UnknownType("e-1"));
        assertThat(ReservationEventMessageMapper.parse("{\"eventId\":\"e-1\",\"eventType\":\"additionalinformationrequested\"}")).isEqualTo(new ParsedEvent.UnknownType("e-1"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CU-07: los cinco resultados, con los ejemplos del contrato
    // ---------------------------------------------------------------------------------------------------------------

    static final String CONFIRMADO = """
        {"eventId":"confirmation-event-7d10","eventType":"AppointmentConfirmed","groupId":"proyecto-juan-perez",
         "reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41","requestEventId":"request-event-8a31",
         "reservationId":8251,"professionalId":15,"date":"2026-09-14","startTime":"09:30","status":"CONFIRMED",
         "occurredAt":"2026-09-10T14:36:01Z","schemaVersion":1}
        """;
    static final String RECHAZADO = """
        {"eventId":"rejection-event-7d11","eventType":"AdditionalInformationRejected","groupId":"proyecto-juan-perez",
         "reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41","requestEventId":"request-event-8a31",
         "reason":"INVALID_PHONE_NUMBER","message":"El telefono ingresado no es valido.","expiresAt":"2026-09-10T14:44:50Z",
         "occurredAt":"2026-09-10T14:36:01Z","schemaVersion":1}
        """;
    static final String VENCIDO = """
        {"eventId":"process-event-7d12","eventType":"AppointmentProcessExpired","groupId":"proyecto-juan-perez",
         "reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41","requestEventId":"request-event-8a31",
         "reason":"HOLD_EXPIRED","occurredAt":"2026-09-10T14:44:52Z","schemaVersion":1}
        """;
    static final String INVALIDO = """
        {"eventId":"process-event-7d13","eventType":"AppointmentProcessInvalid","groupId":"proyecto-juan-perez",
         "reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41","requestEventId":"request-event-8a31",
         "reason":"REQUEST_EVENT_MISMATCH","occurredAt":"2026-09-10T14:36:01Z","schemaVersion":1}
        """;
    static final String CANCELADO = """
        {"eventId":"cancellation-event-7d14","eventType":"AppointmentCancelled","groupId":"proyecto-juan-perez",
         "reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41","requestEventId":"request-event-8a31",
         "reservationId":8251,"professionalId":15,"date":"2026-09-14","startTime":"09:30","status":"CANCELLED",
         "reason":"Cambio solicitado por el usuario","occurredAt":"2026-09-11T15:10:00Z","schemaVersion":1}
        """;

    @Test
    void losCincoResultadosDelContratoSeLeenEnterosSinDegradaciones() {
        assertThat(ReservationEventMessageMapper.parse(CONFIRMADO)).isEqualTo(
            evento(new ReservationEvent.AppointmentConfirmed("confirmation-event-7d10", "process-a92b47d0", "hold-6c0c2f41", "request-event-8a31", 8251, Instant.parse("2026-09-10T14:36:01Z")))
        );
        assertThat(ReservationEventMessageMapper.parse(RECHAZADO)).isEqualTo(
            evento(
                new ReservationEvent.PhoneRejected(
                    "rejection-event-7d11",
                    "process-a92b47d0",
                    "hold-6c0c2f41",
                    "request-event-8a31",
                    "INVALID_PHONE_NUMBER",
                    "El telefono ingresado no es valido.",
                    Instant.parse("2026-09-10T14:44:50Z")
                )
            )
        );
        assertThat(ReservationEventMessageMapper.parse(VENCIDO)).isEqualTo(
            evento(new ReservationEvent.ProcessExpired("process-event-7d12", "process-a92b47d0", "hold-6c0c2f41", "request-event-8a31", "HOLD_EXPIRED"))
        );
        assertThat(ReservationEventMessageMapper.parse(INVALIDO)).isEqualTo(
            evento(new ReservationEvent.ProcessInvalid("process-event-7d13", "process-a92b47d0", "hold-6c0c2f41", "request-event-8a31", "REQUEST_EVENT_MISMATCH"))
        );
        assertThat(ReservationEventMessageMapper.parse(CANCELADO)).isEqualTo(
            evento(
                new ReservationEvent.AppointmentCancelled(
                    "cancellation-event-7d14",
                    "process-a92b47d0",
                    "hold-6c0c2f41",
                    "request-event-8a31",
                    "Cambio solicitado por el usuario",
                    Instant.parse("2026-09-11T15:10:00Z")
                )
            )
        );
    }

    @Test
    void unaCancelacionConReasonNullSeLeeSinDegradar() {
        assertThat(ReservationEventMessageMapper.parse(CANCELADO.replace("\"Cambio solicitado por el usuario\"", "null")))
            .isInstanceOfSatisfying(ParsedEvent.Event.class, leido -> {
                assertThat(((ReservationEvent.AppointmentCancelled) leido.event()).reason()).isNull();
                assertThat(leido.degradations()).isEmpty();
            });
    }

    static Stream<Arguments> degradaciones() {
        return Stream.of(
            Arguments.of("confirmación sin occurredAt", CONFIRMADO.replace("\"occurredAt\":\"2026-09-10T14:36:01Z\",", ""), "occurredAt ausente o ilegible: se usa el reloj"),
            Arguments.of("confirmación con occurredAt ilegible", CONFIRMADO.replace("2026-09-10T14:36:01Z", "ayer"), "occurredAt ausente o ilegible: se usa el reloj"),
            Arguments.of("cancelación sin occurredAt", CANCELADO.replace("\"occurredAt\":\"2026-09-11T15:10:00Z\",", ""), "occurredAt ausente o ilegible: se usa el reloj"),
            Arguments.of("rechazo sin reason", RECHAZADO.replace("\"reason\":\"INVALID_PHONE_NUMBER\",", ""), "reason ausente: se guarda UNSPECIFIED"),
            Arguments.of("rechazo con reason en blanco", RECHAZADO.replace("\"INVALID_PHONE_NUMBER\"", "\" \""), "reason ausente: se guarda UNSPECIFIED"),
            Arguments.of("vencido sin reason", VENCIDO.replace("\"reason\":\"HOLD_EXPIRED\",", ""), "reason ausente: se guarda UNSPECIFIED"),
            Arguments.of("inválido sin reason", INVALIDO.replace("\"reason\":\"REQUEST_EVENT_MISMATCH\",", ""), "reason ausente: se guarda UNSPECIFIED"),
            Arguments.of("rechazo sin expiresAt", RECHAZADO.replace("\"expiresAt\":\"2026-09-10T14:44:50Z\",", ""), "expiresAt ausente o ilegible: se conserva el vencimiento guardado"),
            Arguments.of("rechazo con expiresAt ilegible", RECHAZADO.replace("2026-09-10T14:44:50Z", "pronto"), "expiresAt ausente o ilegible: se conserva el vencimiento guardado"),
            Arguments.of("cancelación con reason de 501", CANCELADO.replace("Cambio solicitado por el usuario", "x".repeat(501)), "reason de 501 caracteres: se recorta a 500")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("degradaciones")
    void unDatoQueNoHaceFaltaSeDegradaYElEventoSeAplicaIgual(String caso, String mensaje, String degradacion) {
        ParsedEvent leido = ReservationEventMessageMapper.parse(mensaje);

        assertThat(leido).isInstanceOfSatisfying(ParsedEvent.Event.class, evento -> assertThat(evento.degradations()).containsExactly(degradacion));
    }

    @Test
    void lasDegradacionesDejanLosValoresDeReemplazo() {
        ReservationEvent.PhoneRejected rechazo = (ReservationEvent.PhoneRejected) ((ParsedEvent.Event) ReservationEventMessageMapper.parse(
            RECHAZADO.replace("\"reason\":\"INVALID_PHONE_NUMBER\",", "").replace("\"expiresAt\":\"2026-09-10T14:44:50Z\",", "")
        )).event();
        assertThat(rechazo.reason()).isEqualTo("UNSPECIFIED");
        assertThat(rechazo.expiresAt()).isNull();

        ReservationEvent.AppointmentCancelled cancelacion = (ReservationEvent.AppointmentCancelled) ((ParsedEvent.Event) ReservationEventMessageMapper.parse(
            CANCELADO.replace("Cambio solicitado por el usuario", "x".repeat(501))
        )).event();
        assertThat(cancelacion.reason()).hasSize(500);
    }

    static Stream<Arguments> resultadosMalFormados() {
        return Stream.of(
            Arguments.of("confirmación sin reservationId", CONFIRMADO.replace("\"reservationId\":8251,", ""), "falta reservationId o no es positivo"),
            Arguments.of("confirmación con reservationId 0", CONFIRMADO.replace("8251", "0"), "falta reservationId o no es positivo"),
            Arguments.of("confirmación con reservationId decimal", CONFIRMADO.replace("8251", "8251.5"), "no es el JSON esperado"),
            Arguments.of("cancelación sin reservationProcessId", CANCELADO.replace("\"reservationProcessId\":\"process-a92b47d0\",", ""), "falta reservationProcessId"),
            Arguments.of("vencido sin eventId", VENCIDO.replace("\"eventId\":\"process-event-7d12\",", ""), "falta eventId")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resultadosMalFormados")
    void sinUnDatoQueExigeLaTransicionElResultadoEsMalFormado(String caso, String mensaje, String detalle) {
        assertThat(ReservationEventMessageMapper.parse(mensaje)).isInstanceOfSatisfying(ParsedEvent.Malformed.class, malo -> assertThat(malo.detail()).isEqualTo(detalle));
    }

    private static ParsedEvent evento(ReservationEvent evento) {
        return new ParsedEvent.Event(evento, List.of());
    }
}
