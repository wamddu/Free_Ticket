// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { getEvents, getWeeklyRanking, getWhatsHot } from "../../api/events";
import type { EventSummary } from "../../types/Event";
import EventPageLayout from "./EventPageLayout";

vi.mock("../../api/events", () => ({
  getEvents: vi.fn(),
  getWhatsHot: vi.fn(),
  getWeeklyRanking: vi.fn(),
}));

const event: EventSummary = {
  id: 40, name: "첫 페이지 공연", startDate: "2026-09-01", endDate: "2026-12-01",
  location: "Seoul", mainImageUrl: "poster", category: "musical",
};

function page(events: EventSummary[], page = 0, hasNext = false) {
  return { events, page, size: 30, hasNext };
}

function picks() {
  return within(screen.getByRole("heading", { name: "FREE TICKET'S PICKS" }).closest("section")!);
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getEvents).mockResolvedValue(page([]));
  vi.mocked(getWhatsHot).mockResolvedValue([{
    rank: 1, id: 2, name: "early", startDate: "2026-09-01", endDate: "2026-10-01",
    location: "Seoul", bannerImageUrl: "banner", mainImageUrl: "poster", category: "musical",
  }]);
  vi.mocked(getWeeklyRanking).mockResolvedValue({
    category: "musical", period: "WEEKLY", events: [{
      rank: 1, id: 7, name: "이번 주 판매 1위", startDate: "2026-09-01", endDate: "2026-12-01",
      location: "Seoul", mainImageUrl: "poster", category: "musical",
    }],
  });
});
afterEach(cleanup);

function ranking() {
  return within(screen.getByRole("heading", { level: 2, name: /RANKING/ }).closest("section")!);
}

test("주간 순위는 HOT과 별도로 최근 7일 판매 순위 API 결과를 표시한다", async () => {
  render(<MemoryRouter><EventPageLayout category="musical" categoryName="뮤지컬" /></MemoryRouter>);

  expect(await screen.findByText("이번 주 1위")).toBeTruthy();
  expect(ranking().getByRole("heading", { name: "WEEKLY RANKING" })).toBeTruthy();
  expect(ranking().getByRole("heading", { name: "이번 주 판매 1위" })).toBeTruthy();
  expect(ranking().queryByRole("heading", { name: "early" })).toBeNull();
  expect(getWeeklyRanking).toHaveBeenCalledWith({ category: "musical", limit: 5 });
  expect(getWhatsHot).toHaveBeenCalledTimes(1);
});

test("이번 주 판매가 없어 누적 순위로 대체되면 제목도 누적 기준으로 바뀐다", async () => {
  vi.mocked(getWeeklyRanking).mockResolvedValue({
    category: "musical", period: "ALL_TIME", events: [{
      rank: 1, id: 8, name: "누적 판매 공연", startDate: "2026-09-01", endDate: "2026-12-01",
      location: "Seoul", mainImageUrl: "poster", category: "musical",
    }],
  });
  render(<MemoryRouter><EventPageLayout category="musical" categoryName="뮤지컬" /></MemoryRouter>);

  expect(await screen.findByText("누적 판매 1위")).toBeTruthy();
  expect(ranking().getByRole("heading", { name: "BEST RANKING" })).toBeTruthy();
  expect(screen.queryByText("이번 주 1위")).toBeNull();
  expect(screen.queryByText("WEEKLY RANKING")).toBeNull();
});

test("주간 순위만 실패해도 다른 영역은 표시한다", async () => {
  vi.mocked(getWeeklyRanking).mockRejectedValue(new Error("offline"));
  render(<MemoryRouter><EventPageLayout category="musical" categoryName="뮤지컬" /></MemoryRouter>);

  expect(await screen.findByText("주간 순위를 불러오지 못했습니다.")).toBeTruthy();
  expect(screen.getByRole("heading", { name: "WHAT'S HOT" })).toBeTruthy();
});

test("마감 임박은 첫 페이지에 없는 공연도 서버가 정한 순서대로 표시한다", async () => {
  vi.mocked(getEvents).mockImplementation(async (_category, _filters, options) => options?.sort === "closingSoon"
    ? page([{ ...event, id: 99, name: "전역 마감 1위", endDate: "2026-11-02" }, { ...event, id: 98, name: "전역 마감 2위", endDate: "2026-11-02" }])
    : page([event]));
  render(<MemoryRouter><EventPageLayout category="musical" categoryName="뮤지컬" /></MemoryRouter>);

  const closing = within((await screen.findByRole("heading", { name: "마감 임박!" })).closest("section")!);
  expect(closing.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent)).toEqual(["전역 마감 1위", "전역 마감 2위"]);
  expect(picks().getByRole("heading", { name: "첫 페이지 공연" })).toBeTruthy();
  expect(picks().queryByRole("heading", { name: "전역 마감 1위" })).toBeNull();
  expect(getEvents).toHaveBeenCalledWith("musical", {}, { page: 0, size: 5, sort: "closingSoon" });
});

