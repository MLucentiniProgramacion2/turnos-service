package ar.edu.um.programacion2.turnos.web.rest;

import ar.edu.um.programacion2.turnos.domain.Authority;
import ar.edu.um.programacion2.turnos.repository.AuthorityRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tech.jhipster.web.util.ResponseUtil;

/**
 * REST controller for reading {@link ar.edu.um.programacion2.turnos.domain.Authority}.
 * <p>
 * Solo lectura: los roles los fija Liquibase (ROLE_ADMIN y ROLE_USER). Sin alta ni baja, un admin
 * no puede crear ROLE_SERVICE, que es del token técnico de servicio (DPT-16).
 */
@RestController
@RequestMapping("/api/authorities")
@Transactional(readOnly = true)
public class AuthorityResource {

    private static final Logger LOG = LoggerFactory.getLogger(AuthorityResource.class);

    private final AuthorityRepository authorityRepository;

    public AuthorityResource(AuthorityRepository authorityRepository) {
        this.authorityRepository = authorityRepository;
    }

    /**
     * {@code GET  /authorities} : get all the Authorities.
     *
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of Authorities in body.
     */
    @GetMapping("")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
    public List<Authority> getAllAuthorities() {
        LOG.debug("REST request to get all Authorities");
        return authorityRepository.findAll();
    }

    /**
     * {@code GET  /authorities/:id} : get the "id" authority.
     *
     * @param id the id of the authority to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the authority, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
    public ResponseEntity<Authority> getAuthority(@PathVariable("id") String id) {
        LOG.debug("REST request to get Authority : {}", id);
        Optional<Authority> authority = authorityRepository.findById(id);
        return ResponseUtil.wrapOrNotFound(authority);
    }
}
