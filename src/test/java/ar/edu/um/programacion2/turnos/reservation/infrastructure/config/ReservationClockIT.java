package ar.edu.um.programacion2.turnos.reservation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.domain.User;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
import ar.edu.um.programacion2.turnos.security.ServiceTokenIssuer;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El bean {@link Clock} de la reserva (issue #12) es el primero del contexto. Acá se lo fija en 2020 y se comprueba que lo
 * que ya existía sigue usando la hora real: el token técnico de servicio (un token emitido en 2020 daría {@code 401} en el
 * catálogo) y la auditoría de JHipster ({@code created_date}).
 */
@IntegrationTest
class ReservationClockIT {

    private static final Instant EN_2020 = Instant.parse("2020-01-01T00:00:00Z");

    @MockitoBean
    private Clock clock;

    @Autowired
    private ServiceTokenIssuer serviceTokenIssuer;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void fijarElReloj() {
        when(clock.instant()).thenReturn(EN_2020);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void elTokenDeServicioSigueSaliendoConLaHoraReal() {
        // El decoder real valida exp: con un token emitido en 2020 lanzaría.
        Jwt token = jwtDecoder.decode(serviceTokenIssuer.issue());

        assertThat(Duration.between(token.getIssuedAt(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void laAuditoriaDeJHipsterSigueUsandoLaHoraReal() {
        User guardado = userRepository.saveAndFlush(ReservationTestDatabase.usuario("reserva-reloj"));

        assertThat(Duration.between(guardado.getCreatedDate(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
    }
}
