package com.vitadelta.api.config;

import com.vitadelta.api.common.problem.ProblemSecurityHandlers;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * The single HTTP security filter chain of the API (project specification sections 8.1, 8.4, 9.1,
 * 15.2 and 15.4).
 *
 * <p>Route matrix (base path {@code /api/v1}):
 *
 * <ul>
 *   <li>public: {@code GET /health}, {@code /auth/**}, {@code /plans}, {@code /session/quota},
 *       {@code /countries}, {@code /nutrients}, {@code /weights/defaults}, {@code /legal}, {@code
 *       /fx/**}, {@code /products/**}, {@code /recommendations/**} ({@link #PUBLIC_PATHS});
 *   <li>authenticated (USER or ADMIN bearer token): {@code /me} and {@code /me/**};
 *   <li>role ADMIN: {@code /admin/**};
 *   <li>Actuator {@code health} and {@code metrics} on the separate management port only;
 *   <li>everything else is denied ({@code 401} without credentials, {@code 403} with).
 * </ul>
 *
 * Adding a route under these prefixes needs no change here; a new top-level prefix is a change of
 * {@link #PUBLIC_PATHS} (requested from the owner of this file).
 *
 * <p><b>Bearer tokens:</b> the identity package provides the {@link JwtDecoder} bean (HS256 over
 * the current and previous secret, selected by {@code kid}). When such a bean exists, the
 * resource-server filter authenticates {@code Authorization: Bearer} tokens; the {@code role} claim
 * becomes the authority {@code ROLE_<role>} and {@code sub} the principal name. Without the bean
 * (before the identity package is present) bearer tokens are not evaluated and every protected
 * route answers {@code 401}.
 *
 * <p>Stateless: no session, no login form, no basic authentication, no CSRF tokens (the
 * cookie-scoped auth endpoints carry their own {@code X-Requested-With} and Origin checks, §8.1).
 * Responses carry {@code X-Content-Type-Options: nosniff}, {@code Referrer-Policy: no-referrer},
 * {@code Content-Security-Policy: default-src 'none'; frame-ancestors 'none'}, {@code
 * X-Frame-Options: DENY} and {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate}
 * unless a handler sets its own cache headers; every {@code /auth/**} response carries exactly
 * {@code Cache-Control: no-store}, whatever the handler set. HSTS is sent by nginx only (§15.2).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class SecurityConfig {

  /** Base path of the public API. */
  public static final String API = "/api/v1";

  /** Paths open to every principal (any method). */
  public static final String[] PUBLIC_PATHS = {
    API + "/auth/**",
    API + "/plans",
    API + "/session/quota",
    API + "/countries",
    API + "/nutrients",
    API + "/weights/defaults",
    API + "/legal",
    API + "/fx/**",
    API + "/products/**",
    API + "/recommendations/**"
  };

  /** Content security policy of every API response. */
  public static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

  /** JWT claim that carries the role ({@code USER} or {@code ADMIN}). */
  public static final String ROLE_CLAIM = "role";

  @Bean
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http,
      ObjectProvider<JwtDecoder> jwtDecoder,
      CorsConfigurationSource corsConfigurationSource,
      ManagementEndpointRequestMatcher managementEndpoints)
      throws Exception {
    AuthenticationEntryPoint entryPoint = ProblemSecurityHandlers.authenticationEntryPoint();
    AccessDeniedHandler deniedHandler = ProblemSecurityHandlers.accessDeniedHandler();
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .anonymous(anonymous -> {})
        .cors(AbstractHttpConfigurer::disable)
        .addFilter(corsFilter(corsConfigurationSource))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(headers ->
            headers
                .contentTypeOptions(options -> {})
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer ->
                    referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .httpStrictTransportSecurity(hsts -> hsts.disable())
                .addHeaderWriter(
                    new DelegatingRequestMatcherHeaderWriter(
                        PathPatternRequestMatcher.withDefaults().matcher(API + "/auth/**"),
                        (request, response) ->
                            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store"))))
        .exceptionHandling(exceptions ->
            exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
        .authorizeHttpRequests(authorize ->
            authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR)
                .permitAll()
                .requestMatchers(managementEndpoints)
                .permitAll()
                .requestMatchers(HttpMethod.GET, API + "/health")
                .permitAll()
                .requestMatchers(PUBLIC_PATHS)
                .permitAll()
                .requestMatchers(API + "/me/**")
                .authenticated()
                .requestMatchers(API + "/admin/**")
                .hasRole("ADMIN")
                .anyRequest()
                .denyAll());
    JwtDecoder decoder = jwtDecoder.getIfUnique();
    if (decoder != null) {
      http.oauth2ResourceServer(resourceServer ->
          resourceServer
              .authenticationEntryPoint(entryPoint)
              .accessDeniedHandler(deniedHandler)
              .jwt(jwt ->
                  jwt.decoder(decoder).jwtAuthenticationConverter(jwtAuthenticationConverter())));
    }
    return http.build();
  }

  /**
   * The CORS filter at the standard position of the chain; rejected cross-origin requests get the
   * {@code 403} {@code forbidden} problem body.
   */
  static CorsFilter corsFilter(CorsConfigurationSource source) {
    CorsFilter filter = new CorsFilter(source);
    filter.setCorsProcessor(ProblemSecurityHandlers.corsProcessor());
    return filter;
  }

  /** Maps the {@code role} claim to {@code ROLE_<role>}; {@code sub} is the principal name. */
  static JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName(ROLE_CLAIM);
    authorities.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }
}
