package ar.edu.um.programacion2.turnos.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.config.Constants;
import ar.edu.um.programacion2.turnos.domain.Authority;
import ar.edu.um.programacion2.turnos.domain.User;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.security.AuthoritiesConstants;
import ar.edu.um.programacion2.turnos.service.dto.AdminUserDTO;
import ar.edu.um.programacion2.turnos.web.rest.vm.ManagedUserVM;
import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Registro de usuarios finales (CU-01): activo al instante, campos administrados por el backend y
 * validaciones DPT-02 (nombres) y DPT-20 (login) en el DTO compartido.
 */
@AutoConfigureMockMvc
@IntegrationTest
class RegistroUsuarioIT {

    private static final String LOGIN = "registro-it";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Transactional
    void registroValidoQuedaActivoConSoloRoleUserAunqueElPedidoMandeOtraCosa() throws Exception {
        ManagedUserVM pedido = usuarioValido();
        // Campos que administra el backend (enunciado §3.2): el pedido no los puede elegir.
        pedido.setId(999_999L);
        pedido.setActivated(false);
        pedido.setAuthorities(Set.of(AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER));
        pedido.setCreatedBy("otro");
        pedido.setCreatedDate(Instant.parse("2000-01-01T00:00:00Z"));

        mockMvc
            .perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(pedido)))
            .andExpect(status().isCreated());

        User creado = userRepository.findOneWithAuthoritiesByLogin(LOGIN).orElseThrow();
        assertThat(creado.isActivated()).isTrue();
        assertThat(creado.getActivationKey()).isNull();
        assertThat(creado.getAuthorities()).extracting(Authority::getName).containsExactly(AuthoritiesConstants.USER);
        assertThat(creado.getId()).isNotEqualTo(999_999L);
        assertThat(creado.getCreatedBy()).isNotEqualTo("otro");
        assertThat(creado.getCreatedDate()).isAfter(Instant.parse("2000-01-01T00:00:00Z"));
    }

    static Stream<Arguments> pedidosInvalidos() {
        return Stream.of(
            Arguments.of("firstName faltante", (Consumer<ManagedUserVM>) u -> u.setFirstName(null)),
            Arguments.of("firstName de 1 carácter", (Consumer<ManagedUserVM>) u -> u.setFirstName("A")),
            Arguments.of("lastName faltante", (Consumer<ManagedUserVM>) u -> u.setLastName(null)),
            Arguments.of("lastName de 1 carácter", (Consumer<ManagedUserVM>) u -> u.setLastName("B")),
            Arguments.of("login de 2 caracteres", (Consumer<ManagedUserVM>) u -> u.setLogin("ab")),
            Arguments.of("email faltante", (Consumer<ManagedUserVM>) u -> u.setEmail(null))
        );
    }

    @ParameterizedTest(name = "{0} -> 400")
    @MethodSource("pedidosInvalidos")
    @Transactional
    void registroInvalidoDa400YNoCreaNada(String caso, Consumer<ManagedUserVM> invalidar) throws Exception {
        ManagedUserVM pedido = usuarioValido();
        invalidar.accept(pedido);
        long usuariosAntes = userRepository.count();

        mockMvc
            .perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(pedido)))
            .andExpect(status().isBadRequest());

        assertThat(userRepository.count()).isEqualTo(usuariosAntes);
    }

    @Test
    @Transactional
    void registroConEmailDe254CaracteresDa201() throws Exception {
        // 254 = máximo de CU-01. Parte local de 64 y etiquetas de dominio de hasta 63 (límites de @Email).
        String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(57) + ".com";
        assertThat(email).hasSize(254);
        ManagedUserVM pedido = usuarioValido();
        pedido.setEmail(email);

        mockMvc
            .perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(pedido)))
            .andExpect(status().isCreated());

        assertThat(userRepository.findOneByLogin(LOGIN).orElseThrow().getEmail()).isEqualTo(email);
    }

    @Test
    @Transactional
    @WithMockUser(LOGIN)
    void actualizarLaCuentaConNombreDe1CaracterDa400() throws Exception {
        userRepository.saveAndFlush(usuarioGuardado());
        AdminUserDTO cambios = new AdminUserDTO(usuarioGuardado());
        cambios.setFirstName("A");

        mockMvc
            .perform(post("/api/account").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(cambios)))
            .andExpect(status().isBadRequest());

        assertThat(userRepository.findOneByLogin(LOGIN).orElseThrow().getFirstName()).isEqualTo("Ana");
    }

    @Test
    @Transactional
    @WithMockUser(authorities = AuthoritiesConstants.ADMIN)
    void adminCreandoUsuarioSinApellidoOConLoginDe2CaracteresDa400() throws Exception {
        AdminUserDTO sinApellido = new AdminUserDTO(usuarioGuardado());
        sinApellido.setLastName(null);
        AdminUserDTO loginCorto = new AdminUserDTO(usuarioGuardado());
        loginCorto.setLogin("ab");

        for (AdminUserDTO pedido : new AdminUserDTO[] { sinApellido, loginCorto }) {
            mockMvc
                .perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(pedido)))
                .andExpect(status().isBadRequest());
        }

        assertThat(userRepository.findOneByLogin(LOGIN)).isEmpty();
        assertThat(userRepository.findOneByLogin("ab")).isEmpty();
    }

    private static ManagedUserVM usuarioValido() {
        ManagedUserVM usuario = new ManagedUserVM();
        usuario.setLogin(LOGIN);
        usuario.setPassword("clave-de-test");
        usuario.setFirstName("Ana");
        usuario.setLastName("Pérez");
        usuario.setEmail(LOGIN + "@example.com");
        usuario.setLangKey(Constants.DEFAULT_LANGUAGE);
        return usuario;
    }

    private static User usuarioGuardado() {
        User usuario = new User();
        usuario.setLogin(LOGIN);
        usuario.setPassword("$2a$10$".concat("x".repeat(53)));
        usuario.setFirstName("Ana");
        usuario.setLastName("Pérez");
        usuario.setEmail(LOGIN + "@example.com");
        usuario.setLangKey(Constants.DEFAULT_LANGUAGE);
        usuario.setActivated(true);
        return usuario;
    }
}
