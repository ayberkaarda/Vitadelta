package com.vitadelta.api.identity;

import static com.vitadelta.api.identity.UserRepository.instant;

import com.vitadelta.api.common.paging.PageQuery;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.identity.IdentityApi.AdminUser;
import com.vitadelta.api.identity.IdentityApi.AdminUserPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** User administration (section 8.9): search and status changes. Never exposes password hashes. */
@Service
public class AdminUserService {

  private static final Logger LOG = LoggerFactory.getLogger(AdminUserService.class);

  private static final String SELECT =
      "SELECT u.id, u.email, u.role, u.status, u.email_verified_at, u.created_at,"
          + " coalesce(s.plan_code, 'FREE') AS plan_code"
          + " FROM app_user u"
          + " LEFT JOIN subscription s ON s.user_id = u.id AND s.status = 'ACTIVE'";

  private final JdbcClient jdbc;
  private final UserRepository users;
  private final RefreshTokenService refreshTokens;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public AdminUserService(
      JdbcClient jdbc,
      UserRepository users,
      RefreshTokenService refreshTokens,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.users = users;
    this.refreshTokens = refreshTokens;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  private static AdminUser map(ResultSet rs, int row) throws SQLException {
    return new AdminUser(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        Role.valueOf(rs.getString("role")),
        UserStatus.valueOf(rs.getString("status")),
        rs.getString("plan_code"),
        rs.getObject("email_verified_at") != null,
        instant(rs, "created_at"));
  }

  /** Users whose e-mail contains {@code query} (case-insensitive), newest first. */
  public AdminUserPage search(String query, PageQuery page) {
    String pattern = query == null ? null : "%" + escapeLike(query) + "%";
    String where = pattern == null ? "" : " WHERE u.email::text ILIKE ? ESCAPE '\\'";
    long total =
        (pattern == null
                ? jdbc.sql("SELECT count(*) FROM app_user u")
                : jdbc.sql("SELECT count(*) FROM app_user u" + where).param(pattern))
            .query(Long.class)
            .single();
    JdbcClient.StatementSpec statement =
        jdbc.sql(SELECT + where + " ORDER BY u.created_at DESC, u.id LIMIT ? OFFSET ?");
    if (pattern != null) {
      statement = statement.param(pattern);
    }
    List<AdminUser> items =
        statement.param(page.pageSize()).param(page.offset()).query(AdminUserService::map).list();
    return new AdminUserPage(page.meta(total), items);
  }

  static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /**
   * Sets the status; {@code DISABLED} revokes all refresh-token families of the user in the same
   * transaction (issued access tokens expire within 15 minutes).
   *
   * @param callerId the administrator making the change; an administrator cannot change their own
   *     status
   * @throws ProblemException 403 when {@code userId} is the caller
   * @throws ResponseStatusException 404 when the user does not exist
   */
  public AdminUser setStatus(UUID callerId, UUID userId, UserStatus status) {
    if (callerId.equals(userId)) {
      throw ProblemException.of(
          ProblemCode.FORBIDDEN, "An administrator cannot change their own status.");
    }
    transactions.executeWithoutResult(tx -> {
      Optional<UserAccount> user = users.findByIdForUpdate(userId);
      if (user.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      users.updateStatus(userId, status, clock.instant());
      if (status == UserStatus.DISABLED) {
        int revoked = refreshTokens.revokeAllForUser(userId);
        LOG.info("User disabled (userId={}, refreshTokensRevoked={})", userId, revoked);
      } else {
        LOG.info("User status set (userId={}, status={})", userId, status);
      }
    });
    return jdbc.sql(SELECT + " WHERE u.id = ?").param(userId).query(AdminUserService::map).single();
  }
}
