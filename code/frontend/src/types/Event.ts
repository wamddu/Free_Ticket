export type EventCategory =
  | "musical"
  | "play"
  | "exhibition"
  | "concert"
  | "classic"
  | "busking";

export type EventSummary = {
  id: number;
  name: string;
  startDate: string;
  endDate: string;
  location: string;
  mainImageUrl: string;
  category: EventCategory;
  description?: string;
};

export type WeeklyRankedEvent = EventSummary & {
  rank: number;
};

export type HotEvent = WeeklyRankedEvent & {
  bannerImageUrl: string;
};

export type EventDetail = EventSummary & {
  bannerImageUrl: string;
  description: string;
  runningTime: number;
  descriptionImageUrl: string;
};

export type TicketOption = {
  ticketId: number;
  type: string;
  price: number;
  totalTicket: number;
  soldTicket: number;
  remainingTicket: number;
  description: string;
  bookingEndTime: string;
  startTime: string;
  soldOut: boolean;
  bookingAvailable: boolean;
};

export type EventPage = {
  events: EventSummary[];
  page: number;
  size: number;
  hasNext: boolean;
};

export type EventListResponse = {
  message: string;
  data: EventPage;
};

export type RankedEventsResponse<TEvent extends WeeklyRankedEvent> = {
  message: string;
  data: {
    category: EventCategory | null;
    events: TEvent[];
  };
};

// WEEKLY: ranked by the last 7 days of sales. ALL_TIME: nothing sold this week, so cumulative sales.
export type RankingPeriod = "WEEKLY" | "ALL_TIME";

export type WeeklyRanking = {
  category: EventCategory | null;
  period: RankingPeriod;
  events: WeeklyRankedEvent[];
};

export type WeeklyRankingResponse = {
  message: string;
  data: WeeklyRanking;
};

export type EventDetailApi = Omit<EventDetail, "runningTime"> & {
  runningTime?: number;
  running_time?: number;
};

export type EventDetailResponse = {
  message: string;
  data: {
    event: EventDetailApi;
    ticketOptions: TicketOption[];
  };
};

export const eventCategoryLabels: Record<EventCategory, string> = {
  musical: "뮤지컬",
  play: "연극",
  exhibition: "전시/스포츠",
  concert: "콘서트",
  classic: "클래식/무용",
  busking: "버스킹",
};
