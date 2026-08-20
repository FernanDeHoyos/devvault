package com.devvault.workspace.infrastructure;
import com.devvault.workspace.domain.Workspace;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    /**
     * Mira si existe un workspace con el path dado.
     *
     * @param path el path del workspace a buscar
     * @return verdadero si existe un workspace con el path dado, falso en caso contrario
     */
    boolean existsByPath(String path);

    /**
     * Busca un workspace por su path.
     *
     * @param path el path del workspace a buscar
     * @return un Optional que contiene el workspace si se encuentra, o vacío si no se encuentra
     * Optional es una clase contenedora que puede contener un valor no nulo o estar vacía, 
     * lo que ayuda a evitar errores de puntero nulo y hace que el código sea más legible.
     */

    Optional<Workspace> findByPath(String path);
}
