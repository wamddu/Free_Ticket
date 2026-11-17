import { api } from "./axios";
import type {
  ApiDataResponse,
  CheckoutOrder,
  PaymentResult,
  ReservationHistory,
} from "../types/Payment";

// queueToken: entry token from the edge waiting room (CloudFront + Lambda), checked by the order API.
export async function createOrder(ticketId: number, quantity: number, queueToken?: string | null) {
  const response = await api.post<ApiDataResponse<CheckoutOrder>>(
    "/orders",
    { ticketId, quantity },
    queueToken ? { headers: { "X-Queue-Token": queueToken } } : undefined,
  );
  return response.data.data;
}

export async function getCheckoutOrder(orderId: string) {
  const response = await api.get<ApiDataResponse<CheckoutOrder>>(
    `/orders/${encodeURIComponent(orderId)}/checkout`,
  );
  return response.data.data;
}

export async function getMyReservations() {
  const response = await api.get<
    ApiDataResponse<{ reservations: ReservationHistory[] }>
  >("/orders/me/reservations", { timeout: 10_000 });
  return response.data.data.reservations;
}

export async function cancelReservation(orderId: string) {
  const response = await api.post<ApiDataResponse<PaymentResult>>(
    `/payments/${encodeURIComponent(orderId)}/cancel`,
    undefined,
    { timeout: 90_000 },
  );
  return response.data.data;
}
