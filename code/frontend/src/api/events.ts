import { api } from "./axios";
import type {
  EventCategory,
  EventDetail,
  EventDetailResponse,
  EventListResponse,
  HotEvent,
  RankedEventsResponse,
  WeeklyRankingResponse,
} from "../types/Event";

type EventQuery = {
  category?: EventCategory;
  limit?: number;
};

export async function getEvents(
  category?: EventCategory,
  filters: { title?: string; region?: string } = {},
  { page = 0, size = 30, sort = "latest" }: { page?: number; size?: number; sort?: "latest" | "closingSoon" } = {},
) {
  const response = await api.get<EventListResponse>("/events", {
    params: { ...filters, ...(category ? { category } : {}), page, size, sort },
  });

  return response.data.data;
}

export async function getWhatsHot({ category, limit }: EventQuery = {}) {
  const response = await api.get<RankedEventsResponse<HotEvent>>(
    "/events/whats-hot",
    { params: { category, limit } },
  );

  return response.data.data.events;
}

export async function getWeeklyRanking({ category, limit }: EventQuery = {}) {
  const response = await api.get<WeeklyRankingResponse>(
    "/events/weekly-ranking",
    { params: { category, limit } },
  );

  return response.data.data;
}

export async function getEventDetail(eventId: number) {
  const response = await api.get<EventDetailResponse>(`/events/${eventId}`);
  const { event, ticketOptions } = response.data.data;
  const { running_time: legacyRunningTime, runningTime, ...eventFields } = event;
  const normalizedEvent: EventDetail = {
    ...eventFields,
    runningTime: runningTime ?? legacyRunningTime ?? 0,
  };

  return {
    event: normalizedEvent,
    ticketOptions,
  };
}
