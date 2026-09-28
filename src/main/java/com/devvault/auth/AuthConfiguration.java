package com.devvault.auth;

import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.devvault.auth.infrastructure.RevokedTokenRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    private final RevokedTokenRepository revokedTokens;

    public AuthConfiguration(RevokedTokenRepository revokedTokens) {
        this.revokedTokens = revokedTokens;
    }

    @Bean
    public SecretKey jwtSecretKey(LocalAuthStore authStore) {
        return authStore.signingKey();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, LocalAuthStore authStore) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).build();
        OAuth2TokenValidator<Jwt> notRevoked = jwt -> jwt.getId() != null && revokedTokens.existsById(jwt.getId())
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token has been revoked.", null))
                : OAuth2TokenValidatorResult.success();
        OAuth2TokenValidator<Jwt> currentCredentialVersion = jwt -> {
            Object claim = jwt.getClaim("authVersion");
            return claim instanceof Number version && version.longValue() == authStore.tokenVersion()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Credentials have changed.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("devvault"), notRevoked, currentCredentialVersion));
        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/setup-status").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/setup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/recover").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        // Log WebSockets are authorized by the single-use ticket handshake interceptor.
                        .requestMatchers("/api/v1/projects/*/logs").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("scope");
        authorities.setAuthorityPrefix("SCOPE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("http://localhost:5050", "http://127.0.0.1:5050"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setExposedHeaders(List.of("Authorization"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
