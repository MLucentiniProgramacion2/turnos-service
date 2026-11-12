package ar.edu.um.programacion2.turnos.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turnos rechaza los tokens técnicos de servicio en sus propios endpoints (ADR 0005, como el catálogo desde su #35):
 * turnos los firma con la misma clave que los de usuario, así que la firma sola los dejaría pasar. Y ningún token sin
 * {@code exp} pasa.
 * <p>
 * Los tokens se firman acá con la clave de test, como los firmaría turnos, y se mandan con MockMvc por la cadena de
 * seguridad real.
 */
@AutoConfigureMockMvc
@IntegrationTest
@TestPropertySource(properties = { "management.endpoints.web.base-path=/management", "management.endpoints.web.exposure.include=env,info" })
class ServiceTokenRejectionIT {

    private static final String LOGIN = "reserva-seguridad";

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void crearUsuario() {
        ReservationTestDatabase.limpiar(tx, em);
        tx.executeWithoutResult(status -> userRepository.saveAndFlush(ReservationTestDatabase.usuario(LOGIN)));
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/account", "/api/admin/users", "/api/authorities", "/management/env", "/v3/api-docs" })
    void unTokenDeServicioDa403EnLosEndpointsDeUsuario(String ruta) throws Exception {
        mvc.perform(get(ruta).header("Authorization", "Bearer " + token(serviceClaims()))).andExpect(status().isForbidden());
    }

    @Test
    void unTokenMixtoDeServicioYAdminTambienDa403() throws Exception {
        String mixto = token(c -> {
            serviceClaims().accept(c);
            c.claim(SecurityUtils.AUTHORITIES_CLAIM, "ROLE_SERVICE ROLE_ADMIN");
        });

        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + mixto)).andExpect(status().isForbidden());
    }

    /** {@code /management/info} es pública: con un token de servicio responde lo mismo que sin token (decisión del #10). */
    @Test
    void managementInfoSigueSiendoPublica() throws Exception {
        mvc.perform(get("/management/info").header("Authorization", "Bearer " + token(serviceClaims()))).andExpect(status().isOk());
    }

    @Test
    void unTokenSinExpDa401AunqueEsteFirmado() throws Exception {
        String sinExp = token(c -> c.subject(LOGIN).issuedAt(Instant.now()).claim(SecurityUtils.AUTHORITIES_CLAIM, "ROLE_USER"));

        mvc.perform(get("/api/account").header("Authorization", "Bearer " + sinExp)).andExpect(status().isUnauthorized());
    }

    @Test
    void losTokensDeUsuarioSiguenAndando() throws Exception {
        mvc.perform(get("/api/account").header("Authorization", "Bearer " + token(userClaims("ROLE_USER")))).andExpect(status().isOk());
        mvc
            .perform(get("/api/admin/users").header("Authorization", "Bearer " + token(userClaims("ROLE_USER ROLE_ADMIN"))))
            .andExpect(status().isOk());
        mvc
            .perform(get("/api/admin/users").header("Authorization", "Bearer " + token(userClaims("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    private static Consumer<JwtClaimsSet.Builder> serviceClaims() {
        Instant ahora = Instant.now();
        return c ->
            c
                .subject("turnos-service")
                .issuer("turnos-service")
                .audience(List.of("catalogo-service"))
                .issuedAt(ahora)
                .expiresAt(ahora.plusSeconds(300))
                .claim(SecurityUtils.AUTHORITIES_CLAIM, AuthoritiesConstants.SERVICE);
    }

    private static Consumer<JwtClaimsSet.Builder> userClaims(String roles) {
        Instant ahora = Instant.now();
        return c ->
            c.subject(LOGIN).issuedAt(ahora).expiresAt(ahora.plusSeconds(300)).claim(SecurityUtils.AUTHORITIES_CLAIM, roles);
    }

    private String token(Consumer<JwtClaimsSet.Builder> claims) {
        byte[] clave = Base64.from(jwtKey).decode();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(clave, "HmacSHA512")));
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder();
        claims.accept(builder);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SecurityUtils.JWT_ALGORITHM).build(), builder.build())).getTokenValue();
    }
}
