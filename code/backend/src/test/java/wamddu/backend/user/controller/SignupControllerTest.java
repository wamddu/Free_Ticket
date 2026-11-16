package wamddu.backend.user.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.global.exception.ApiExceptionHandler;
import wamddu.backend.order.controller.OrderController;
import wamddu.backend.order.service.OrderService;
import wamddu.backend.payment.controller.PaymentController;
import wamddu.backend.payment.service.PaymentService;
import wamddu.backend.queue.QueueGate;
import wamddu.backend.user.dto.request.SignupRequest;
import wamddu.backend.user.dto.response.SignupResponse;
import wamddu.backend.user.dto.response.UserInfoResponse;
import wamddu.backend.user.service.UserService;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SignupControllerTest {
    @Mock private UserService userService;
    @Mock private OrderService orderService;
    @Mock private PaymentService paymentService;
    @Mock private QueueGate queueGate;
    private MockMvc mvc;
    private static final String VALID = """
            {"username":"홍길동","password":"password123!","email":"user@example.com","phonenumber":"01012345678","emailVerificationToken":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
            """;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UserController(userService),
                        new OrderController(orderService, queueGate), new PaymentController(paymentService))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void invalidFieldsReturnAllRequiredMessages() throws Exception {
        assertAllFieldsInvalid("""
                {"username":"홍","password":"short","email":"invalid","phonenumber":"010"}
                """);
    }

    @Test
    void missingFieldsReturnAllRequiredMessages() throws Exception {
        assertAllFieldsInvalid("{}");
    }

    @Test
    void explicitNullFieldsReturnAllRequiredMessages() throws Exception {
        assertAllFieldsInvalid("""
                {"username":null,"password":null,"email":null,"phonenumber":null}
                """);
    }

    private void assertAllFieldsInvalid(String body) throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("입력값을 확인해 주세요."))
                .andExpect(jsonPath("$.errors", aMapWithSize(5)))
                .andExpect(jsonPath("$.errors.username").value("사용자 이름은 2자 이상이어야 합니다."))
                .andExpect(jsonPath("$.errors.password").value(wamddu.backend.user.validation.PasswordPolicy.MESSAGE))
                .andExpect(jsonPath("$.errors.email").value("올바른 이메일 형식이 아닙니다."))
                .andExpect(jsonPath("$.errors.phonenumber").value("올바른 전화번호 형식이 아닙니다."));
        verifyNoInteractions(userService);
    }

    @Test
    void onlyInvalidFieldsAreReturned() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("user@example.com", "invalid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", aMapWithSize(1)))
                .andExpect(jsonPath("$.errors.email").value("올바른 이메일 형식이 아닙니다."));
        verifyNoInteractions(userService);
    }

    @Test
    void validSignupKeepsCreatedResponse() throws Exception {
        when(userService.signUp(any(SignupRequest.class))).thenReturn(SignupResponse.of(
                "회원가입이 완료되었습니다.", UserInfoResponse.builder().id(1L).username("홍길동").build()));
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
                .andExpect(jsonPath("$.user.id").value(1))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verify(userService).signUp(any(SignupRequest.class));
    }

    @Test
    void duplicateErrorsKeepExistingResponse() throws Exception {
        for (String code : new String[]{"EMAIL_ALREADY_EXISTS", "PHONENUMBER_ALREADY_EXISTS"}) {
            when(userService.signUp(any(SignupRequest.class)))
                    .thenThrow(new ApiException(HttpStatus.CONFLICT, code, "중복 정보입니다."));
            mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(VALID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.errors").doesNotExist());
            reset(userService);
        }
    }

    @Test
    void orderAndPaymentValidationKeepExistingResponse() throws Exception {
        for (String path : new String[]{"/api/orders", "/api/payments/confirm"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("요청 값을 확인해 주세요."))
                    .andExpect(jsonPath("$.errors").doesNotExist());
        }
        verifyNoInteractions(orderService, paymentService);
    }
}
