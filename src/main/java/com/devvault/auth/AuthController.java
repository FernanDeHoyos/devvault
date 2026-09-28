package com.devvault.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devvault.auth.dto.AuthenticatedUserResponse;
import com.devvault.auth.dto.LoginRequest;
import com.devvault.auth.dto.PasswordRecoveryRequest;
import com.devvault.auth.dto.SetupRequest;
import com.devvault.auth.dto.SetupStatusResponse;
import com.devvault.auth.dto.TokenResponse;
import com.devvault.auth.dto.WebSocketTicketRequest;
import com.devvault.auth.dto.WebSocketTicketResponse;
import com.devvault.shared.api.exception.ApiException;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final WebSocketTicketService ticketService;

    public AuthController(AuthService authService, WebSocketTicketService ticketService) {
        this.authService = authService;
        this.ticketService = ticketService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/setup-status")
    public SetupStatusResponse setupStatus() {
        return new SetupStatusResponse(authService.setupRequired(), authService.recoveryConfigured());
    }

    @PostMapping("/setup")
    public ResponseEntity<Void> setup(@Valid @RequestBody SetupRequest request, HttpServletRequest servletRequest) {
        try {
            requireLoopback(servletRequest);
        } catch (UnknownHostException exception) {
            throw new ApiException(HttpStatus.FORBIDDEN, "No se pudo validar el origen local de la solicitud.");
        }
        authService.createAdministrator(request.username(), request.password(), request.recoveryKey());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recover")
    public ResponseEntity<Void> recover(@Valid @RequestBody PasswordRecoveryRequest request, HttpServletRequest servletRequest) {
        try {
            requireLoopback(servletRequest);
        } catch (UnknownHostException exception) {
            throw new ApiException(HttpStatus.FORBIDDEN, "No se pudo validar el origen local de la solicitud.");
        }
        authService.recoverPassword(request);
        return ResponseEntity.noContent().build();
    }

    private static void requireLoopback(HttpServletRequest request) throws UnknownHostException {
        if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Esta operación solo está disponible desde este equipo.");
        }
    }

    @GetMapping("/me")
    public AuthenticatedUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new AuthenticatedUserResponse(jwt.getSubject());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        if (jwt.getId() == null || jwt.getExpiresAt() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Token inválido.");
        }
        authService.logout(jwt.getId(), jwt.getExpiresAt());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ws-ticket")
    public WebSocketTicketResponse createWebSocketTicket(@Valid @RequestBody WebSocketTicketRequest request) {
        WebSocketTicketService.IssuedTicket ticket = ticketService.issue(request.projectId(), request.serviceName());
        return new WebSocketTicketResponse(ticket.value(), ticket.expiresAt());
    }
}
