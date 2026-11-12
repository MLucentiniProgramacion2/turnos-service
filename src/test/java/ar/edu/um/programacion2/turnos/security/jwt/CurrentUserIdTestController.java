package ar.edu.um.programacion2.turnos.security.jwt;

import ar.edu.um.programacion2.turnos.security.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Solo para tests: expone {@link SecurityUtils#getCurrentUserId()} para probarlo con un token real,
 * que pasa por el filtro del resource server como cualquier pedido.
 */
@RestController
public class CurrentUserIdTestController {

    @GetMapping("/api/test/current-user-id")
    public ResponseEntity<Long> currentUserId() {
        return SecurityUtils.getCurrentUserId().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
