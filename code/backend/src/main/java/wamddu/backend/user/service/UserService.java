package wamddu.backend.user.service;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.global.security.JwtProvider;
import wamddu.backend.eventDirector.repository.EventDirectorRepository;
import wamddu.backend.user.domain.Role;
import wamddu.backend.user.domain.User;
import wamddu.backend.user.domain.UserStatus;
import wamddu.backend.user.dto.request.*;
import wamddu.backend.user.dto.response.*;
import wamddu.backend.user.repository.UserRepository;

import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final EventDirectorRepository eventDirectorRepository;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final EmailVerificationService emailVerificationService;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    public static String generateCustomerKey() {
        byte[] randomBytes = new byte[16];
        SECURE_RANDOM.nextBytes(randomBytes);

        String randomString = URL_ENCODER.encodeToString(randomBytes);

        return "customer_" + randomString;
    }

    @Transactional
    public SignupResponse signUp(SignupRequest request) {
        request.setEmail(EmailVerificationService.normalizeEmail(request.getEmail()));
        // 이메일 중복 검사
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.");
        }

        // 전화번호 중복 검사
        if (userRepository.existsByPhonenumber(request.getPhonenumber())) {
            throw new ApiException(HttpStatus.CONFLICT, "PHONENUMBER_ALREADY_EXISTS", "이미 등록된 전화번호입니다.");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setEmailVerifiedAt(emailVerificationService.consume(request.getEmail(), request.getEmailVerificationToken()));
        user.setPhonenumber(request.getPhonenumber());
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setCustomerKey(generateCustomerKey());
        User savedUser = userRepository.save(user);

        return SignupResponse.of("회원가입이 완료되었습니다.", UserInfoResponse.from(savedUser, false));
    }

    @Transactional
    public LoginResult login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail());

        if (user == null || user.getStatus() != UserStatus.ACTIVE || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String accessToken = jwtProvider.generateJwtToken(user.getId(), user.getRole().name());
        String refreshToken = jwtProvider.generateRefreshToken(user.getId());
        Claims refreshClaims = jwtProvider.parseRefreshToken(refreshToken);
        refreshTokenStore.save(refreshClaims.get("family_id", String.class), user.getId(), refreshToken,
                refreshClaims.getExpiration().toInstant());

        LoginResponse response = LoginResponse.builder()
                .message("로그인에 성공했습니다.")
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtProvider.getAccessExpiresIn())
                .user(toUserInfo(user))
                .build();

        return new LoginResult(response, refreshToken, refreshClaims.getExpiration().toInstant());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TokenReissueResult reissueToken(String refreshToken) {
        if (refreshToken == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_NOT_FOUND", "로그인 정보가 없습니다.");
        }

        Claims refreshClaims;
        try {
            refreshClaims = jwtProvider.parseRefreshToken(refreshToken);
        } catch (ExpiredJwtException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED", "로그인 정보가 만료되었습니다. 다시 로그인해 주세요.");
        } catch (JwtException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "유효하지 않은 로그인 정보입니다. 다시 로그인해 주세요.");
        }

        Long id = Long.parseLong(refreshClaims.getSubject());
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REVOKED", "로그인 정보가 폐기되었습니다."));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REVOKED", "로그인 정보가 폐기되었습니다.");
        }

        String newRefreshToken = jwtProvider.rotateRefreshToken(refreshClaims);
        if (!refreshTokenStore.rotate(refreshClaims.get("family_id", String.class), id, refreshToken, newRefreshToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REVOKED", "로그인 정보가 폐기되었습니다. 다시 로그인해 주세요.");
        }
        String newToken = jwtProvider.generateJwtToken(user.getId(), user.getRole().name());

        RefreshResponse response = RefreshResponse.builder()
                .accessToken(newToken)
                .tokenType("Bearer")
                .expiresIn(jwtProvider.getAccessExpiresIn())
                .build();

        return new TokenReissueResult(response, newRefreshToken, refreshClaims.getExpiration().toInstant());
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null) return;
        Claims claims;
        try {
            claims = jwtProvider.parseRefreshToken(refreshToken);
        } catch (JwtException | IllegalArgumentException invalid) {
            return; // Logout is idempotent even when the cookie is invalid or expired.
        }
        refreshTokenStore.revoke(claims.get("family_id", String.class));
    }

    public MyInfoResponse getMyInfo(UserDetails userDetails) {
        User user = userRepository.findById(Long.parseLong(userDetails.getUsername()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");
        }

        return MyInfoResponse.from(toUserInfo(user));
    }

    @Transactional
    public UpdateMyInfoResponse updateMyInfo(UpdateMyInfoRequest request, UserDetails userDetails) {
        User user = userRepository.findByIdForUpdate(Long.parseLong(userDetails.getUsername()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");
        }

        if (request.getEmail() != null && !request.getEmail().equalsIgnoreCase(user.getEmail())) {
            String email = EmailVerificationService.normalizeEmail(request.getEmail());
            if (userRepository.existsByEmail(email)) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.");
            }
            user.setEmailVerifiedAt(emailVerificationService.consume(email, request.getEmailVerificationToken()));
            user.setEmail(email);
        }

        if (request.getPhonenumber() != null) {
            if (userRepository.existsByPhonenumber(request.getPhonenumber())) {
                throw new ApiException(HttpStatus.CONFLICT, "PHONENUMBER_ALREADY_EXISTS", "이미 등록된 전화번호입니다.");
            }
            user.setPhonenumber(request.getPhonenumber());
        }

        if (request.getUsername() != null) {
            user.setUsername(request.getUsername());
        }

        User updatedUser = userRepository.save(user);

        return UpdateMyInfoResponse.of("회원 정보가 수정되었습니다.", toUserInfo(updatedUser));
    }

    private UserInfoResponse toUserInfo(User user) {
        return UserInfoResponse.from(user, eventDirectorRepository.existsByUserId(user.getId()));
    }

    @Transactional
    public MessageResponse updateMyPassword(ChangePasswordRequest request, UserDetails userDetails) {
        User user = userRepository.findByIdForUpdate(Long.parseLong(userDetails.getUsername()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");
        }

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD", "현재 비밀번호가 올바르지 않습니다.");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_AS_CURRENT_PASSWORD", "새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        }

        String newPassword = passwordEncoder.encode(request.getNewPassword());
        user.setPassword(newPassword);
        userRepository.save(user);
        refreshTokenStore.revokeAll(user.getId());

        return MessageResponse.from("비밀번호가 변경되었습니다.");
    }

    @Transactional
    public MessageResponse deleteMyAccount(DeleteAccountRequest request, UserDetails userDetails) {
        User user = userRepository.findByIdForUpdate(Long.parseLong(userDetails.getUsername()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "비밀번호가 올바르지 않습니다.");
        }

        user.setStatus(UserStatus.DELETED);
        user.setEmail("deleted_" + System.currentTimeMillis() + "_" + user.getEmail());
        if (user.getPhonenumber() != null) {
            user.setPhonenumber("deleted_" + System.currentTimeMillis() + "_" + user.getPhonenumber());
        }
        userRepository.save(user);
        refreshTokenStore.revokeAll(user.getId());
        return MessageResponse.from("회원 탈퇴가 완료되었습니다.");
    }
}
