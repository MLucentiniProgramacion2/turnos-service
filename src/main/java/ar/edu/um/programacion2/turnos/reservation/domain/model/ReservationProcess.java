package ar.edu.um.programacion2.turnos.reservation.domain.model;

import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CANCELLED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CONFIRMED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CREATING_HOLD;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.EXPIRED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.FAILED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.HOLD_CREATED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.INVALID;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REJECTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REQUESTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_SUBMITTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST;

import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult.Applied;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult.NoEffect;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Proceso de reserva local, con su máquina de estados (DPT-05). El diagrama está en {@code docs/use-cases/README.md}.
 * <p>
 * Es una clase y no un record porque cambia en el tiempo, pero <strong>no tiene setters</strong>: cada cambio entra por un
 * método con el nombre del hecho ({@link #holdCreated}, {@link #phoneRequested}, {@link #confirmed}...), que aplica las
 * reglas y devuelve un {@link TransitionResult}. Las reglas, en orden:
 * <ol>
 * <li>Un estado final no cambia nunca: {@link NoEffectReason#FINAL_STATE}.</li>
 * <li>{@code CONFIRMED} solo sale a {@code CANCELLED}: cualquier otro hecho da
 * {@link NoEffectReason#CONFIRMED_ONLY_CANCELS}.</li>
 * <li>Solo se avanza. Un salto hacia adelante es válido (por ejemplo, {@code AppointmentConfirmed} en
 * {@code HOLD_CREATED}); repetir o retroceder da {@link NoEffectReason#NOT_FORWARD}; un hecho que no corresponde al
 * estado da {@link NoEffectReason#NOT_APPLICABLE}.</li>
 * </ol>
 * Un resultado sin efecto <strong>no es un error</strong>: con Kafka (entrega al menos una vez y orden distinto del de
 * REST) los duplicados y los eventos tardíos son normales. Las excepciones quedan para los errores de programación: un
 * argumento inválido, guardar el hold dos veces, confirmar sin hold o mandar el teléfono sin pedido.
 * <p>
 * <strong>CREATING_HOLD:</strong> los hechos que llegan por un {@code reservationProcessId} ({@link #phoneRequested},
 * {@link #confirmed}, {@link #expired}, {@link #invalid}, {@link #cancelledByReconciliation}) quedan sin efecto: en ese
 * estado el proceso todavía no tiene ese ID, así que ningún evento ni reconciliación lo puede encontrar (CU-07 2.2a), y
 * CU-11 solo lo pasa a {@code FAILED}. Así el modelo nunca produce un {@code EXPIRED} sin los datos del hold.
 * <p>
 * El modelo no lee el reloj: cada método recibe el instante ({@code now}) del {@code Clock} del caso de uso, como
 * {@code SyncStatus} del catálogo. {@code statusChangedAt} cambia solo cuando cambia el estado (DPT-24).
 * <p>
 * {@code version} es la del bloqueo optimista (DPT-09): la administra la persistencia, el modelo solo la conserva para que
 * una escritura basada en una lectura vieja falle. Es {@code null} en un proceso que todavía no se guardó.
 */
public class ReservationProcess {

    private static final Set<ReservationProcessStatus> WITHOUT_PHONE_REQUEST = EnumSet.of(
        CREATING_HOLD,
        HOLD_CREATED,
        WAITING_FOR_PHONE_REQUEST
    );

    private final ReservationProcessId id;
    private final Long version;
    private final ProcessOwner owner;
    private final AppointmentSlot slot;
    private final ProfessionalSnapshot professional;
    private final PatientName patient;
    private final Instant createdAt;

    private ReservationProcessStatus status;
    private Instant statusChangedAt;
    private CatedraHold hold;
    private PhoneRequest phoneRequest;
    private PhoneNumber phone;
    private String lastSubmittedEventId;
    private PhoneRejection rejection;
    private Confirmation confirmation;
    private Cancellation cancellation;
    private Closing closing;

    /**
     * Reconstruye un proceso con todos sus datos (lo usa la persistencia). Valida que los datos correspondan al estado: los
     * mismos invariantes que los {@code CHECK} de la tabla, y alguno más.
     */
    public ReservationProcess(
        ReservationProcessId id,
        Long version,
        ProcessOwner owner,
        AppointmentSlot slot,
        ProfessionalSnapshot professional,
        PatientName patient,
        ReservationProcessStatus status,
        Instant createdAt,
        Instant statusChangedAt,
        CatedraHold hold,
        PhoneRequest phoneRequest,
        PhoneNumber phone,
        String lastSubmittedEventId,
        PhoneRejection rejection,
        Confirmation confirmation,
        Cancellation cancellation,
        Closing closing
    ) {
        this.id = Objects.requireNonNull(id, "Falta el identificador");
        if (version != null && version < 0) {
            throw new IllegalArgumentException("La versión no puede ser negativa");
        }
        this.version = version;
        this.owner = Objects.requireNonNull(owner, "Falta el dueño");
        this.slot = Objects.requireNonNull(slot, "Falta el turno");
        this.professional = Objects.requireNonNull(professional, "Faltan los datos del profesional");
        this.patient = Objects.requireNonNull(patient, "Falta el nombre del paciente");
        this.status = Objects.requireNonNull(status, "Falta el estado");
        this.createdAt = Objects.requireNonNull(createdAt, "Falta el instante de creación");
        this.statusChangedAt = Objects.requireNonNull(statusChangedAt, "Falta el instante del último cambio de estado");
        this.hold = hold;
        this.phoneRequest = phoneRequest;
        this.phone = phone;
        this.lastSubmittedEventId = Texts.optionalText(lastSubmittedEventId, "el último eventId enviado");
        this.rejection = rejection;
        this.confirmation = confirmation;
        this.cancellation = cancellation;
        this.closing = closing;
        requireDataMatchesStatus();
    }

    /** Un proceso nuevo, en {@code CREATING_HOLD}, que CU-04 guarda antes de pedir el hold (DPT-03). */
    public static ReservationProcess start(
        ReservationProcessId id,
        ProcessOwner owner,
        AppointmentSlot slot,
        ProfessionalSnapshot professional,
        PatientName patient,
        Instant now
    ) {
        requireNow(now);
        return new ReservationProcess(
            id,
            null,
            owner,
            slot,
            professional,
            patient,
            CREATING_HOLD,
            now,
            now,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    private void requireDataMatchesStatus() {
        if (status == CREATING_HOLD && hold != null) {
            throw new IllegalArgumentException("Un proceso en CREATING_HOLD todavía no tiene hold");
        }
        if (status != CREATING_HOLD && status != FAILED && hold == null) {
            throw new IllegalArgumentException("Un proceso en " + status + " tiene que tener los datos del hold");
        }
        if (EnumSet.of(PHONE_REQUESTED, PHONE_SUBMITTED, PHONE_REJECTED).contains(status) && phoneRequest == null) {
            throw new IllegalArgumentException("Un proceso en " + status + " tiene que tener el pedido de teléfono");
        }
        if ((status == PHONE_SUBMITTED || status == PHONE_REJECTED) && phone == null) {
            throw new IllegalArgumentException("Un proceso en " + status + " tiene que tener el teléfono enviado");
        }
        if (phone != null && lastSubmittedEventId == null) {
            throw new IllegalArgumentException("Un teléfono enviado tiene que tener el eventId con que se envió");
        }
        if (status == PHONE_REJECTED && rejection == null) {
            throw new IllegalArgumentException("Un proceso en PHONE_REJECTED tiene que tener el motivo del rechazo");
        }
        if (status == CONFIRMED && confirmation == null) {
            throw new IllegalArgumentException("Un proceso en CONFIRMED tiene que tener los datos de la confirmación");
        }
        if (status == CANCELLED && cancellation == null) {
            throw new IllegalArgumentException("Un proceso en CANCELLED tiene que tener los datos de la cancelación");
        }
        if ((status == EXPIRED || status == INVALID || status == FAILED) && closing == null) {
            throw new IllegalArgumentException("Un proceso en " + status + " tiene que tener el motivo del cierre");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Transiciones
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code 201} del hold (CU-04 paso 10): guarda sus datos antes de pedir el confirm. */
    public TransitionResult holdCreated(CatedraHold newHold, Instant now) {
        Objects.requireNonNull(newHold, "Faltan los datos del hold");
        requireNow(now);
        Optional<NoEffect> absorbed = absorbed();
        if (absorbed.isPresent()) {
            return absorbed.orElseThrow();
        }
        if (status != CREATING_HOLD) {
            // El POST del hold nunca se repite (DPT-06): un segundo hold para el mismo proceso es un error de programación.
            throw new IllegalStateException("El proceso " + id + " ya tiene el hold " + hold.holdId());
        }
        hold = newHold;
        return moveTo(HOLD_CREATED, now);
    }

    /**
     * El confirm se aplicó: {@code 202}, {@code 409 RESERVATION_PROCESS_ALREADY_CONFIRMED} o, en CU-11, la cátedra lo
     * tiene en {@code PHONE_PENDING}.
     *
     * @param expiresAt el vencimiento que informó la cátedra, o {@code null} si no informó uno (se conserva el actual)
     */
    public TransitionResult confirmAccepted(Instant expiresAt, Instant now) {
        requireNow(now);
        Optional<NoEffect> absorbed = absorbed();
        if (absorbed.isPresent()) {
            return absorbed.orElseThrow();
        }
        if (status == CREATING_HOLD) {
            throw new IllegalStateException("El proceso " + id + " no tiene hold que confirmar");
        }
        if (status != HOLD_CREATED) {
            // El pedido de teléfono llegó antes que el 202 (CU-04 13a): sería retroceder.
            return noEffect(NoEffectReason.NOT_FORWARD);
        }
        hold = hold.withExpiresAt(expiresAt);
        return moveTo(WAITING_FOR_PHONE_REQUEST, now);
    }

    /** {@code AdditionalInformationRequested} (CU-05). Acepta {@code HOLD_CREATED}: el evento puede llegar antes que el 202. */
    public TransitionResult phoneRequested(PhoneRequest request, Instant expiresAt, Instant now) {
        Objects.requireNonNull(request, "Falta el pedido de teléfono");
        Objects.requireNonNull(expiresAt, "Falta el vencimiento informado en el pedido");
        requireNow(now);
        Optional<NoEffect> absorbed = absorbed();
        if (absorbed.isPresent()) {
            return absorbed.orElseThrow();
        }
        return switch (status) {
            case CREATING_HOLD -> noEffect(NoEffectReason.NOT_APPLICABLE);
            case HOLD_CREATED, WAITING_FOR_PHONE_REQUEST -> {
                phoneRequest = request;
                hold = hold.withExpiresAt(expiresAt);
                yield moveTo(PHONE_REQUESTED, now);
            }
            // Otro pedido para un proceso que ya lo tenía (CU-05 3.3c).
            default -> noEffect(NoEffectReason.NOT_FORWARD);
        };
    }

    /**
     * El teléfono se publicó en Kafka (CU-06 paso 7). El {@code eventId} enviado se guarda <strong>siempre</strong>,
     * aunque el estado no cambie: es un hecho (el evento ya está en la cátedra) y CU-06 7a pide guardarlo también si el
     * resultado llegó antes. Es la única escritura sobre un proceso {@code CONFIRMED} o final. El teléfono se guarda solo
     * si el estado cambia.
     */
    public TransitionResult phoneSubmitted(PhoneNumber newPhone, String eventId, Instant now) {
        Objects.requireNonNull(newPhone, "Falta el teléfono");
        Texts.requireText(eventId, "el eventId enviado");
        requireNow(now);
        if (WITHOUT_PHONE_REQUEST.contains(status)) {
            // CU-06 solo publica en PHONE_REQUESTED o PHONE_REJECTED, y desde ahí el proceso solo avanza.
            throw new IllegalStateException("El proceso " + id + " no tiene pedido de teléfono (" + status + ")");
        }
        lastSubmittedEventId = eventId;
        Optional<NoEffect> absorbed = absorbed();
        if (absorbed.isPresent()) {
            return absorbed.orElseThrow();
        }
        if (status == PHONE_SUBMITTED) {
            return noEffect(NoEffectReason.NOT_FORWARD);
        }
        phone = newPhone;
        return moveTo(PHONE_SUBMITTED, now);
    }

    /** {@code AdditionalInformationRejected} (CU-07): solo desde {@code PHONE_SUBMITTED}. No es final. */
    public TransitionResult phoneRejected(PhoneRejection newRejection, Instant expiresAt, Instant now) {
        Objects.requireNonNull(newRejection, "Falta el motivo del rechazo");
        requireNow(now);
        Optional<NoEffect> absorbed = absorbed();
        if (absorbed.isPresent()) {
            return absorbed.orElseThrow();
        }
        return switch (status) {
            case PHONE_SUBMITTED -> {
                rejection = newRejection;
                hold = hold.withExpiresAt(expiresAt);
                yield moveTo(PHONE_REJECTED, now);
            }
            case PHONE_REJECTED -> noEffect(NoEffectReason.NOT_FORWARD);
            // Rechazo de un envío que no llegamos a guardar (CU-07 2.3c): el usuario puede volver a enviar.
            default -> noEffect(NoEffectReason.NOT_APPLICABLE);
        };
    }

    /** {@code AppointmentConfirmed} o reconciliación: desde cualquier intermedio con hold. */
    public TransitionResult confirmed(Confirmation newConfirmation, Instant now) {
        Objects.requireNonNull(newConfirmation, "Faltan los datos de la confirmación");
        return closeFromIntermediate(CONFIRMED, now, () -> confirmation = newConfirmation);
    }

    /** {@code AppointmentProcessExpired}, {@code 404 HOLD_EXPIRED} o reconciliación (contrato §18.3). */
    public TransitionResult expired(Closing reason, Instant now) {
        Objects.requireNonNull(reason, "Falta el motivo del vencimiento");
        return closeFromIntermediate(EXPIRED, now, () -> closing = reason);
    }

    /** {@code AppointmentProcessInvalid} (contrato §15.9). */
    public TransitionResult invalid(Closing reason, Instant now) {
        Objects.requireNonNull(reason, "Falta el motivo de la invalidez");
        return closeFromIntermediate(INVALID, now, () -> closing = reason);
    }

    /**
     * {@code AppointmentCancelled} o CU-10: solo desde {@code CONFIRMED}. En un intermedio no debería pasar (los eventos
     * de un proceso llegan en orden, CU-07 2.3e) y queda sin efecto: lo corrige la reconciliación.
     */
    public TransitionResult cancelled(Cancellation newCancellation, Instant now) {
        Objects.requireNonNull(newCancellation, "Faltan los datos de la cancelación");
        requireNow(now);
        if (status.isFinal()) {
            return noEffect(NoEffectReason.FINAL_STATE);
        }
        if (status != CONFIRMED) {
            return noEffect(NoEffectReason.NOT_APPLICABLE);
        }
        cancellation = newCancellation;
        return moveTo(CANCELLED, now);
    }

    /**
     * La cátedra informa la reserva como {@code CANCELLED} al reconciliar (CU-09, CU-11): es el único camino de un
     * intermedio a {@code CANCELLED}. También vale desde {@code CONFIRMED}.
     */
    public TransitionResult cancelledByReconciliation(Cancellation newCancellation, Instant now) {
        Objects.requireNonNull(newCancellation, "Faltan los datos de la cancelación");
        requireNow(now);
        if (status.isFinal()) {
            return noEffect(NoEffectReason.FINAL_STATE);
        }
        if (status == CREATING_HOLD) {
            return noEffect(NoEffectReason.NOT_APPLICABLE);
        }
        cancellation = newCancellation;
        return moveTo(CANCELLED, now);
    }

    /** Rechazo o resultado desconocido al crear el hold, o rechazo al confirmarlo (DPT-06): solo antes del confirm. */
    public TransitionResult failed(Closing reason, Instant now) {
        Objects.requireNonNull(reason, "Falta el motivo de la falla");
        requireNow(now);
        Optional<NoEffect> absorbed = absorbed();
        if (absorbed.isPresent()) {
            return absorbed.orElseThrow();
        }
        if (status != CREATING_HOLD && status != HOLD_CREATED) {
            return noEffect(NoEffectReason.NOT_APPLICABLE);
        }
        closing = reason;
        return moveTo(FAILED, now);
    }

    private TransitionResult closeFromIntermediate(ReservationProcessStatus target, Instant now, Runnable saveData) {
        requireNow(now);
        Optional<NoEffect> absorbed = absorbed();
        if (absorbed.isPresent()) {
            return absorbed.orElseThrow();
        }
        if (status == CREATING_HOLD) {
            return noEffect(NoEffectReason.NOT_APPLICABLE);
        }
        saveData.run();
        return moveTo(target, now);
    }

    /** Reglas 1 y 2 de DPT-05, que valen para todos los hechos salvo la cancelación. */
    private Optional<NoEffect> absorbed() {
        if (status.isFinal()) {
            return Optional.of(new NoEffect(status, NoEffectReason.FINAL_STATE));
        }
        if (status == CONFIRMED) {
            return Optional.of(new NoEffect(status, NoEffectReason.CONFIRMED_ONLY_CANCELS));
        }
        return Optional.empty();
    }

    private NoEffect noEffect(NoEffectReason reason) {
        return new NoEffect(status, reason);
    }

    private Applied moveTo(ReservationProcessStatus target, Instant now) {
        ReservationProcessStatus from = status;
        status = target;
        statusChangedAt = now;
        return new Applied(from, target);
    }

    private static void requireNow(Instant now) {
        Objects.requireNonNull(now, "Falta el instante actual");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Lectura
    // ---------------------------------------------------------------------------------------------------------------

    public ReservationProcessId getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public ProcessOwner getOwner() {
        return owner;
    }

    public AppointmentSlot getSlot() {
        return slot;
    }

    public ProfessionalSnapshot getProfessional() {
        return professional;
    }

    public PatientName getPatient() {
        return patient;
    }

    public ReservationProcessStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public Optional<CatedraHold> getHold() {
        return Optional.ofNullable(hold);
    }

    public Optional<PhoneRequest> getPhoneRequest() {
        return Optional.ofNullable(phoneRequest);
    }

    public Optional<PhoneNumber> getPhone() {
        return Optional.ofNullable(phone);
    }

    public Optional<String> getLastSubmittedEventId() {
        return Optional.ofNullable(lastSubmittedEventId);
    }

    public Optional<PhoneRejection> getRejection() {
        return Optional.ofNullable(rejection);
    }

    public Optional<Confirmation> getConfirmation() {
        return Optional.ofNullable(confirmation);
    }

    public Optional<Cancellation> getCancellation() {
        return Optional.ofNullable(cancellation);
    }

    public Optional<Closing> getClosing() {
        return Optional.ofNullable(closing);
    }

    /** Sin datos personales: ni el teléfono ni los nombres del paciente. */
    @Override
    public String toString() {
        return "ReservationProcess{id=" + id + ", status=" + status + ", userId=" + owner.userId() + ", slot=" + slot + "}";
    }
}
