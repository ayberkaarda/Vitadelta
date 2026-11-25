package com.vitadelta.api.identity;

import com.vitadelta.api.common.paging.PageQuery;
import com.vitadelta.api.identity.IdentityApi.AdminUser;
import com.vitadelta.api.identity.IdentityApi.AdminUserPage;
import com.vitadelta.api.identity.IdentityApi.SetUserStatusRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/users} (section 8.9). Requires role ADMIN (route matrix and method
 * security). The subscription grant endpoint belongs to the billing module.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

  private final AdminUserService users;

  public AdminUserController(AdminUserService users) {
    this.users = users;
  }

  @GetMapping
  AdminUserPage list(
      @RequestParam(required = false)
          @Size(min = 1, max = 254)
          @Pattern(regexp = IdentityApi.NO_CONTROL_CHARACTERS)
          String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer pageSize) {
    return users.search(q, PageQuery.of(page, pageSize));
  }

  @PostMapping("/{id}/status")
  AdminUser setStatus(
      @PathVariable UUID id,
      @Valid @RequestBody SetUserStatusRequest body,
      Authentication authentication) {
    return users.setStatus(CurrentUser.require(authentication), id, body.status());
  }
}
