package wamddu.backend.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import wamddu.backend.global.response.ApiResponse;
import wamddu.backend.user.repository.UserRepository;
import wamddu.backend.user.service.DemoAccounts;

import java.util.List;

@RestController
@RequestMapping("/api/admin/tables")
@RequiredArgsConstructor
public class AdminTableController {
    private final AdminTableService service;
    private final UserRepository userRepository;
    private final DemoAccounts demoAccounts;

    public record UpdateRequest(List<AdminTableService.RowChange> changes,
                                List<java.util.Map<String, String>> creations,
                                List<AdminTableService.RowDeletion> deletions) {}

    @PatchMapping("/{table}")
    public ApiResponse<AdminTableService.TablePage> update(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable String table,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestBody UpdateRequest request) {
        demoAccounts.requireChangeable(AdminAccess.requireAdmin(principal, userRepository));
        return ApiResponse.success("변경 사항을 저장했습니다.", service.save(table, request.changes(), request.creations(), request.deletions(), page, size));
    }

    @GetMapping
    public ApiResponse<List<String>> tables(@AuthenticationPrincipal UserDetails principal) {
        AdminAccess.requireAdmin(principal, userRepository);
        return ApiResponse.success(service.tables());
    }

    @GetMapping("/{table}")
    public ApiResponse<AdminTableService.TablePage> read(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable String table,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        var admin = AdminAccess.requireAdmin(principal, userRepository);
        var result = service.read(table, page, size);
        return ApiResponse.success(demoAccounts.isDemo(admin) ? result.maskPersonalData() : result);
    }
}
