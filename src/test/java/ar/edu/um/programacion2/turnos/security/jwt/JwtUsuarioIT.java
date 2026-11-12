package ar.edu.um.programacion2.turnos.security.jwt;

import static ar.edu.um.programacion2.turnos.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.turnos.security.SecurityUtils.USER_ID_CLAIM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.domain.User;
import ar.edu.um.programacion2.turnos.repository.AuthorityRepository;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.security.AuthoritiesConstants;
import ar.edu.um.programacion2.turnos.web.rest.vm.LoginVM;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * JWT de usuario que emite turnos (CU-02, DPT-16, DPT-21): sub = login, auth = autoridades y
 * userId = id interno; sin aud ni iss, que son del token técnico de servicio.
 */
@AutoConfigureMockMvc
@IntegrationTest
class JwtUsuarioIT {

    private static final String LOGIN = "jwt-usuario-it";

    private static final String PASSWORD = "clave-de-test";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthorityRepository authorityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Transactional
    void loginEmiteTokenConSubAuthYUserIdDelUsuarioYSinAudNiIss() throws Exception {
        User usuario = guardarUsuario();

        Jwt token = jwtDecoder.decode(login());

        assertThat(token.getSubject()).isEqualTo(LOGIN);
        // Solo los roles del usuario: sin FACTOR_PASSWORD, que Spring Security 7 suma al autenticar.
        assertThat(token.getClaimAsString(AUTHORITIES_CLAIM)).isEqualTo(AuthoritiesConstants.USER);
        assertThat(token.getClaims().get(USER_ID_CLAIM)).isInstanceOf(Number.class);
        assertThat(((Number) token.getClaims().get(USER_ID_CLAIM)).longValue()).isEqualTo(usuario.getId());
        assertThat(token.getClaims()).doesNotContainKeys(JwtClaimNames.AUD, JwtClaimNames.ISS);
        assertThat(token.getExpiresAt()).isAfter(token.getIssuedAt());
    }

    @Test
    @Transactional
    void securityUtilsDevuelveElIdDelUsuarioDesdeElToken() throws Exception {
        User usuario = guardarUsuario();
        String token = login();

        mockMvc
            .perform(get("/api/test/current-user-id").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(content().string(String.valueOf(usuario.getId())));
    }

    private User guardarUsuario() {
        User usuario = new User();
        usuario.setLogin(LOGIN);
        usuario.setEmail(LOGIN + "@example.com");
        usuario.setFirstName("Ana");
        usuario.setLastName("Pérez");
        usuario.setActivated(true);
        usuario.setPassword(passwordEncoder.encode(PASSWORD));
        usuario.setAuthorities(Set.of(authorityRepository.findById(AuthoritiesConstants.USER).orElseThrow()));
        return userRepository.saveAndFlush(usuario);
    }

    private String login() throws Exception {
        LoginVM login = new LoginVM();
        login.setUsername(LOGIN);
        login.setPassword(PASSWORD);
        String respuesta = mockMvc
            .perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(login)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return om.readTree(respuesta).get("id_token").asString();
    }
}
