package ar.edu.um.programacion2.turnos.archejemplo.infrastructure;

import ar.edu.um.programacion2.turnos.domain.User;

/**
 * Usa la entidad {@code User} de JHipster para leer el perfil del usuario. Viola la regla por capas: el perfil se lee con
 * una entidad propia de solo lectura sobre {@code jhi_user} (como {@code PerfilUsuarioEjemploEntity}).
 */
public class FeatureQueUsaDomainDeJHipster {

    public String nombre(User usuario) {
        return usuario.getFirstName();
    }
}
