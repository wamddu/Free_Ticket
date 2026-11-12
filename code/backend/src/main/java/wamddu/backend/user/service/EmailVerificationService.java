package wamddu.backend.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.user.domain.EmailVerification;
import wamddu.backend.user.domain.EmailVerification.Purpose;
import wamddu.backend.user.repository.EmailVerificationRepository;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {
    private final EmailVerificationRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<JavaMailSender> mailSender;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ArrayDeque<Instant> recentSends = new ArrayDeque<>();
    @Value("${app.mail.from:}") private String from;
    @Value("${app.mail.sender-name:FreeTicket}") private String senderName;
    @Value("${spring.mail.password:}") private String mailPassword;

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public String send(String email) {
        return send(email, Purpose.EMAIL_VERIFICATION);
    }

    @Transactional
    public String send(String email, Purpose purpose) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || from.isBlank() || mailPassword.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_NOT_CONFIGURED", "메일 발송 설정이 필요합니다.");
        }
        email = normalizeEmail(email);
        Instant now = Instant.now();
        var verification = repository.findForUpdate(email).orElseGet(EmailVerification::new);
        if (verification.getSentAt() != null && verification.getSentAt().plusSeconds(60).isAfter(now)) {
            throw rateLimited();
        }
        if (verification.getWindowStartedAt() == null || !verification.getWindowStartedAt().plusSeconds(3600).isAfter(now)) {
            verification.setWindowStartedAt(now);
            verification.setSendCount(0);
        }
        if (verification.getSendCount() >= 5) throw rateLimited();
        reserveSend(now);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        verification.setEmail(email);
        verification.setPurpose(purpose);
        verification.setTokenHash(hash(token));
        verification.setCodeHash(passwordEncoder.encode(code));
        verification.setExpiresAt(now.plusSeconds(300));
        verification.setSentAt(now);
        verification.setSendCount(verification.getSendCount() + 1);
        verification.setFailedAttempts(0);
        verification.setVerifiedAt(null);
        verification.setConsumedAt(null);
        try {
            repository.saveAndFlush(verification);
        } catch (org.springframework.dao.DataIntegrityViolationException concurrentSend) {
            throw rateLimited();
        }

        var message = new SimpleMailMessage();
        message.setFrom(senderName + " <" + from + ">");
        message.setTo(email);
        String subject = purpose == Purpose.PASSWORD_RESET ? "비밀번호 재설정 인증번호" : "이메일 인증번호";
        message.setSubject("[FreeTicket] " + subject);
        message.setText("FreeTicket " + subject + ": " + code + "\n5분 이내에 입력해 주세요.\n요청하지 않았다면 이 메일을 무시해 주세요.");
        try {
            sender.send(message);
        } catch (MailException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_SEND_FAILED", "메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        return token;
    }

    // Failed code attempts must survive the error response so guessing cannot reset the counter.
    @Transactional(noRollbackFor = ApiException.class)
    public void verify(String email, String token, String code) {
        verify(email, token, code, Purpose.EMAIL_VERIFICATION);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void verify(String email, String token, String code, Purpose purpose) {
        var verification = requireValid(email, token, purpose);
        if (verification.getVerifiedAt() != null) return;
        if (verification.getFailedAttempts() >= 5) throw invalidProof();
        if (!passwordEncoder.matches(code, verification.getCodeHash())) {
            verification.setFailedAttempts(verification.getFailedAttempts() + 1);
            throw invalidProof();
        }
        verification.setVerifiedAt(Instant.now());
    }

    // Consume together with the account write: a failed signup must not lose its valid proof.
    @Transactional(propagation = Propagation.MANDATORY)
    public Instant consume(String email, String token) {
        return consume(email, token, Purpose.EMAIL_VERIFICATION);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Instant consume(String email, String token, Purpose purpose) {
        var verification = requireValid(email, token, purpose);
        if (verification.getVerifiedAt() == null) throw invalidProof();
        verification.setConsumedAt(Instant.now());
        return verification.getVerifiedAt();
    }

    private EmailVerification requireValid(String email, String token, Purpose purpose) {
        if (token == null || token.length() != 43) throw invalidProof();
        var verification = repository.findForUpdate(normalizeEmail(email)).orElseThrow(EmailVerificationService::invalidProof);
        if (verification.getPurpose() != purpose || !verification.getEmail().equals(normalizeEmail(email))
                || verification.getConsumedAt() != null || !verification.getExpiresAt().isAfter(Instant.now())
                || !MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                    verification.getTokenHash().getBytes(StandardCharsets.US_ASCII))) throw invalidProof();
        return verification;
    }

    private synchronized void reserveSend(Instant now) {
        // 시간당 발송 한도는 이 서버 안에서만 센다. 서버를 여러 대로 늘리기 전에 공유 저장소 기반 제한으로 바꾼다.
        while (!recentSends.isEmpty() && !recentSends.getFirst().plusSeconds(3600).isAfter(now)) recentSends.removeFirst();
        if (recentSends.size() >= 30) throw rateLimited();
        recentSends.addLast(now);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ApiException invalidProof() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_VERIFICATION", "이메일 인증이 유효하지 않습니다. 인증번호를 확인하거나 다시 발급받아 주세요.");
    }

    private static ApiException rateLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_RATE_LIMITED", "인증 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }
}
