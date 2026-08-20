package com.devvault.shared.api.exception;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Formato de error estándar definido en DevVault-API-Design.md, 
 * sección "Convenciones generales".
 */

@RestControllerAdvice
public class Globalexceptionhandler {


    /**
     * Maneja las excepciones de tipo ApiException y devuelve una respuesta HTTP con el estado y el mensaje de error correspondiente.
     * @param ex la excepción ApiException lanzada
     * @param request la solicitud web que causó la excepción
     * @return una ResponseEntity con el estado HTTP y el cuerpo del error
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex, WebRequest request) { 
        return ResponseEntity. 
        status(ex.getStatus()) // Establece el estado HTTP de la respuesta según el estado de la excepción
                .body(buildBody(ex.getStatus().value(), ex.getMessage(), request)); // Construye el cuerpo de la respuesta de error con el estado, 
                // el mensaje y la ruta de la solicitud
    }

    /**
     * Maneja las excepciones de tipo MethodArgumentNotValidException y devuelve una respuesta HTTP con el estado y el mensaje de error correspondiente.
     * @param ex la excepción MethodArgumentNotValidException lanzada
     * @param request la solicitud web que causó la excepción
     * @return una ResponseEntity con el estado HTTP y el cuerpo del error
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex, WebRequest request) {
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse(ex.getMessage());

        return ResponseEntity
                .status(400)
                .body(buildBody(400, errorMessage, request));
    }

    /**
     * Construye el cuerpo de la respuesta de error con el estado, el mensaje y la ruta de la solicitud.
     * @param status el estado HTTP de la respuesta
     * @param message el mensaje de error
     * @param request la solicitud web que causó la excepción
     * @return un mapa que representa el cuerpo de la respuesta de error
     */
    private Map<String, Object> buildBody(int status, String message, WebRequest request) {
        Map<String, Object> body = Map.of(
                "status", status,
                "message", message,
                "path", request.getDescription(false).replace("uri=", "")
        );
        return body;
    }
}
