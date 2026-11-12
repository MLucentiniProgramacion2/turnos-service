package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Fila de {@code reservation_process} (changelog {@code reservation/001-reservation-process.xml}).
 * <ul>
 * <li><strong>{@code @Version}</strong>: bloqueo optimista (DPT-09). Hibernate suma 1 en cada {@code UPDATE} y le agrega
 * {@code where version = ?}: si otra transacción cambió la fila (un evento de Kafka y CU-11 a la vez), no actualiza nada
 * y lanza la excepción del bloqueo optimista en vez de pisar el cambio. Con el ID asignado por el caso de uso, la versión
 * {@code null} es además lo que le dice a Spring Data que la fila es nueva ({@code persist} y no {@code merge}).</li>
 * <li><strong>{@code user_id} sin relación JPA</strong> hacia {@code User} de JHipster: la clave foránea está en
 * Liquibase, y la feature no depende de las entidades de JHipster (las reglas de ArchUnit lo prohíben).</li>
 * <li><strong>Las horas se guardan sin convertir de zona</strong> ({@code @JdbcTypeCode(SqlTypes.LOCAL_TIME)}): con
 * {@code hibernate.jdbc.time_zone: UTC}, Hibernate convertiría cada {@code LocalTime} de la zona de la JVM a UTC y, con la
 * JVM en -03, las 09:00 quedarían como 12:00 (#27 del catálogo). Los {@code Instant} siguen en UTC.</li>
 * <li>Los datos personales (teléfono y nombres del paciente) no salen en {@code toString()}.</li>
 * </ul>
 */
@Entity
@Table(name = "reservation_process")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ReservationProcessEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_login", nullable = false, length = 50)
    private String userLogin;

    @Column(name = "professional_id", nullable = false)
    private Long professionalId;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "professional_first_name", nullable = false, length = 100)
    private String professionalFirstName;

    @Column(name = "professional_last_name", nullable = false, length = 100)
    private String professionalLastName;

    @Column(name = "professional_category_name", nullable = false, length = 100)
    private String professionalCategoryName;

    @Column(name = "slot_duration_minutes", nullable = false)
    private Integer slotDurationMinutes;

    @ToString.Exclude
    @Column(name = "patient_first_name", nullable = false, length = 100)
    private String patientFirstName;

    @ToString.Exclude
    @Column(name = "patient_last_name", nullable = false, length = 100)
    private String patientLastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ReservationProcessStatus status;

    @Column(name = "hold_id")
    private String holdId;

    @Column(name = "reservation_process_id")
    private String reservationProcessId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "request_event_id")
    private String requestEventId;

    @Column(name = "phone_request_message")
    private String phoneRequestMessage;

    @ToString.Exclude
    @Column(name = "phone_number", length = 16)
    private String phoneNumber;

    @Column(name = "last_submitted_event_id")
    private String lastSubmittedEventId;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "rejection_message")
    private String rejectionMessage;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "closing_reason")
    private String closingReason;

    @Column(name = "closing_message")
    private String closingMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;
}
