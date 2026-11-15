package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Immutable;

/**
 * El perfil del usuario, leído de {@code jhi_user} (decisión del #10): una entidad <strong>propia y de solo lectura</strong>
 * con las cuatro columnas que necesita la reserva, en vez de usar {@code User}, {@code UserRepository} o
 * {@code UserService} de JHipster. Así la feature no depende de nada de JHipster y las reglas de ArchUnit no tienen
 * excepciones.
 * <p>
 * {@code @Immutable} (Hibernate): la entidad nunca se actualiza ni se borra, aunque alguien le cambiara un campo; la tabla
 * la escribe solo JHipster. Tampoco tiene setters. Que haya dos entidades sobre la misma tabla es válido: Hibernate las
 * trata por separado.
 * <p>
 * Si JHipster renombra o saca una de estas columnas, la validación del esquema ({@code ddl-auto: validate}) impide
 * arrancar, en vez de fallar en la primera reserva. Los nombres del usuario son datos personales: no salen en
 * {@code toString()}.
 */
@Entity
@Immutable
@Table(name = "jhi_user")
@Getter
@NoArgsConstructor
@ToString
public class UserProfileEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "login")
    private String login;

    @ToString.Exclude
    @Column(name = "first_name")
    private String firstName;

    @ToString.Exclude
    @Column(name = "last_name")
    private String lastName;
}
