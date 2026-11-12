package ar.edu.um.programacion2.turnos.security;

/**
 * Constants for Spring Security authorities.
 */
public final class AuthoritiesConstants {

    public static final String ADMIN = "ROLE_ADMIN";

    public static final String USER = "ROLE_USER";

    public static final String ANONYMOUS = "ROLE_ANONYMOUS";

    /**
     * El rol del token técnico de servicio que turnos emite para llamar al catálogo (DPT-16, ADR 0005). Ningún usuario lo
     * tiene (las autoridades son fijas, ADR 0003), y los endpoints de turnos rechazan cualquier token que lo traiga.
     */
    public static final String SERVICE = "ROLE_SERVICE";

    private AuthoritiesConstants() {}
}
