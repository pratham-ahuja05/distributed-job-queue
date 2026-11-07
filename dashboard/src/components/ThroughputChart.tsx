import { useEffect, useMemo, useRef, useState } from "react";
import type { SeriesPoint } from "../types";

const SERIES = [
  { key: "success", label: "Completed", color: "var(--series-1)" },
  { key: "retry", label: "Retries scheduled", color: "var(--series-2)" },
  { key: "dlq", label: "Dead-lettered", color: "var(--series-3)" },
] as const;

const HEIGHT = 220;
const PAD = { top: 10, right: 12, bottom: 22, left: 34 };

function niceMax(v: number) {
  if (v <= 5) return 5;
  const pow = 10 ** Math.floor(Math.log10(v));
  const n = v / pow;
  return (n <= 2 ? 2 : n <= 5 ? 5 : 10) * pow;
}

/** Jobs per second over the last 60 s; one y-axis, crosshair tooltip on hover. */
export function ThroughputChart({ series }: { series: SeriesPoint[] }) {
  const wrap = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(600);
  const [hover, setHover] = useState<number | null>(null);

  useEffect(() => {
    if (!wrap.current) return;
    const ro = new ResizeObserver(([entry]) => setWidth(Math.max(280, entry.contentRect.width)));
    ro.observe(wrap.current);
    return () => ro.disconnect();
  }, []);

  const { max, x, y } = useMemo(() => {
    const peak = Math.max(1, ...series.flatMap((p) => SERIES.map((s) => p[s.key])));
    const m = niceMax(peak);
    const innerW = width - PAD.left - PAD.right;
    const innerH = HEIGHT - PAD.top - PAD.bottom;
    return {
      max: m,
      x: (i: number) => PAD.left + (series.length <= 1 ? 0 : (i / (series.length - 1)) * innerW),
      y: (v: number) => PAD.top + innerH - (v / m) * innerH,
    };
  }, [series, width]);

  const path = (key: (typeof SERIES)[number]["key"]) =>
    series.map((p, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(p[key]).toFixed(1)}`).join("");

  const ticks = [0, max / 2, max];
  const last = series[series.length - 1];
  const hovered = hover !== null ? series[hover] : null;

  const onMove = (e: React.PointerEvent<SVGRectElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const rel = (e.clientX - rect.left) / rect.width;
    setHover(Math.max(0, Math.min(series.length - 1, Math.round(rel * (series.length - 1)))));
  };

  return (
    <div>
      <div className="legend" style={{ marginBottom: 10 }}>
        {SERIES.map((s) => (
          <span key={s.key} className="key">
            <span className="swatch" style={{ background: s.color }} />
            {s.label} <b>{last ? last[s.key] : 0}/s</b>
          </span>
        ))}
      </div>
      <div className="chart-wrap" ref={wrap}>
        <svg height={HEIGHT} role="img" aria-label="Jobs per second over the last 60 seconds: completed, retries scheduled, dead-lettered">
          {ticks.map((t) => (
            <g key={t}>
              <line x1={PAD.left} x2={width - PAD.right} y1={y(t)} y2={y(t)} stroke="var(--grid)" strokeWidth={1} />
              <text x={PAD.left - 6} y={y(t) + 4} textAnchor="end" className="axis-label">{t}</text>
            </g>
          ))}
          <text x={PAD.left} y={HEIGHT - 4} className="axis-label">−60s</text>
          <text x={width - PAD.right} y={HEIGHT - 4} textAnchor="end" className="axis-label">now</text>
          {SERIES.slice().reverse().map((s) => (
            <path key={s.key} d={path(s.key)} fill="none" stroke={s.color} strokeWidth={2}
              strokeLinejoin="round" strokeLinecap="round" />
          ))}
          {hovered && hover !== null && (
            <g>
              <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={HEIGHT - PAD.bottom} stroke="var(--text-muted)" strokeDasharray="3 3" />
              {SERIES.map((s) => (
                <circle key={s.key} cx={x(hover)} cy={y(hovered[s.key])} r={4} fill={s.color}
                  stroke="var(--surface-1)" strokeWidth={2} />
              ))}
            </g>
          )}
          <rect x={PAD.left} y={0} width={Math.max(0, width - PAD.left - PAD.right)} height={HEIGHT}
            fill="transparent" onPointerMove={onMove} onPointerLeave={() => setHover(null)} />
        </svg>
        {hovered && hover !== null && (
          <div className="tooltip" style={{
            left: Math.min(x(hover) + 12, width - 170),
            top: 8,
          }}>
            <div className="t">{new Date(hovered.t).toLocaleTimeString()}</div>
            {SERIES.map((s) => (
              <div key={s.key} className="r">
                <span><span className="swatch" style={{ width: 10, height: 3, borderRadius: 2, background: s.color, display: "inline-block" }} />{s.label}</span>
                <b>{hovered[s.key]}</b>
              </div>
            ))}
            <div className="r"><span>Submitted</span><b>{hovered.submitted}</b></div>
            <div className="r"><span>Recovered</span><b>{hovered.recovered}</b></div>
          </div>
        )}
      </div>
    </div>
  );
}
