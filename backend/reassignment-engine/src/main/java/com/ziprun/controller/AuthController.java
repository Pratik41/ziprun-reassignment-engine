package com.ziprun.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in for the console (session cookie; see SecurityConfig).
 *
 * POST /auth/login  {username, password} -> 200 {username} | 401 | 429 (too many failures)
 * GET  /auth/me     -> 200 {username} when signed in, else 401
 * POST /auth/logout -> 204
 *
 * Brute force brake: 5 failed attempts from one address lock it out for 10 minutes.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(10);

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final boolean securityEnabled;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    private record Failures(int count, Instant since) {
    }

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                          @Value("${security.enabled:true}") boolean securityEnabled) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.securityEnabled = securityEnabled;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest body,
                                                     HttpServletRequest request, HttpServletResponse response) {
        String client = request.getRemoteAddr();
        Failures f = failures.get(client);
        if (f != null && f.count() >= MAX_FAILURES && f.since().plus(LOCKOUT).isAfter(Instant.now())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("message", "Too many failed sign-ins. Try again in a few minutes."));
        }

        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException e) {
            failures.merge(client, new Failures(1, Instant.now()), (old, one) ->
                old.since().plus(LOCKOUT).isBefore(Instant.now()) ? one : new Failures(old.count() + 1, old.since()));
            log.warn("Failed sign-in for '{}' from {}", body.username(), client);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Wrong username or password"));
        }
        failures.remove(client);

        // New session id on sign-in (session fixation), then store the authenticated context in it
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        log.info("'{}' signed in from {}", auth.getName(), client);
        return ResponseEntity.ok(Map.of("username", auth.getName()));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        if (!securityEnabled) {
            return ResponseEntity.ok(Map.of("username", "ops", "loginRequired", false));
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not signed in"));
        }
        return ResponseEntity.ok(Map.of("username", auth.getName(), "loginRequired", true));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}
