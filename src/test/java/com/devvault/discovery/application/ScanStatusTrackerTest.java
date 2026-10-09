package com.devvault.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * El estado de escaneo vive en memoria y se consulta por polling desde la UI.
 *
 * <p>El caso que importa es el de un Workspace recién creado: nunca se ha
 * escaneado, así que el mapa está vacío. Antes ese caso devolvía 404 desde el
 * controlador, que llenaba la consola del navegador en cada tarjeta de la
 * pantalla de workspaces sin que hubiera nada que arreglar. Estos tests fijan el
 * comportamiento correcto para que no vuelva.
 */
class ScanStatusTrackerTest {

    private final ScanStatusTracker tracker = new ScanStatusTracker();

    @Test
    void unWorkspaceNuncaEscaneadoNoEsUnError() {

        UUID workspaceId = UUID.randomUUID();

        // El Workspace existe y el escaneo no: son dos hechos distintos y el
        // tracker solo conoce el segundo.
        assertThat(tracker.getStatus(workspaceId)).isEmpty();
        assertThat(tracker.statusOrNotStarted(workspaceId).status())
                .isEqualTo(ScanStatusTracker.ScanStatus.NOT_STARTED);
    }

    @Test
    void notStartedNoInventaFechasNiProyectosDetectados() {

        UUID workspaceId = UUID.randomUUID();

        ScanStatusTracker.Snapshot snapshot = tracker.statusOrNotStarted(workspaceId);

        // La UI pinta el proyecto(count) cuando hay resultado. Un 0 con fechas
        // nulas es lo único honesto: no se ha contado nada todavía.
        assertThat(snapshot.projectsFound()).isZero();
        assertThat(snapshot.startedAt()).isNull();
        assertThat(snapshot.finishedAt()).isNull();
        assertThat(snapshot.errorMessage()).isNull();
    }

    @Test
    void consultarSinEscanearNoCreaRuidoVisible() {

        UUID workspaceId = UUID.randomUUID();

        tracker.statusOrNotStarted(workspaceId);

        // Pedir el estado no debe parecer que hubo un escaneo: si se escribiera
        // NOT_STARTED como si fuera un resultado, getStatus lo devolvería y la UI
        // dejaría de distinguir "nunca escaneado" de "escaneado y falló".
        assertThat(tracker.getStatus(workspaceId)).isEmpty();
    }

    @Test
    void unEscaneoEnCursoReemplazaElEstadoPorDefecto() {

        UUID workspaceId = UUID.randomUUID();
        tracker.statusOrNotStarted(workspaceId);

        tracker.markInProgress(workspaceId);

        assertThat(tracker.statusOrNotStarted(workspaceId).status())
                .isEqualTo(ScanStatusTracker.ScanStatus.IN_PROGRESS);
        assertThat(tracker.statusOrNotStarted(workspaceId).startedAt()).isNotNull();
    }

    @Test
    void forgetDevuelveElWorkspaceAlEstadoNuncaEscaneado() {

        UUID workspaceId = UUID.randomUUID();
        tracker.markInProgress(workspaceId);

        tracker.forget(workspaceId);

        // Borra un Workspace, el mapa no expira entradas y el snapshot se
        // quedaría de un Workspace que ya no existe: una fuga por cada
        // workspace borrado durante toda la vida del proceso.
        assertThat(tracker.getStatus(workspaceId)).isEmpty();
        assertThat(tracker.statusOrNotStarted(workspaceId).status())
                .isEqualTo(ScanStatusTracker.ScanStatus.NOT_STARTED);
    }

    @Test
    void forgetNoAfectaAOtrosWorkspaces() {

        UUID borrado = UUID.randomUUID();
        UUID otro = UUID.randomUUID();
        tracker.markInProgress(borrado);
        tracker.markInProgress(otro);

        tracker.forget(borrado);

        assertThat(tracker.statusOrNotStarted(otro).status())
                .isEqualTo(ScanStatusTracker.ScanStatus.IN_PROGRESS);
    }
}