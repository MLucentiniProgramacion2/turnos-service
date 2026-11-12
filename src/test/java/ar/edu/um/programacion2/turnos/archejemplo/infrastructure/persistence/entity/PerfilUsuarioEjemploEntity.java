package ar.edu.um.programacion2.turnos.archejemplo.infrastructure.persistence.entity;

/**
 * Cómo lee una feature el perfil del usuario (decisión 1 del #10, opción b): una entidad propia, de solo lectura, sobre
 * las columnas de {@code jhi_user} que necesita. No depende de nada de JHipster, así que cumple todas las reglas. La real
 * lleva {@code @Entity}, {@code @Immutable} y {@code @Table(name = "jhi_user")}; esta no, por lo que explica
 * {@code package-info}.
 */
public class PerfilUsuarioEjemploEntity {

    private Long id;
    private String login;
    private String firstName;
    private String lastName;

    public String getLogin() {
        return login;
    }
}
