package wamddu.backend.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.user.domain.EmailVerification.Purpose;
import wamddu.backend.user.domain.UserStatus;
import wamddu.backend.user.repository.UserRepository;
import wamddu.backend.user.validation.PasswordPolicy;

@Service
@RequiredArgsConstructor
public class PasswordRecoveryService {
    private final EmailVerificationService verification;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final RefreshTokenStore refreshTokens;
    private final DemoAccounts demoAccounts;

    @Transactional
    public void reset(String email, String token, String password, String confirmation) {
        if (!PasswordPolicy.isValid(password)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", PasswordPolicy.MESSAGE);
        }
        if (!password.equals(confirmation)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "비밀번호 확인이 일치하지 않습니다.");
        }
        email = EmailVerificationService.normalizeEmail(email);
        demoAccounts.requireChangeable(email);
        // All account writes lock user before verification, preventing stale profile writes and lock inversion.
        var user = users.findByEmail(email);
        // Verification consumption and password update commit together; failures leave the proof usable.
        verification.consume(email, token, Purpose.PASSWORD_RESET);
        if (user == null || user.getStatus() != UserStatus.ACTIVE
                || !EmailVerificationService.normalizeEmail(user.getEmail()).equals(email)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_RESET_UNAVAILABLE", "이 이메일로 비밀번호를 변경할 수 없습니다. 가입한 이메일인지 확인해 주세요.");
        }
        if (encoder.matches(password, user.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_AS_CURRENT_PASSWORD", "새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        }
        user.setPassword(encoder.encode(password));
        refreshTokens.revokeAll(user.getId());
    }
}
