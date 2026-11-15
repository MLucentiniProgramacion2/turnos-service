package ar.edu.um.programacion2.turnos.reservation;

import ar.edu.um.programacion2.turnos.security.SecurityUtils;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Tokens para los tests de los endpoints de la reserva, firmados con la clave de test como los firma turnos (ADR 0003):
 * {@code sub} = login, {@code auth} = roles separados por espacios y {@code userId} = id interno.
 */
public final class ReservationTestTokens {

    private ReservationTestTokens() {}

    /** {@code Authorization} de un usuario final. */
    public static String usuario(String jwtKey, String login, long userId) {
        return bearer(jwtKey, login, "ROLE_USER", userId, null);
    }

    public static String admin(String jwtKey, String login, long userId) {
        return bearer(jwtKey, login, "ROLE_ADMIN ROLE_USER", userId, null);
    }

    /** {@code Authorization} con un token técnico de servicio, como el que turnos le manda al catálogo. */
    public static String servicio(String jwtKey) {
        return bearer(jwtKey, "turnos-service", "ROLE_SERVICE", null, "catalogo-service");
    }

    private static String bearer(String jwtKey, String subject, String authorities, Long userId, String audience) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(Base64.from(jwtKey).decode(), "HmacSHA512")));
        Instant ahora = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .subject(subject)
            .issuedAt(ahora)
            .expiresAt(ahora.plusSeconds(300))
            .claim(SecurityUtils.AUTHORITIES_CLAIM, authorities);
        if (userId != null) {
            claims.claim(SecurityUtils.USER_ID_CLAIM, userId);
        }
        if (audience != null) {
            claims.issuer(subject).audience(List.of(audience));
        }
        JwsHeader header = JwsHeader.with(SecurityUtils.JWT_ALGORITHM).build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
