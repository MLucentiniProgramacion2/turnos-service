package ar.edu.um.programacion2.turnos.reservation.domain.model;

import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CANCELLED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CONFIRMED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CREATING_HOLD;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.EXPIRED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.FAILED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.INVALID;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REJECTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REQUESTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_SUBMITTED;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * La matriz de la máquina de estados (DPT-05) escrita a mano, como tabla, para que los tests la comparen con el modelo:
 * todos los estados por todas las operaciones. Es la "doble entrada" del {@code switch} de {@link ReservationProcess}.
 * <p>
 * Coincide con el diagrama de {@code docs/use-cases/README.md}, la tabla de CU-07 y la de CU-11, más las decisiones del
 * #10: los hechos que llegan por {@code reservationProcessId} quedan sin efecto en {@code CREATING_HOLD}, y mandar el
 * teléfono o confirmar sin pedido o sin hold es un error de programación.
 * <p>
 * La usan {@code ReservationProcessTest} (el modelo) y {@code ReservationProcessMatrixPersistenceIT} (que guarda en
 * PostgreSQL cada resultado aplicado, así un {@code CHECK} que contradiga al modelo se detecta).
 */
public final class ReservationProcessMatrix {

    public static final Instant CREADO = Instant.parse("2026-12-10T11:00:00Z");
    public static final Instant CAMBIADO = Instant.parse("2026-12-10T11:30:00Z");
    public static final Instant AHORA = Instant.parse("2026-12-10T12:00:00Z");
    public static final Instant VENCE = Instant.parse("2026-12-10T12:10:00Z");
    public static final Instant VENCE_NUEVO = Instant.parse("2026-12-10T12:12:00Z");

    public static final CatedraHold HOLD = new CatedraHold("hold-6c0c2f41", "process-a92b47d0", VENCE);
    public static final PhoneRequest PEDIDO = new PhoneRequest("request-event-8a31", "Ingrese un numero de telefono.");
    public static final PhoneRequest PEDIDO_NUEVO = new PhoneRequest("request-event-otro", null);
    public static final PhoneNumber TELEFONO = PhoneNumber.of("+5492615551234");
    public static final PhoneNumber TELEFONO_NUEVO = PhoneNumber.of("+5492615559999");
    public static final String EVENTO_ENVIADO = "phone-submitted-b91e";
    public static final String EVENTO_NUEVO = "phone-submitted-c02f";
    public static final PhoneRejection RECHAZO = new PhoneRejection("INVALID_PHONE_NUMBER", "El telefono no es valido.");
    public static final Confirmation CONFIRMACION = new Confirmation(8251, Instant.parse("2026-12-10T11:20:00Z"));
    public static final Confirmation CONFIRMACION_NUEVA = new Confirmation(8251, AHORA);
    public static final Cancellation CANCELACION = new Cancellation("Cambio solicitado por el usuario", AHORA);
    public static final Closing CIERRE = new Closing("HOLD_EXPIRED", null);
    public static final Closing CIERRE_NUEVO = new Closing("REQUEST_EVENT_MISMATCH", "otro");

    /** Las 11 operaciones del modelo, con los datos que se le pasan a cada una. */
    public enum Operacion {
        holdCreated((p, now) -> p.holdCreated(HOLD, now)),
        confirmAccepted((p, now) -> p.confirmAccepted(VENCE_NUEVO, now)),
        phoneRequested((p, now) -> p.phoneRequested(PEDIDO_NUEVO, VENCE_NUEVO, now)),
        phoneSubmitted((p, now) -> p.phoneSubmitted(TELEFONO_NUEVO, EVENTO_NUEVO, now)),
        phoneRejected((p, now) -> p.phoneRejected(RECHAZO, VENCE_NUEVO, now)),
        confirmed((p, now) -> p.confirmed(CONFIRMACION_NUEVA, now)),
        expired((p, now) -> p.expired(CIERRE_NUEVO, now)),
        invalid((p, now) -> p.invalid(CIERRE_NUEVO, now)),
        cancelled((p, now) -> p.cancelled(CANCELACION, now)),
        cancelledByReconciliation((p, now) -> p.cancelledByReconciliation(CANCELACION, now)),
        failed((p, now) -> p.failed(CIERRE_NUEVO, now));

        private final BiFunction<ReservationProcess, Instant, TransitionResult> accion;

        Operacion(BiFunction<ReservationProcess, Instant, TransitionResult> accion) {
            this.accion = accion;
        }

        public TransitionResult aplicar(ReservationProcess proceso) {
            return accion.apply(proceso, AHORA);
        }
    }

    /** Lo que tiene que pasar: un estado nuevo, sin efecto con un motivo, o una excepción (error de programación). */
    public sealed interface Esperado {
        record Aplica(ReservationProcessStatus destino) implements Esperado {}

        record SinEfecto(NoEffectReason motivo) implements Esperado {}

        record Excepcion() implements Esperado {}
    }

    public record Caso(ReservationProcessStatus estado, Operacion operacion, Esperado esperado) {
        @Override
        public String toString() {
            return estado + " + " + operacion + " -> " + esperado;
        }
    }

