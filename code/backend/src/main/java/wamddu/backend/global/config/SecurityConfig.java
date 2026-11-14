package wamddu.backend.global.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;
import wamddu.backend.global.response.ErrorResponse;
import wamddu.backend.global.security.JwtAuthenticationFilter;
import wamddu.backend.global.security.JwtProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final ObjectMapper errorJson = new ObjectMapper();

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf((csrf) -> csrf.disable())
                .cors(corsConfig -> corsConfig.configurationSource(new CorsConfigurationSource() {

                    @Override
                    public @Nullable CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
                        CorsConfiguration config = new CorsConfiguration();
                        config.setAllowedOriginPatterns(List.of(
                                "http://localhost:*",
                                "http://127.0.0.1:*"
                        ));
                        config.setAllowedMethods(Collections.singletonList("*"));
                        config.setAllowedHeaders(Collections.singletonList("*"));
                        config.setAllowCredentials(true);
                        config.setMaxAge(3600L);

                        return config;
                    }
                }))
                .formLogin((login) -> login.disable())
                .httpBasic((basic) -> basic.disable())
                .sessionManagement(
                        (session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(response,
                                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, exception) -> writeError(response,
                                HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.")))
                .authorizeHttpRequests((auth) -> auth
                        // Exceptions are re-dispatched to /error without the JWT; authorizing it would turn every error into 403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Served only on the management port (8081), which is not published outside Docker.
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/events/{eventId}/tickets/{ticketId}/price-history").permitAll()
                        .requestMatchers("/api/auth/email-verifications", "/api/auth/email-verifications/verify").permitAll()
                        .requestMatchers("/api/auth/password-resets", "/api/auth/password-resets/email",
                                "/api/auth/password-resets/email/verify").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/signup", "/api/auth/refresh", "/api/auth/logout",
                                "/api/events/*", "/api/events").permitAll()
                        .anyRequest().authenticated()
                )

                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtProvider),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(errorJson.writeValueAsString(ErrorResponse.of(code, message)));
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
