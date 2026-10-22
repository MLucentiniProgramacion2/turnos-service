package ar.edu.um.programacion2.turnos.repository;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.domain.Authority;
import ar.edu.um.programacion2.turnos.security.AuthoritiesConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Liquibase carga solo las autoridades: no hay usuarios por defecto (admin/admin, user/user).
 */
@IntegrationTest
class DatosInicialesIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthorityRepository authorityRepository;

    @Test
    void soloSeCarganLasAutoridadesSinUsuariosPorDefecto() {
        assertThat(authorityRepository.findAll())
            .extracting(Authority::getName)
            .containsExactlyInAnyOrder(AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER);
        assertThat(userRepository.findOneByLogin("admin")).isEmpty();
        assertThat(userRepository.findOneByLogin("user")).isEmpty();
    }
}
