package wamddu.backend.order.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.global.exception.ApiExceptionHandler;
import wamddu.backend.queue.QueueGate;
import wamddu.backend.order.dto.request.CreateOrderRequest;
import wamddu.backend.order.dto.response.CheckoutOrderResponse;
import wamddu.backend.order.dto.response.ReservationHistoryResponse;
import wamddu.backend.order.dto.response.ReservationListResponse;
import wamddu.backend.order.service.OrderService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    private MockMvc mockMvc;

    @Mock
    private OrderService orderService;

    @Mock

    private QueueGate queueGate;


    @InjectMocks
    private OrderController orderController;

    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(orderController)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        userDetails = new User("1", "password", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("주문 생성 API - 성공 응답 포맷 (ApiResponse) 검증")
    void createOrder_Success_ReturnsApiResponse() throws Exception {
        // given
        CheckoutOrderResponse mockResponse = new CheckoutOrderResponse(
                "ORD-20260907-TEST1234",
                "뮤지컬 햄릿 - VIP석",
                300000L,
                2,
                "customer_12345",
                "홍길동",
                "user@example.com",
                LocalDateTime.now().plusMinutes(10)
        );

        given(orderService.createOrder(any(CreateOrderRequest.class), eq(1L)))
                .willReturn(mockResponse);

        String requestJson = """
                {
                    "ticketId": 100,
                    "quantity": 2
                }
                """;

        // when & then
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("주문이 생성되었습니다."))
                .andExpect(jsonPath("$.data.orderId").value("ORD-20260907-TEST1234"))
                .andExpect(jsonPath("$.data.amount").value(300000))
                .andExpect(jsonPath("$.data.quantity").value(2));
    }

    @Test
    @DisplayName("체크아웃 주문 조회 API - 성공 응답 검증")
    void getCheckoutOrder_Success() throws Exception {
        // given
        CheckoutOrderResponse mockResponse = new CheckoutOrderResponse(
                "ORD-20260907-TEST1234",
                "뮤지컬 햄릿 - VIP석",
                150000L,
                1,
                "customer_12345",
                "홍길동",
                "user@example.com",
                LocalDateTime.now().plusMinutes(5)
        );

        given(orderService.getCheckoutOrder("ORD-20260907-TEST1234", 1L))
                .willReturn(mockResponse);

        // when & then
        mockMvc.perform(get("/api/orders/ORD-20260907-TEST1234/checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("결제할 주문을 조회했습니다."))
                .andExpect(jsonPath("$.data.orderId").value("ORD-20260907-TEST1234"))
                .andExpect(jsonPath("$.data.amount").value(150000));
    }

    @Test
    @DisplayName("체크아웃 주문 조회 API - 주문 미존재 예외 발생 시 ErrorResponse 포맷 검증")
    void getCheckoutOrder_NotFound_ReturnsErrorResponse() throws Exception {
        // given
        given(orderService.getCheckoutOrder("ORD-NOT-EXIST", 1L))
                .willThrow(new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));

        // when & then
        mockMvc.perform(get("/api/orders/ORD-NOT-EXIST/checkout"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("주문을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("내 예매 내역 조회 API - ApiResponse 반환 검증")
    void getMyReservations_Success() throws Exception {
        // given
        ReservationHistoryResponse history = new ReservationHistoryResponse(
                "ORD-20260907-PAID",
                101L,
                "햄릿 뮤지컬",
                "http://example.com/main.jpg",
                "세종문화회관",
                "VIP석",
                LocalDateTime.now(),
                1,
                150000L,
                LocalDateTime.now(),
                "카드",
                "http://example.com/receipt",
                "PAID"
        );

        given(orderService.getMyReservations(1L))
                .willReturn(new ReservationListResponse(List.of(history)));

        // when & then
        mockMvc.perform(get("/api/orders/me/reservations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("예매 내역을 조회했습니다."))
                .andExpect(jsonPath("$.data.reservations[0].orderId").value("ORD-20260907-PAID"))
                .andExpect(jsonPath("$.data.reservations[0].amount").value(150000));
    }
}