    // Abreviaturas: EX excepción; NF NOT_FORWARD; NA NOT_APPLICABLE; CO CONFIRMED_ONLY_CANCELS; FS FINAL_STATE.
    // Columnas: holdCreated confirmAccepted phoneRequested phoneSubmitted phoneRejected confirmed expired invalid
    //           cancelled cancelledByReconciliation failed
    // prettier-ignore
    private static final String TABLA = """
        CREATING_HOLD             HOLD_CREATED EX                        NA              EX              NA             NA        NA      NA      NA        NA        FAILED
        HOLD_CREATED              EX           WAITING_FOR_PHONE_REQUEST PHONE_REQUESTED EX              NA             CONFIRMED EXPIRED INVALID NA        CANCELLED FAILED
        WAITING_FOR_PHONE_REQUEST EX           NF                        PHONE_REQUESTED EX              NA             CONFIRMED EXPIRED INVALID NA        CANCELLED NA
        PHONE_REQUESTED           EX           NF                        NF              PHONE_SUBMITTED NA             CONFIRMED EXPIRED INVALID NA        CANCELLED NA
        PHONE_SUBMITTED           EX           NF                        NF              NF              PHONE_REJECTED CONFIRMED EXPIRED INVALID NA        CANCELLED NA
        PHONE_REJECTED            EX           NF                        NF              PHONE_SUBMITTED NF             CONFIRMED EXPIRED INVALID NA        CANCELLED NA
        CONFIRMED                 CO           CO                        CO              CO              CO             CO        CO      CO      CANCELLED CANCELLED CO
        CANCELLED                 FS           FS                        FS              FS              FS             FS        FS      FS      FS        FS        FS
        EXPIRED                   FS           FS                        FS              FS              FS             FS        FS      FS      FS        FS        FS
        INVALID                   FS           FS                        FS              FS              FS             FS        FS      FS      FS        FS        FS
        FAILED                    FS           FS                        FS              FS              FS             FS        FS      FS      FS        FS        FS
        """;

    private ReservationProcessMatrix() {}

    /** Los 121 casos, en el orden de la tabla. Falla si a la tabla le falta o le sobra un estado o una columna. */
    public static List<Caso> casos() {
        Map<ReservationProcessStatus, String[]> filas = new EnumMap<>(ReservationProcessStatus.class);
        for (String linea : TABLA.strip().split("\n")) {
            String[] celdas = linea.strip().split("\\s+");
            if (celdas.length != Operacion.values().length + 1) {
                throw new IllegalStateException("Fila con " + celdas.length + " celdas: " + linea);
            }
            filas.put(ReservationProcessStatus.valueOf(celdas[0]), Arrays.copyOfRange(celdas, 1, celdas.length));
        }
        if (filas.size() != ReservationProcessStatus.values().length) {
            throw new IllegalStateException("La tabla no tiene todos los estados: " + filas.keySet());
        }
        List<Caso> casos = new ArrayList<>();
        filas.forEach((estado, celdas) -> {
            for (Operacion operacion : Operacion.values()) {
                casos.add(new Caso(estado, operacion, esperado(celdas[operacion.ordinal()])));
            }
        });
        return casos;
    }

    private static Esperado esperado(String celda) {
        return switch (celda) {
            case "EX" -> new Esperado.Excepcion();
            case "NF" -> new Esperado.SinEfecto(NoEffectReason.NOT_FORWARD);
            case "NA" -> new Esperado.SinEfecto(NoEffectReason.NOT_APPLICABLE);
            case "CO" -> new Esperado.SinEfecto(NoEffectReason.CONFIRMED_ONLY_CANCELS);
            case "FS" -> new Esperado.SinEfecto(NoEffectReason.FINAL_STATE);
            default -> new Esperado.Aplica(ReservationProcessStatus.valueOf(celda));
        };
    }

    /**
     * Un proceso en ese estado, con los datos que exige el estado y nada más. {@code FAILED} viene de
     * {@code CREATING_HOLD} (sin hold); {@code CANCELLED} viene de {@code CONFIRMED}. La versión es {@code null}
     * (todavía no se guardó), así el IT lo puede insertar.
     */
    public static ReservationProcess procesoEn(ReservationProcessStatus estado, long userId) {
        boolean conHold = estado != CREATING_HOLD && estado != FAILED;
        boolean conPedido = estado == PHONE_REQUESTED || estado == PHONE_SUBMITTED || estado == PHONE_REJECTED;
        boolean conTelefono = estado == PHONE_SUBMITTED || estado == PHONE_REJECTED;
        return new ReservationProcess(
            new ReservationProcessId(UUID.fromString("00000000-0000-4000-8000-" + "%012d".formatted(estado.ordinal()))),
            null,
            new ProcessOwner(userId, "ana.perez"),
            new AppointmentSlot(202, LocalDate.parse("2026-12-14"), LocalTime.parse("09:00"), LocalTime.parse("09:30")),
            new ProfessionalSnapshot("Ana", "Gomez", "Clínica", 30),
            new PatientName("Juan", "Perez"),
            estado,
            CREADO,
            CAMBIADO,
            conHold ? HOLD : null,
            conPedido ? PEDIDO : null,
            conTelefono ? TELEFONO : null,
            conTelefono ? EVENTO_ENVIADO : null,
            estado == PHONE_REJECTED ? RECHAZO : null,
            estado == CONFIRMED || estado == CANCELLED ? CONFIRMACION : null,
            estado == CANCELLED ? CANCELACION : null,
            estado == EXPIRED || estado == INVALID || estado == FAILED ? CIERRE : null
        );
    }
}
