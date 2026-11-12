package ar.edu.um.programacion2.turnos.web.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Endpoints de JHipster que turnos no expone (ADR 0003): el listado público de usuarios
 * (aislamiento del §9, DPT-19) y los que dependían del envío de correo. Se prueban con un usuario
 * autenticado, para que el 4xx sea porque el endpoint no existe y no solo porque falta el token.
 */
@AutoConfigureMockMvc
@IntegrationTest
@WithMockUser("endpoints-eliminados-it")
class EndpointsEliminadosIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listadoDeUsuariosNoExiste() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().is4xxClientError());
    }

    @Test
    void activacionPorCorreoNoExiste() throws Exception {
        mockMvc.perform(get("/api/activate").param("key", "cualquiera")).andExpect(status().is4xxClientError());
    }

    @Test
    void recuperacionDeContrasenaNoExiste() throws Exception {
        mockMvc
            .perform(post("/api/account/reset-password/init").contentType(MediaType.TEXT_PLAIN).content("alguien@example.com"))
            .andExpect(status().is4xxClientError());
    }
}
