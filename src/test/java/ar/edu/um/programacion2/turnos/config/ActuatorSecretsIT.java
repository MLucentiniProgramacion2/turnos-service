package ar.edu.um.programacion2.turnos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.security.AuthoritiesConstants;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ningún secreto sale por los endpoints de actuator que muestran la configuración, ni siquiera a un administrador
 * (enunciado §9): ni el JWT técnico de la cátedra ni la clave de firma de los JWT ({@code JWT_BASE64_SECRET}), con la que
 * se podría firmar cualquier token de usuario o de servicio.
 * <p>
 * El {@code application.yml} de test tapa al principal: acá se repite la exposición de {@code env} y {@code configprops}.
 * Se exige un {@code 200} y que la propiedad aparezca (enmascarada), para que el test no pase sin probar nada.
 */
@AutoConfigureMockMvc
@WithMockUser(authorities = AuthoritiesConstants.ADMIN)
@IntegrationTest
@TestPropertySource(
    properties = { "management.endpoints.web.base-path=/management", "management.endpoints.web.exposure.include=env,configprops" }
)
class ActuatorSecretsIT {

    @Autowired
    private MockMvc mvc;

    @Value("${catedra.api.token}")
    private String tokenDeLaCatedra;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String claveJwt;

    /** {@code env} muestra el nombre de la propiedad como está en el YAML; {@code configprops}, el del campo Java. */
    @ParameterizedTest
    @CsvSource({ "/management/env, base64-secret", "/management/configprops, base64Secret" })
    void niElTokenDeLaCatedraNiLaClaveDelJwtSalenPorActuator(String ruta, String propiedad) throws Exception {
        String body = mvc.perform(get(ruta)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains(propiedad).doesNotContain(tokenDeLaCatedra).doesNotContain(claveJwt);
    }
}