test("PICKS를 이어 불러오는 동안 버튼을 비활성화하고 마지막 페이지에서 숨긴다", async () => {
  let resolveNext!: (value: ReturnType<typeof page>) => void;
  vi.mocked(getEvents).mockImplementation(async (_category, _filters, options) => {
    if (options?.sort === "closingSoon") return page([]);
    if (options?.page === 1) return new Promise((resolve) => { resolveNext = resolve; });
    return page([event], 0, true);
  });
  render(<MemoryRouter><EventPageLayout category="musical" categoryName="뮤지컬" /></MemoryRouter>);
  await screen.findByRole("heading", { name: "FREE TICKET'S PICKS" });
  fireEvent.click(picks().getByRole("button", { name: "더보기 +" }));
  expect((picks().getByRole("button", { name: /불러오는 중/ }) as HTMLButtonElement).disabled).toBe(true);
  expect(picks().getByRole("heading", { name: "첫 페이지 공연" })).toBeTruthy();
  await waitFor(() => expect(getEvents).toHaveBeenCalledWith("musical", {}, { page: 1, size: 30, sort: "latest" }));
  await act(async () => resolveNext(page([{ ...event, id: 39, name: "다음 페이지 공연" }], 1)));
  expect(picks().getByRole("heading", { name: "첫 페이지 공연" })).toBeTruthy();
  expect(picks().getByRole("heading", { name: "다음 페이지 공연" })).toBeTruthy();
  expect(picks().queryByRole("button", { name: "더보기 +" })).toBeNull();
  expect(getWhatsHot).toHaveBeenCalledTimes(1);
});

test("PICKS 추가 요청 실패 시 기존 목록을 유지하고 같은 페이지를 재시도한다", async () => {
  let appendAttempts = 0;
  vi.mocked(getEvents).mockImplementation(async (_category, _filters, options) => {
    if (options?.sort === "closingSoon") return page([]);
    if (options?.page === 1) {
      appendAttempts++;
      if (appendAttempts === 1) throw new Error("offline");
      return page([{ ...event, id: 39, name: "재시도 공연" }], 1);
    }
    return page([event], 0, true);
  });
  render(<MemoryRouter><EventPageLayout category="musical" categoryName="뮤지컬" /></MemoryRouter>);
  await screen.findByRole("heading", { name: "FREE TICKET'S PICKS" });
  fireEvent.click(picks().getByRole("button", { name: "더보기 +" }));
  expect(await screen.findByRole("alert")).toBeTruthy();
  expect(picks().getByRole("heading", { name: "첫 페이지 공연" })).toBeTruthy();
  fireEvent.click(picks().getByRole("button", { name: "더보기 +" }));
  expect(await screen.findByRole("heading", { name: "재시도 공연" })).toBeTruthy();
  expect(picks().getByRole("heading", { name: "첫 페이지 공연" })).toBeTruthy();
  expect(screen.queryByRole("alert")).toBeNull();
});

test("카테고리를 바꾸면 첫 페이지부터 시작하고 이전 추가 요청을 무시한다", async () => {
  let resolveNext!: (value: ReturnType<typeof page>) => void;
  vi.mocked(getEvents).mockImplementation(async (category, _filters, options) => {
    if (options?.sort === "closingSoon") return page([]);
    if (category === "play") return page([{ ...event, id: 80, name: "새 카테고리 공연", category: "play" }]);
    if (options?.page === 1) return new Promise((resolve) => { resolveNext = resolve; });
    return page([event], 0, true);
  });
  const view = render(<MemoryRouter><EventPageLayout category="musical" categoryName="뮤지컬" /></MemoryRouter>);
  await screen.findByRole("heading", { name: "FREE TICKET'S PICKS" });
  fireEvent.click(picks().getByRole("button", { name: "더보기 +" }));
  await waitFor(() => expect(getEvents).toHaveBeenCalledWith("musical", {}, { page: 1, size: 30, sort: "latest" }));
  view.rerender(<MemoryRouter><EventPageLayout category="play" categoryName="연극" /></MemoryRouter>);
  expect(await screen.findByRole("heading", { name: "새 카테고리 공연" })).toBeTruthy();
  await act(async () => resolveNext(page([{ ...event, id: 39, name: "이전 추가 공연" }], 1, true)));
  expect(picks().queryByRole("heading", { name: "첫 페이지 공연" })).toBeNull();
  expect(picks().queryByRole("heading", { name: "이전 추가 공연" })).toBeNull();
  expect(picks().queryByRole("button", { name: "더보기 +" })).toBeNull();
  expect(getEvents).toHaveBeenCalledWith("play", {}, { page: 0, size: 30, sort: "latest" });
});
