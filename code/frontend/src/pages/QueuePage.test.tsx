// @vitest-environment jsdom
import { act, cleanup, render, screen } from "@testing-library/react";
import { AxiosError, AxiosHeaders } from "axios";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { api } from "../api/axios";
import QueuePage from "./QueuePage";

const waiting = (position: number, nextPollSeconds: number) => ({
  admitted: false, bypassed: false, position, estimatedWaitSeconds: position, nextPollSeconds, passExpiresInSeconds: null,
});
const admitted = { admitted: true, bypassed: false, position: null, estimatedWaitSeconds: null, nextPollSeconds: null, passExpiresInSeconds: 300 };
const order = { orderId: "ORDER-1" };
const ok = (data: unknown) => ({ data: { message: "OK", data } });

function apiError(status: number, code: string) {
  const headers = new AxiosHeaders();
  return new AxiosError(code, String(status), { headers }, null, {
    status, statusText: code, headers, config: { headers }, data: { code, message: code },
  });
}

function openQueue() {
  render(
    <MemoryRouter initialEntries={["/queue/7?ticketId=70&quantity=2"]}>
      <Routes>
        <Route path="/queue/:eventId" element={<QueuePage />} />
        <Route path="/payment/checkout/:orderId" element={<p>결제 화면</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function flush() {
  await act(async () => { await Promise.resolve(); });
}

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

test("순번을 보여 주며 안내받은 간격으로 확인하고, 순서가 되면 고른 티켓으로 주문한다", async () => {
  const post = vi.spyOn(api, "post").mockImplementation(async (url: string) =>
    url === "/queue/7" ? ok(waiting(3, 2)) : ok(order));
  const get = vi.spyOn(api, "get")
    .mockResolvedValueOnce(ok(waiting(1, 2)))
    .mockResolvedValueOnce(ok(admitted));

  openQueue();
  await flush();
  expect(screen.getByLabelText("대기 순서").textContent).toBe("2명");
  expect(get).not.toHaveBeenCalled();

  await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
  expect(screen.getByLabelText("대기 순서").textContent).toBe("0명");

  await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
  expect(post).toHaveBeenCalledWith("/orders", { ticketId: 70, quantity: 2 }, undefined);
  expect(screen.getByText("결제 화면")).toBeTruthy();
});

test("입장권이 만료돼 주문이 거절되면 한 번 다시 줄을 선다", async () => {
  let orders = 0;
  vi.spyOn(api, "post").mockImplementation(async (url: string) => {
    if (url === "/queue/7") return orders === 0 ? ok(admitted) : ok(waiting(5, 3));
    orders += 1;
    throw apiError(409, "QUEUE_REQUIRED");
  });

  openQueue();
  await flush();
  await flush();

  expect(screen.getByLabelText("대기 순서").textContent).toBe("4명");
});

test("엣지 대기실이 준 입장 토큰을 주문 요청 헤더에 붙인다", async () => {
  const post = vi.spyOn(api, "post").mockImplementation(async (url: string) =>
    url === "/queue/7" ? ok({ ...admitted, token: "edge-token" }) : ok(order));

  openQueue();
  await flush();
  await flush();

  expect(post).toHaveBeenCalledWith("/orders", { ticketId: 70, quantity: 2 }, { headers: { "X-Queue-Token": "edge-token" } });
  expect(screen.getByText("결제 화면")).toBeTruthy();
});

test("대기열을 쓸 수 없으면(bypassed) 바로 주문한다", async () => {
  vi.spyOn(api, "post").mockImplementation(async (url: string) =>
    url === "/queue/7" ? ok({ ...admitted, bypassed: true, passExpiresInSeconds: null }) : ok(order));

  openQueue();
  await flush();
  await flush();

  expect(screen.getByText("결제 화면")).toBeTruthy();
});
