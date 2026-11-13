package wamddu.backend.user.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.user.domain.User;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Accounts whose passwords are published for reviewers (app.demo.account-emails, comma separated).
 * Anyone can sign in with them, so they may use the service but not change the account or admin data,
 * and an admin demo account sees members' personal data masked.
 */
@Component
public class DemoAccounts {
    public static final String READ_ONLY = "DEMO_ACCOUNT_READ_ONLY";

    private final Set<String> emails;

    public DemoAccounts(@Value("${app.demo.account-emails:}") String emails) {
        this.emails = Arrays.stream(emails.split(","))
                .map(String::trim)
                .filter(email -> !email.isEmpty())
                .map(EmailVerificationService::normalizeEmail)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isDemo(String email) {
        return email != null && emails.contains(EmailVerificationService.normalizeEmail(email));
    }

    public boolean isDemo(User user) {
        return user != null && isDemo(user.getEmail());
    }

    public void requireChangeable(User user) {
        if (isDemo(user)) {
            throw readOnly();
        }
    }

    public void requireChangeable(String email) {
        if (isDemo(email)) {
            throw readOnly();
        }
    }

    private static ApiException readOnly() {
        return new ApiException(HttpStatus.FORBIDDEN, READ_ONLY,
                "체험 계정은 조회와 예매만 할 수 있습니다. 계정 정보와 관리자 데이터는 변경할 수 없습니다.");
    }
}
