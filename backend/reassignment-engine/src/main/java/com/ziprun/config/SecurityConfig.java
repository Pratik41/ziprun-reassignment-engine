package com.ziprun.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Who may use the API.
 *
 *  - Ops (the console, or curl): sign in with POST /auth/login and get a session
 *    cookie. Credentials come from OPS_USERNAME / OPS_PASSWORD. HTTP Basic also works
 *    for scripts. Everything except /auth/** needs ROLE_OPS.
 *  - Agents' phone apps: POST /agents/{id}/heartbeat with header X-Agent-Token =
 *    AGENT_APP_TOKEN (ROLE_AGENT_APP, heartbeats only). Unset = only ops can send them.
 *  - CSRF: the session cookie is protected by the double-submit pattern. The API sets
 *    an XSRF-TOKEN cookie; the console echoes it in X-XSRF-TOKEN on every write
 *    (Angular's HttpClient does this by itself). Header-authenticated calls (Basic,
 *    agent token) can't be forged cross-site, so they skip it.
 *  - Unauthenticated calls get a bare 401 (no WWW-Authenticate), so browsers never
 *    show their own login prompt or cache Basic credentials.
 *
 * security.enabled=false turns all of this off (the test suite uses it; never in production).
 */
@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    static final String DEFAULT_PASSWORD = "ziprun";
    public static final String AGENT_TOKEN_HEADER = "X-Agent-Token";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            @Value("${security.enabled:true}") boolean enabled,
                                            @Value("${agents.app-token:}") String agentAppToken) throws Exception {
        http.cors(Customizer.withDefaults());
        // Write security headers before the request is handled, on the request thread. By default they're
        // written when the response commits, which for the SSE endpoints happens on the background thread
        // sending the first event, racing the request thread on the same header map.
        http.headers(headers -> headers.addObjectPostProcessor(new ObjectPostProcessor<HeaderWriterFilter>() {
            @Override
            public <O extends HeaderWriterFilter> O postProcess(O filter) {
                filter.setShouldWriteHeadersEagerly(true);
                return filter;
            }
        }));
        if (!enabled) {
            log.warn("security.enabled=false: the API is open to anyone who can reach it");
            return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
        }

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/agents/*/heartbeat").hasAnyRole("OPS", "AGENT_APP")
                .anyRequest().hasRole("OPS"))
            .httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable()) // POST /auth/logout (AuthController)
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                .ignoringRequestMatchers(
                    request -> request.getHeader("Authorization") != null || request.getHeader(AGENT_TOKEN_HEADER) != null)
                // signing in needs no token: there is no session to ride on yet
                .ignoringRequestMatchers("/auth/login"))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterBefore(new AgentTokenFilter(agentAppToken), BasicAuthenticationFilter.class)
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin())); // H2 console, when enabled
        return http.build();
    }

    @Bean
    UserDetailsService users(@Value("${ops.username:ops}") String username,
                             @Value("${ops.password:" + DEFAULT_PASSWORD + "}") String password,
                             PasswordEncoder encoder) {
        if (DEFAULT_PASSWORD.equals(password)) {
            log.warn("Ops login is '{}' with the DEFAULT password. Set OPS_PASSWORD before exposing this anywhere.", username);
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
            .password(encoder.encode(password)).roles("OPS").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Spring Security's recipe for single-page apps: BREACH-safe (xor) tokens when
     * rendering, but accept the raw cookie value the SPA echoes back in the header.
     */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            xor.handle(request, response, csrfToken);
            csrfToken.get(); // render the token now, so the XSRF-TOKEN cookie is set
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String header = request.getHeader(csrfToken.getHeaderName());
            return StringUtils.hasText(header)
                ? plain.resolveCsrfTokenValue(request, csrfToken)
                : xor.resolveCsrfTokenValue(request, csrfToken);
        }
    }

    /** Makes sure every response carries the XSRF-TOKEN cookie (tokens are loaded lazily otherwise). */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    /** X-Agent-Token on a heartbeat = the agent's phone app (ROLE_AGENT_APP). */
    static final class AgentTokenFilter extends OncePerRequestFilter {
        private final byte[] expected;

        AgentTokenFilter(String token) {
            this.expected = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String presented = request.getHeader(AGENT_TOKEN_HEADER);
            if (presented != null && expected != null && request.getRequestURI().endsWith("/heartbeat")
                && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
                var auth = new UsernamePasswordAuthenticationToken("agent-app", null,
                    AuthorityUtils.createAuthorityList("ROLE_AGENT_APP"));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }
    }
}
