import { useEffect, useState } from "react";
import axios from "axios";

import { getEvents, getWeeklyRanking, getWhatsHot } from "../../api/events";
import SlidePosts from "../../components/SlidePosts1";
import type {
  EventCategory,
  EventSummary,
  HotEvent,
  WeeklyRanking,
} from "../../types/Event";
import PosterSection from "./PosterSection";
import WeeklyRankingSection from "./WeeklyRankingSection";

type EventPageLayoutProps = {
  category: EventCategory;
  categoryName: string;
};

type SectionMessageProps = {
  message: string;
  error?: boolean;
};

function getErrorMessage(error: unknown, fallback: string) {
  if (axios.isAxiosError(error)) {
    return error.response?.data?.message ?? fallback;
  }

  return fallback;
}

function SectionMessage({ message, error = false }: SectionMessageProps) {
  return (
    <p role={error ? "alert" : undefined} className={`py-20 text-center ${error ? "text-red-500" : "text-gray-500"}`}>
      {message}
    </p>
  );
}

export default function EventPageLayout(props: EventPageLayoutProps) {
  return <EventPageContent key={props.category} {...props} />;
}

function EventPageContent({
  category,
  categoryName,
}: EventPageLayoutProps) {
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [hotEvents, setHotEvents] = useState<HotEvent[]>([]);
  const [closingSoonEvents, setClosingSoonEvents] = useState<EventSummary[]>([]);
  const [eventsError, setEventsError] = useState<string | null>(null);
  const [hotEventsError, setHotEventsError] = useState<string | null>(null);
  const [weeklyRanking, setWeeklyRanking] = useState<WeeklyRanking | null>(null);
  const [weeklyRankingError, setWeeklyRankingError] = useState<string | null>(null);
  const [closingSoonError, setClosingSoonError] = useState<string | null>(null);
  const [eventsLoading, setEventsLoading] = useState(true);
  const [rankingsLoading, setRankingsLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [request, setRequest] = useState({ page: 0 });
  const loading = rankingsLoading || (eventsLoading && events.length === 0);

  useEffect(() => {
    let active = true;

    const loadRankings = async () => {
      const [closingSoonResult, whatsHotResult, weeklyResult] =
        await Promise.allSettled([
          getEvents(category, {}, { page: 0, size: 5, sort: "closingSoon" }),
          getWhatsHot({ category, limit: 5 }),
          getWeeklyRanking({ category, limit: 5 }),
        ]);

      if (!active) {
        return;
      }

      if (closingSoonResult.status === "fulfilled") {
        setClosingSoonEvents(closingSoonResult.value.events);
      } else {
        setClosingSoonError(
          getErrorMessage(closingSoonResult.reason, "마감 임박 이벤트를 불러오지 못했습니다."),
        );
      }

      if (whatsHotResult.status === "fulfilled") {
        setHotEvents(whatsHotResult.value);
      } else {
        setHotEventsError(
          getErrorMessage(whatsHotResult.reason, "인기 이벤트를 불러오지 못했습니다."),
        );
      }

      if (weeklyResult.status === "fulfilled") {
        setWeeklyRanking(weeklyResult.value);
      } else {
        setWeeklyRankingError(
          getErrorMessage(weeklyResult.reason, "주간 순위를 불러오지 못했습니다."),
        );
      }

      setRankingsLoading(false);
    };

    void loadRankings();

    return () => {
      active = false;
    };
  }, [category]);

  useEffect(() => {
    let active = true;
    getEvents(category, {}, { page: request.page, size: 30, sort: "latest" })
      .then((result) => {
        if (!active) return;
        setEvents((previous) => request.page === 0 ? result.events : [...previous, ...result.events]);
        setPage(result.page);
        setHasNext(result.hasNext);
      })
      .catch((error) => {
        if (active) setEventsError(getErrorMessage(error, "이벤트 목록을 불러오지 못했습니다."));
      })
      .finally(() => {
        if (active) setEventsLoading(false);
      });
    return () => { active = false; };
  }, [category, request]);

  function loadMore() {
    if (eventsLoading) return;
    setEventsLoading(true);
    setEventsError(null);
    setRequest({ page: page + 1 });
  }

  const heroSlides = hotEvents
    .filter((event) => event.bannerImageUrl.trim() && event.mainImageUrl.trim())
    .slice(0, 3)
    .map((event) => ({
      id: event.id,
      name: event.name,
      imageUrl: event.bannerImageUrl,
      posterUrl: event.mainImageUrl,
    }));

  return (
    <main className="min-h-screen bg-white text-[#222222]">
      {!loading && heroSlides.length > 0 && <SlidePosts slides={heroSlides} />}

      <section className="py-20 text-center">
        <h1 className="text-4xl font-extrabold">{categoryName}</h1>
        <div className="mx-auto mt-5 h-[3px] w-10 bg-[#f36f21]" />
      </section>

      {loading ? (
        <SectionMessage message="이벤트를 불러오는 중입니다." />
      ) : (
        <>
          <section className="mb-24">
            {hotEventsError ? (
              <SectionMessage message={hotEventsError} error />
            ) : hotEvents.length > 0 ? (
              <PosterSection title="WHAT'S HOT" events={hotEvents} />
            ) : (
              <SectionMessage message="등록된 인기 이벤트가 없습니다." />
            )}
          </section>

          <section className="mb-24 bg-[#f7f7f7] py-20">
            {weeklyRankingError ? (
              <SectionMessage message={weeklyRankingError} error />
            ) : weeklyRanking && weeklyRanking.events.length > 0 ? (
              <WeeklyRankingSection events={weeklyRanking.events} period={weeklyRanking.period} />
            ) : (
              <SectionMessage message="등록된 주간 순위가 없습니다." />
            )}
          </section>

          {closingSoonError ? (
            <SectionMessage message={closingSoonError} error />
          ) : closingSoonEvents.length > 0 && (
            <section className="mb-24 bg-[#f7f7f7] py-20">
              <PosterSection title="마감 임박!" events={closingSoonEvents} showMore={false} />
            </section>
          )}

          {eventsError && <SectionMessage message={eventsError} error />}
          {events.length === 0 && !eventsError ? (
            <SectionMessage message="등록된 이벤트가 없습니다." />
          ) : events.length > 0 && (
            <section className="pb-24" aria-busy={eventsLoading}>
              <PosterSection title="FREE TICKET'S PICKS" events={events} showMore={hasNext} onMore={loadMore} loadingMore={eventsLoading} />
            </section>
          )}
        </>
      )}
    </main>
  );
}
