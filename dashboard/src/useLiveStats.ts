import { useEffect, useRef, useState } from "react";
import { api } from "./api";
import type { JobEvent, Snapshot } from "./types";

export type Connection = "connecting" | "live" | "polling" | "offline";

const MAX_EVENTS = 60;

/**
 * Subscribes to the API's server-sent snapshot stream (1/s). If SSE can't be established
 * (some proxies buffer it), falls back to polling /api/stats every 2s.
 */
export function useLiveStats() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [events, setEvents] = useState<JobEvent[]>([]);
  const [connection, setConnection] = useState<Connection>("connecting");
  const pollTimer = useRef<number | null>(null);

  useEffect(() => {
    let closed = false;
    let source: EventSource | null = null;
    let failures = 0;

    const apply = (snap: Snapshot) => {
      setSnapshot(snap);
      if (snap.events?.length) {
        setEvents((prev) => [...snap.events.slice().reverse(), ...prev].slice(0, MAX_EVENTS));
      }
    };

    const startPolling = () => {
      if (pollTimer.current !== null) return;
      const poll = async () => {
        try {
          apply(await api.stats());
          setConnection("polling");
        } catch {
          setConnection("offline");
        }
      };
      void poll();
      pollTimer.current = window.setInterval(poll, 2000);
    };

    const stopPolling = () => {
      if (pollTimer.current !== null) {
        window.clearInterval(pollTimer.current);
        pollTimer.current = null;
      }
    };

    const connect = () => {
      if (closed) return;
      source = new EventSource("/api/stream");
      source.addEventListener("snapshot", (e) => {
        failures = 0;
        stopPolling();
        setConnection("live");
        apply(JSON.parse((e as MessageEvent).data) as Snapshot);
      });
      source.onerror = () => {
        source?.close();
        failures += 1;
        startPolling();
        window.setTimeout(connect, Math.min(15000, 1000 * 2 ** failures));
      };
    };

    connect();
    return () => {
      closed = true;
      source?.close();
      stopPolling();
    };
  }, []);

  return { snapshot, events, connection };
}
