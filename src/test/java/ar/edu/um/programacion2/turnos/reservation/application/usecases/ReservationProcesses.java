package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AppointmentSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Cancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Closing;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Confirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PatientName;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneNumber;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRejection;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRequest;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProcessOwner;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalSnapshot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Procesos de prueba llevados hasta un estado por el propio modelo (no armados a mano): así un test no puede partir de un
 * estado que el modelo nunca produciría. El hold es {@code hold-<n>} / {@code process-<n>}.
 */
final class ReservationProcesses {

    static final Instant ANTES = Instant.parse("2026-12-14T12:20:00Z");
    static final Instant VENCE = Instant.parse("2026-12-14T12:40:00Z");

    private ReservationProcesses() {}

    static ReservationProcessId id(int n) {
        return new ReservationProcessId(UUID.fromString("00000000-0000-4000-9000-" + "%012d".formatted(n)));
    }

    static String reservationProcessId(int n) {
        return "process-" + n;
    }

    static String holdId(int n) {
        return "hold-" + n;
    }

    /** Un proceso del usuario {@code userId}, profesional 202, martes 15/12/2026 a esa hora, en ese estado. */
    static ReservationProcess in(ReservationProcessStatus status, long userId, LocalTime startTime, int n) {
        ReservationProcess process = ReservationProcess.start(
            id(n),
            new ProcessOwner(userId, "login-" + userId),
            new AppointmentSlot(202, LocalDate.parse("2026-12-15"), startTime, startTime.plusMinutes(30)),
            new ProfessionalSnapshot("Ana", "Gomez", "Clínica", 30),
            new PatientName("Ana", "Pérez"),
            ANTES.minusSeconds(60)
        );
        CatedraHold hold = new CatedraHold(holdId(n), reservationProcessId(n), VENCE);
        PhoneRequest request = new PhoneRequest("request-" + n, "Ingrese un teléfono");
        Confirmation confirmation = new Confirmation(8251, ANTES);
        switch (status) {
            case CREATING_HOLD -> {}
            case HOLD_CREATED -> process.holdCreated(hold, ANTES);
            case WAITING_FOR_PHONE_REQUEST -> {
                process.holdCreated(hold, ANTES);
                process.confirmAccepted(VENCE, ANTES);
            }
            case PHONE_REQUESTED -> {
                process.holdCreated(hold, ANTES);
                process.phoneRequested(request, VENCE, ANTES);
            }
            case PHONE_SUBMITTED -> {
                process.holdCreated(hold, ANTES);
                process.phoneRequested(request, VENCE, ANTES);
                process.phoneSubmitted(PhoneNumber.of("2615551234"), "submitted-" + n, ANTES);
            }
            case PHONE_REJECTED -> {
                process.holdCreated(hold, ANTES);
                process.phoneRequested(request, VENCE, ANTES);
                process.phoneSubmitted(PhoneNumber.of("2615551234"), "submitted-" + n, ANTES);
                process.phoneRejected(new PhoneRejection("INVALID_PHONE_NUMBER", null), VENCE, ANTES);
            }
            case CONFIRMED -> {
                process.holdCreated(hold, ANTES);
                process.confirmed(confirmation, ANTES);
            }
            case CANCELLED -> {
                process.holdCreated(hold, ANTES);
                process.confirmed(confirmation, ANTES);
                process.cancelled(new Cancellation(null, ANTES), ANTES);
            }
            case EXPIRED -> {
                process.holdCreated(hold, ANTES);
                process.expired(new Closing("HOLD_EXPIRED", null), ANTES);
            }
            case INVALID -> {
                process.holdCreated(hold, ANTES);
                process.invalid(new Closing("REQUEST_EVENT_MISMATCH", null), ANTES);
            }
            // Con hold: un confirm rechazado (CU-04 11c). Un FAILED sin hold no lo encuentra ningún evento.
            case FAILED -> {
                process.holdCreated(hold, ANTES);
                process.failed(new Closing("SLOT_ALREADY_RESERVED", null), ANTES);
            }
        }
        if (process.getStatus() != status) {
            throw new IllegalStateException("No se llegó a " + status);
        }
        return process;
    }
}
