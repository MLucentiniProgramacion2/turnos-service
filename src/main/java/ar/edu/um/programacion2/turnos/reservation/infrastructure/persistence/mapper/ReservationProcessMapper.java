package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper;

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
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import org.springframework.stereotype.Component;

/**
 * Traduce entre la fila de {@code reservation_process} y {@link ReservationProcess}.
 * <p>
 * El modelo no tiene setters: se reconstruye con su constructor, que valida que los datos correspondan al estado. Si la
 * base tuviera una fila incoherente, la lectura falla en vez de inventar un proceso. Los grupos de columnas (hold,
 * confirmación, cancelación...) son un record del modelo cada uno: todas sus columnas en {@code null} es "sin dato".
 */
@Component
public class ReservationProcessMapper {

    public ReservationProcessEntity toEntity(ReservationProcess process) {
        ReservationProcessEntity entity = new ReservationProcessEntity();
        entity.setId(process.getId().value());
        entity.setVersion(process.getVersion());
        entity.setUserId(process.getOwner().userId());
        entity.setUserLogin(process.getOwner().login());
        entity.setProfessionalId(process.getSlot().professionalId());
        entity.setAppointmentDate(process.getSlot().date());
        entity.setStartTime(process.getSlot().startTime());
        entity.setEndTime(process.getSlot().endTime());
        entity.setProfessionalFirstName(process.getProfessional().firstName());
        entity.setProfessionalLastName(process.getProfessional().lastName());
        entity.setProfessionalCategoryName(process.getProfessional().categoryName());
        entity.setSlotDurationMinutes(process.getProfessional().slotDurationMinutes());
        entity.setPatientFirstName(process.getPatient().firstName());
        entity.setPatientLastName(process.getPatient().lastName());
        entity.setStatus(process.getStatus());
        entity.setCreatedAt(process.getCreatedAt());
        entity.setStatusChangedAt(process.getStatusChangedAt());
        process
            .getHold()
            .ifPresent(hold -> {
                entity.setHoldId(hold.holdId());
                entity.setReservationProcessId(hold.reservationProcessId());
                entity.setExpiresAt(hold.expiresAt());
            });
        process
            .getPhoneRequest()
            .ifPresent(request -> {
                entity.setRequestEventId(request.requestEventId());
                entity.setPhoneRequestMessage(request.message());
            });
        process.getPhone().ifPresent(phone -> entity.setPhoneNumber(phone.value()));
        entity.setLastSubmittedEventId(process.getLastSubmittedEventId().orElse(null));
        process
            .getRejection()
            .ifPresent(rejection -> {
                entity.setRejectionReason(rejection.reason());
                entity.setRejectionMessage(rejection.message());
            });
        process
            .getConfirmation()
            .ifPresent(confirmation -> {
                entity.setReservationId(confirmation.reservationId());
                entity.setConfirmedAt(confirmation.confirmedAt());
            });
        process
            .getCancellation()
            .ifPresent(cancellation -> {
                entity.setCancellationReason(cancellation.reason());
                entity.setCancelledAt(cancellation.cancelledAt());
            });
        process
            .getClosing()
            .ifPresent(closing -> {
                entity.setClosingReason(closing.reason());
                entity.setClosingMessage(closing.message());
            });
        return entity;
    }

    public ReservationProcess toDomain(ReservationProcessEntity entity) {
        return new ReservationProcess(
            new ReservationProcessId(entity.getId()),
            entity.getVersion(),
            new ProcessOwner(entity.getUserId(), entity.getUserLogin()),
            new AppointmentSlot(entity.getProfessionalId(), entity.getAppointmentDate(), entity.getStartTime(), entity.getEndTime()),
            new ProfessionalSnapshot(
                entity.getProfessionalFirstName(),
                entity.getProfessionalLastName(),
                entity.getProfessionalCategoryName(),
                entity.getSlotDurationMinutes()
            ),
            new PatientName(entity.getPatientFirstName(), entity.getPatientLastName()),
            entity.getStatus(),
            entity.getCreatedAt(),
            entity.getStatusChangedAt(),
            entity.getHoldId() == null ? null : new CatedraHold(entity.getHoldId(), entity.getReservationProcessId(), entity.getExpiresAt()),
            entity.getRequestEventId() == null ? null : new PhoneRequest(entity.getRequestEventId(), entity.getPhoneRequestMessage()),
            entity.getPhoneNumber() == null ? null : new PhoneNumber(entity.getPhoneNumber()),
            entity.getLastSubmittedEventId(),
            entity.getRejectionReason() == null ? null : new PhoneRejection(entity.getRejectionReason(), entity.getRejectionMessage()),
            entity.getReservationId() == null ? null : new Confirmation(entity.getReservationId(), entity.getConfirmedAt()),
            entity.getCancelledAt() == null ? null : new Cancellation(entity.getCancellationReason(), entity.getCancelledAt()),
            entity.getClosingReason() == null ? null : new Closing(entity.getClosingReason(), entity.getClosingMessage())
        );
    }
}
