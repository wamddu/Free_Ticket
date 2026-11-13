package wamddu.backend.admin;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import wamddu.backend.global.response.ApiResponse;
import wamddu.backend.user.repository.UserRepository;
import wamddu.backend.user.service.DemoAccounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/admin/payment-reconciliation/issues")
@RequiredArgsConstructor
public class AdminPaymentReconciliationController {
    private final AdminPaymentReconciliationService service;
    private final UserRepository users;
    private final AdminPaymentReconciliationActions actions;
    private final DemoAccounts demoAccounts;

    @GetMapping
    public ApiResponse<AdminPaymentReconciliationService.IssuePage> list(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String issueType,
            @RequestParam(required = false) String resolution,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AdminAccess.requireAdmin(principal, users);
        return ApiResponse.success(service.list(orderId, issueType, resolution, from, to, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminPaymentReconciliationService.IssueDetail> detail(
            @AuthenticationPrincipal UserDetails principal, @PathVariable long id) {
        AdminAccess.requireAdmin(principal, users);
        return ApiResponse.success(service.detail(id));
    }

    public record ActionRequest(@NotBlank @Size(max = 500) String reason) {}

    @PostMapping("/{id}/{operation}")
    public ApiResponse<AdminPaymentReconciliationService.IssueDetail> perform(
            @AuthenticationPrincipal UserDetails principal, @PathVariable long id,
            @PathVariable String operation, @Valid @RequestBody ActionRequest request) {
        demoAccounts.requireChangeable(AdminAccess.requireAdmin(principal, users));
        return ApiResponse.success(actions.perform(id, Long.parseLong(principal.getUsername()), operation, request.reason()));
    }
}
