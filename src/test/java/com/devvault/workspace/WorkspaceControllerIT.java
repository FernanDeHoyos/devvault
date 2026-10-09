package com.devvault.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.devvault.workspace.application.dto.CreatedWorkspaceRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class WorkspaceControllerIT {


    /** PostgreSQL container for integration testing 
     * Container es una anotación de Testcontainers que indica que la 
     * clase de prueba utiliza contenedores de Docker para las pruebas de integración.
     * ServiceConnection es una anotación de Spring Boot que indica que la clase de prueba 
     * necesita conectarse a un servicio externo, en este caso, la base de datos PostgreSQL proporcionada por el contenedor.    
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 
     * MockMvc es una clase de Spring que permite realizar pruebas de integración de controladores web sin necesidad de iniciar un servidor web completo.
     * ObjectMapper es una clase de Jackson que permite convertir objetos Java a JSON y viceversa
     */
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;
    

    /**
     * Prueba de integración para crear un espacio de trabajo con una ruta válida.
     * Se crea un directorio temporal y se envía una solicitud POST al endpoint /api/v1/workspaces 
     * con un objeto CreatedWorkspaceRequest que contiene el nombre y la ruta del espacio de trabajo.
     * Se espera que la respuesta tenga un estado HTTP 201 (Created) y que el cuerpo de la respuesta 
     * contenga el nombre y la ruta del espacio de trabajo creado.
     * @throws Exception
     */
    @Test
    void shouldCreateWorkspaceWithValidPath() throws Exception {
        String tempDir = Files.createTempDirectory("devvault-test").toString();
        CreatedWorkspaceRequest request = new CreatedWorkspaceRequest("My Workspace", tempDir);

        mockMvc.perform(post("/api/v1/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My Workspace"))
                .andExpect(jsonPath("$.path").value(tempDir));
    }


    /**
     * Prueba de integración para crear un espacio de trabajo con una ruta no existente.
     * Se envía una solicitud POST al endpoint /api/v1/workspaces con un objeto 
     * CreatedWorkspaceRequest que contiene el nombre y la ruta de un espacio de trabajo
     * que no existe en el sistema de archivos.
     * Se espera que la respuesta tenga un estado HTTP 422 (Unprocessable Entity) y
     * @throws Exception
     */
    @Test
    void shouldRejectNonExistentPath() throws Exception {
        CreatedWorkspaceRequest request = new CreatedWorkspaceRequest("Invalid Workspace", "/non/existent/path");

        mockMvc.perform(post("/api/v1/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The specified path does not exist."));
    }


    /**
     * Prueba de integración para crear un espacio de trabajo con una ruta duplicada.
     * Se crea un directorio temporal y se envía una solicitud POST al endpoint /api/v1/workspaces 
     * con un objeto CreatedWorkspaceRequest que contiene el nombre y la ruta del espacio de trabajo.
     * Luego, se envía otra solicitud POST con el mismo objeto CreatedWorkspaceRequest.
     * Se espera que la primera respuesta tenga un estado HTTP 201 (Created) y que la segunda respuesta 
     * tenga un estado HTTP 409 (Conflict) y que el cuerpo de la respuesta contenga un mensaje de error.
     * @throws Exception
     */
    @Test
    void shouldRejectDuplicatePath() throws Exception {
        String tempDir = Files.createTempDirectory("devvault-test-duplicate").toString();
        CreatedWorkspaceRequest request = new CreatedWorkspaceRequest("Duplicate Workspace", tempDir);
        
        mockMvc.perform(post("/api/v1/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A workspace with the specified path already exists."));
    }


    /**
     * Prueba de integración para listar los espacios de trabajo.
     * Se envía una solicitud GET al endpoint /api/v1/workspaces.
     * Se espera que la respuesta tenga un estado HTTP 200 (OK) y que contenga los dos
     * espacios de trabajo creados por esta prueba.
     * 
     * <p>Antes se afirmaba el número total de elementos devueltos, lo que sólo era
     * cierto si esta prueba se ejecutaba la primera: todos los tests de esta clase
     * comparten el mismo contenedor y la misma base, y los demás también crean
     * workspaces que se quedan ahí. Ahora se busca por nombre, que es lo que el test
     * pretende comprobar realmente, sin depender del orden de ejecución.
     * 
     * @throws Exception
     */
    @Test
    void shouldListWorkspaces() throws Exception {

        // Crea dos directorios temporales y dos espacios de trabajo con rutas distintas
        String tempDirA = Files.createTempDirectory("devvault-test-a").toString();
        String tempDirB = Files.createTempDirectory("devvault-test-b").toString();
        createWorkspaceAndReturnId("Workspace A", tempDirA);
        createWorkspaceAndReturnId("Workspace B", tempDirB);

        mockMvc.perform(get("/api/v1/workspaces")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='Workspace A')]").exists())
                .andExpect(jsonPath("$[?(@.name=='Workspace B')]").exists());
    }

    /**
     * Un Workspace recién creado nunca se ha escaneado, así que su estado de escaneo
     * es NOT_STARTED y no un 404. La UI hace polling de esta ruta en cada tarjeta de
     * la pantalla de workspaces, así que el 404 llenaba la consola del navegador en el
     * caso más normal que existe: un workspace recién añadido.
     *
     * @throws Exception
     */
    @Test
    void shouldReportNotStartedForNeverScannedWorkspace() throws Exception {
        String tempDir = Files.createTempDirectory("devvault-test-notstarted").toString();
        String id = createWorkspaceAndReturnId("Never Scanned", tempDir);

        mockMvc.perform(get("/api/v1/workspaces/" + id + "/scan/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.projectsFound").value(0))
                .andExpect(jsonPath("$.startedAt").doesNotExist());
    }

    /**
     * Borrar un Workspace lo quita de DevVault y devuelve 204. Los archivos de la
     * carpeta deben seguir en el disco: borrar un Workspace es olvidarse de una
     * carpeta, no borrarla del disco.
     *
     * @throws Exception
     */
    @Test
    void shouldDeleteWorkspaceAndKeepItsFiles() throws Exception {
        Path folder = Files.createTempDirectory("devvault-test-delete");
        String id = createWorkspaceAndReturnId("To Delete", folder.toString());

        mockMvc.perform(delete("/api/v1/workspaces/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/workspaces/" + id))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/workspaces"))
                .andExpect(jsonPath("$[?(@.name=='To Delete')]").doesNotExist());

        // Lo que se borra son los datos de DevVault, no la carpeta del usuario.
        assertThat(folder).exists();
    }

    /**
     * Borrar un Workspace que no existe tiene que ser 404, no 500: el cliente ya lo
     * quitó de su lista y vuelve a pulsar el botón, o dos pestañas lo hacen a la vez.
     *
     * @throws Exception
     */
    @Test
    void shouldReturnNotFoundWhenDeletingUnknownWorkspace() throws Exception {
        mockMvc.perform(delete("/api/v1/workspaces/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    /**
     * Crea un Workspace y devuelve su id.
     *
     * <p>Los tests que necesitan borrar lo que crearon leen el id de la respuesta en
     * lugar de suponer que será el primero de la lista: la base está compartida con el
     * resto de tests de la clase y el orden no está garantizado.
     *
     * @param nombre nombre del Workspace
     * @param path   ruta de la carpeta, que debe existir
     * @return el id del Workspace recién creado
     * @throws Exception
     */
    private String createWorkspaceAndReturnId(String nombre, String path) throws Exception {
        CreatedWorkspaceRequest request = new CreatedWorkspaceRequest(nombre, path);
        String json = mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(json).get("id").asText();
    }
}