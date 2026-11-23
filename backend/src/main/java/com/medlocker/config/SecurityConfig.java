package com.medlocker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medlocker.exception.GlobalExceptionHandler;
import com.medlocker.service.JwtService;
import com.medlocker.service.SocialAccounts;
import com.medlocker.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    private static final String PIN_PATH = "/api/v1/share/access";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtService jwt, ObjectMapper mapper, UserRepository users,
                                 ClientRegistrationRepository providers, SocialAccounts socialAccounts,
                                 @Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        SocialLoginConfig.configure(http, providers, socialAccounts);
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .contentSecurityPolicy(policy -> policy.policyDirectives(
                                "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; "
                                + "worker-src 'self' blob: https://cdn.jsdelivr.net; "
                                + "connect-src 'self' https://cdn.jsdelivr.net; img-src 'self' data: blob:; "
                                + "font-src 'self' data: https://cdn.jsdelivr.net; "
                                + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")))
                .authorizeHttpRequests(routes -> routes
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/viewer.html", "/css/**", "/js/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/oauth2/authorization/**", "/login/oauth2/code/**",
                                "/api/v1/auth/social/providers").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/social/start", "/api/v1/auth/social/result",
                                "/api/v1/auth/password/request", "/api/v1/auth/password/reset").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/doctor/login", "/api/v1/auth/doctor/register").permitAll()
                        .requestMatchers(HttpMethod.POST, PIN_PATH).hasRole("DOCTOR")
                        .requestMatchers("/api/v1/documents", "/api/v1/documents/**", "/api/v1/profile").hasRole("PATIENT")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) ->
                                writeError(mapper, response, HttpStatus.UNAUTHORIZED, "Sign in to continue."))
                        .accessDeniedHandler((request, response, failure) ->
                                writeError(mapper, response, HttpStatus.FORBIDDEN, "Access denied.")))
                .addFilterBefore(new ApiFilter(jwt, mapper, users), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${medlocker.allowed-origin}") String origin) {
        var source = new UrlBasedCorsConfigurationSource();
        if (!origin.isBlank()) {
            var configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(List.of(origin));
            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
            configuration.setExposedHeaders(List.of("X-Expires-At", HttpHeaders.RETRY_AFTER));
            source.registerCorsConfiguration("/api/**", configuration);
        }
        return source;
    }

    private static void writeError(ObjectMapper mapper, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
        mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.payload(status, message));
    }

    private static final class ApiFilter extends OncePerRequestFilter {
        private final JwtService jwt;
        private final ObjectMapper mapper;
        private final UserRepository users;
        private final RateLimit pins = new RateLimit(30);
        private final RateLimit accounts = new RateLimit(30);
        private final RateLimit resetRequests = new RateLimit(12);
        private final RateLimit resetAttempts = new RateLimit(30);

        private ApiFilter(JwtService jwt, ObjectMapper mapper, UserRepository users) {
            this.jwt = jwt;
            this.mapper = mapper;
            this.users = users;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String path = request.getServletPath();
            if ("POST".equals(request.getMethod())) {
                RateLimit limit = switch (path) {
                    case PIN_PATH -> pins;
                    case "/api/v1/auth/password/request" -> resetRequests;
                    case "/api/v1/auth/password/reset" -> resetAttempts;
                    case "/api/v1/auth/login", "/api/v1/auth/register",
                         "/api/v1/auth/doctor/login", "/api/v1/auth/doctor/register" -> accounts;
                    default -> null;
                };
                if (limit != null && !limit.allow()) {
                    response.setHeader(HttpHeaders.RETRY_AFTER, "60");
                    writeError(mapper, response, HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again in a minute.");
                    return;
                }
            }

            if (path.equals("/api/v1/documents") || path.startsWith("/api/v1/documents/")
                    || path.equals("/api/v1/profile") || path.equals(PIN_PATH) || path.equals("/api/v1/auth/social/start")) {
                String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    try {
                        var identity = jwt.authenticate(authorization.substring(7));
                        users.findById(identity.id()).filter(user -> user.getPasswordVersion() == identity.passwordVersion())
                                .ifPresent(user -> SecurityContextHolder.getContext().setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated(identity.id(), null, List.of(
                                        new SimpleGrantedAuthority(user.isDoctor() ? "ROLE_DOCTOR" : "ROLE_PATIENT")))));
                    } catch (JwtException | IllegalArgumentException failure) {
                        writeError(mapper, response, HttpStatus.UNAUTHORIZED, "Your session expired. Sign in again.");
                        return;
                    }
                }
            }
            chain.doFilter(request, response);
        }
    }

    private static final class RateLimit {
        private final long refillNanos;
        private final long capacityNanos;
        private long credit;
        private long last = System.nanoTime();

        private RateLimit(int perMinute) {
            capacityNanos = TimeUnit.MINUTES.toNanos(1);
            refillNanos = capacityNanos / perMinute;
            credit = capacityNanos;
        }

        private synchronized boolean allow() {
            long now = System.nanoTime();
            credit = Math.min(capacityNanos, credit + Math.min(capacityNanos, now - last));
            last = now;
            if (credit < refillNanos) return false;
            credit -= refillNanos;
            return true;
        }
    }
}
