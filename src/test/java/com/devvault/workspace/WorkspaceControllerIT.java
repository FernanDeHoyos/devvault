package com.devvault.workspace;

import java.nio.file.Files;

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
     * CreatedWorkspaceRequest que contiene un nombre y una ruta no existente.
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
     * Se espera que la respuesta tenga un estado HTTP 200 (OK) y que el cuerpo de la respuesta
     * contenga una lista con un solo espacio de trabajo.
     * @throws Exception
     */
    @Test
    void shouldListWorkspaces() throws Exception {

        // Crea un directorio temporal y un espacio de trabajo para la prueba
        String tempDirA = Files.createTempDirectory("devvault-test-a").toString();
        String tempDirB = Files.createTempDirectory("devvault-test-b").toString();

        // Crea dos espacios de trabajo con rutas diferentes
        CreatedWorkspaceRequest requestA = new CreatedWorkspaceRequest("Workspace A", tempDirA);

        // Envía una solicitud POST para crear el primer espacio de trabajo
        mockMvc.perform(post("/api/v1/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestA)))
                .andExpect(status().isCreated());

        // Envía una solicitud POST para crear el segundo espacio de trabajo
        CreatedWorkspaceRequest requestB = new CreatedWorkspaceRequest("Workspace B", tempDirB);

        mockMvc.perform(post("/api/v1/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestB)))
                .andExpect(status().isCreated());

        // Envía una solicitud GET para listar los espacios de trabajo y verifica que la respuesta contenga ambos espacios de trabajo
        mockMvc.perform(get("/api/v1/workspaces")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Workspace A"))
                .andExpect(jsonPath("$[1].name").value("Workspace B"));
    }
}
