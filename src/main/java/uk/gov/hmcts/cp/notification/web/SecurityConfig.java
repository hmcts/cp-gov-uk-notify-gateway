package uk.gov.hmcts.cp.notification.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// The gateway has no business HTTP surface: commands arrive on Azure Service Bus and outcomes leave as
// result events. The notification query API was removed (ADR-002 D7), so HTTP is deny-by-default and only
// the actuator probe, info and metrics endpoints are reachable. Any endpoint added later is refused
// until it is explicitly permitted here with its own authentication decision.
//
// CSRF is left at Spring Security's default (enabled). Nothing state-changing is served; a future
// state-changing endpoint must configure CSRF explicitly for this stateless service.
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
