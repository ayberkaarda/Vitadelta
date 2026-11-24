package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.support.TestData;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Admin user endpoints and RBAC on every {@code /admin/**} route of the contract. */
class AdminUsersIntegrationTest extends IdentityIntegrationTest {

  private record Route(HttpMethod method, String path) {}

  private static List<Route> adminRoutes() throws Exception {
    JsonNode paths =
        YAMLMapper.builder()
            .build()
            .readTree(
                Files.readString(
                    TestData.dataDir().getParent().resolve("contracts/public-api.openapi.yaml")))
            .get("paths");
    List<Route> routes = new ArrayList<>();
    for (String path : paths.propertyNames()) {
      if (!path.startsWith("/admin")) {
        continue;
      }
      for (String method : paths.get(path).propertyNames()) {
        if (List.of("get", "post", "put", "patch", "delete").contains(method)) {
          String concrete = "/api/v1" + path.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
          routes.add(new Route(HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)), concrete));
        }
      }
    }
    return routes;
  }

  private EntityExchangeResult<String> call(Route route, String token) {
    var spec = client.method(route.method()).uri(route.path()).header("X-Forwarded-For", ip);
    if (token != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    if (route.method() == HttpMethod.POST) {
      return spec.contentType(MediaType.APPLICATION_JSON)
          .body("{}")
          .exchange()
          .returnResult(String.class);
    }
    return spec.exchange().returnResult(String.class);
  }

  @Test
  void everyAdminRouteRequiresTheAdminRole() throws Exception {
    List<Route> routes = adminRoutes();
    assertThat(routes).hasSizeGreaterThanOrEqualTo(12);
    String email = uniqueEmail();
    registerVerified(email);
    String user = login(email, PASSWORD).accessToken();
    String admin = adminToken();
    for (Route route : routes) {
      assertProblem(call(route, null), 401, "unauthorized");
      assertProblem(call(route, user), 403, "forbidden");
      assertThat(call(route, admin).getStatus().value()).as(route.toString()).isNotIn(401, 403);
    }
  }

  @Test
  void listSearchesByEmailAndPages() {
    String marker = "find" + UUID.randomUUID().toString().substring(0, 8);
    String first = marker + ".a@example.org";
    String second = marker + ".b@example.org";
    registerVerified(first);
    clock.advance(java.time.Duration.ofSeconds(1));
    register(second, PASSWORD);
    String admin = adminToken();

    EntityExchangeResult<String> page =
        get("/api/v1/admin/users?q=" + marker.toUpperCase(Locale.ROOT) + "&pageSize=1", admin);
    assertThat(page.getStatus().value()).as(page.getResponseBody()).isEqualTo(200);
    ContractSchemas.assertConforms("AdminUserPage", page.getResponseBody());
    JsonNode body = json(page);
    assertThat(body.get("meta").get("total").asInt()).isEqualTo(2);
    assertThat(body.get("meta").get("pageSize").asInt()).isEqualTo(1);
    assertThat(body.get("items").get(0).get("email").asString()).isEqualTo(second);
    assertThat(body.get("items").get(0).get("emailVerified").asBoolean()).isFalse();
    assertThat(body.get("items").get(0).get("plan").asString()).isEqualTo("FREE");
    assertThat(page.getResponseBody()).doesNotContain("argon2").doesNotContain("password");

    JsonNode secondPage =
        json(get("/api/v1/admin/users?q=" + marker + "&page=2&pageSize=1", admin));
    assertThat(secondPage.get("items").get(0).get("email").asString()).isEqualTo(first);
    assertThat(secondPage.get("items").get(0).get("emailVerified").asBoolean()).isTrue();

    assertThat(json(get("/api/v1/admin/users?q=%25_%25", admin)).get("items").size()).isZero();
    JsonNode all = json(get("/api/v1/admin/users", admin));
    assertThat(all.get("meta").get("total").asInt()).isGreaterThanOrEqualTo(3);
    assertProblem(get("/api/v1/admin/users?q=", admin), 422, "validation_failed");
    assertProblem(get("/api/v1/admin/users?pageSize=101", admin), 422, "validation_failed");
  }

  @Test
  void statusChangesValidateAndUnknownUsersAre404() {
    String admin = adminToken();
    assertProblem(
        post(
            "/api/v1/admin/users/" + UUID.randomUUID() + "/status",
            body(Map.of("status", "DISABLED")),
            HttpHeaders.AUTHORIZATION,
            "Bearer " + admin),
        404,
        "about:blank");
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    assertProblem(
        post(
            "/api/v1/admin/users/" + id + "/status",
            body(Map.of("status", "GONE")),
            HttpHeaders.AUTHORIZATION,
            "Bearer " + admin),
        422,
        "validation_failed");
    assertProblem(
        post(
            "/api/v1/admin/users/" + id + "/status",
            "{}",
            HttpHeaders.AUTHORIZATION,
            "Bearer " + admin),
        422,
        "validation_failed");
    EntityExchangeResult<String> active =
        post(
            "/api/v1/admin/users/" + id + "/status",
            body(Map.of("status", "ACTIVE")),
            HttpHeaders.AUTHORIZATION,
            "Bearer " + admin);
    assertThat(active.getStatus().value()).isEqualTo(200);
    ContractSchemas.assertConforms("AdminUser", active.getResponseBody());
  }
}
