package ar.edu.um.programacion2.turnos.security;

import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasAnyAuthority;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority;
import static org.springframework.security.authorization.AuthorizationManagers.allOf;
import static org.springframework.security.authorization.AuthorizationManagers.not;

import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * La regla de los endpoints de usuario de turnos (ADR 0005, como {@code TokenAuthorizations} del catálogo): alguno de los
 * roles pedidos y <strong>no</strong> {@code ROLE_SERVICE}.
 * <p>
 * Turnos firma su token técnico de servicio (para el catálogo, DPT-16) con la misma clave HS512 que los de usuario: la
 * firma no dice qué clase de token es. Lo que marca un token como de servicio es {@code ROLE_SERVICE}, no el {@code aud}:
 * un token de servicio mal armado puede no traerlo. Un token mixto ({@code ROLE_SERVICE} con {@code ROLE_ADMIN}) da
 * {@code 403} igual.
 */
public final class TokenAuthorizations {

    private TokenAuthorizations() {}

    @SafeVarargs
    private static <T> AuthorizationManager<T> both(AuthorizationManager<T>... managers) {
        return allOf(managers);
    }

    /** Un token de usuario con alguno de esos roles. */
    public static AuthorizationManager<RequestAuthorizationContext> userToken(String... authorities) {
        return both(hasAnyAuthority(authorities), not(hasAuthority(AuthoritiesConstants.SERVICE)));
    }
}
