package wamddu.backend.admin;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.user.domain.Role;
import wamddu.backend.user.domain.User;
import wamddu.backend.user.domain.UserStatus;
import wamddu.backend.user.repository.UserRepository;

final class AdminAccess {
    private AdminAccess() {}

    static User requireAdmin(UserDetails principal, UserRepository users) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
        }
        // A revoked or inactive administrator must not retain access through an old token.
        var user = users.findById(Long.parseLong(principal.getUsername()));
        if (user.isEmpty() || user.get().getRole() != Role.ADMIN || user.get().getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "관리자만 조회할 수 있습니다.");
        }
        return user.get();
    }
}
