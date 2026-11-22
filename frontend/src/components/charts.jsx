import { useLayoutEffect, useRef, useState } from 'react';

/*
 * Small dependency-free SVG charts.
 * Colors come from a validated categorical palette (blue, orange: CVD-safe on white),
 * marks are thin with 4px rounded data-ends, gridlines are recessive, every chart has a
 * hover tooltip and a "View as table" fallback.
 */
export const SERIES = ['#2a78d6', '#eb6834'];

export function StatTile({ label, value, sub }) {
    return (
        <div className="stat-tile">
            <span className="stat-label">{label}</span>
            <b className="stat-value">{value}</b>
            {sub && <span className="stat-sub">{sub}</span>}
        </div>
    );
}

/** Nice round axis maximum and ~4 ticks. */
function ticks(max) {
    if (max <= 0) return { top: 4, step: 1 };
    const raw = max / 4;
    const mag = 10 ** Math.floor(Math.log10(raw));
    const step = [1, 2, 5, 10].map(m => m * mag).find(s => s >= raw);
    return { top: Math.ceil(max / step) * step, step };
}

/** Bar with 4px rounded top, flat bottom anchored to the baseline. */
function columnPath(x, y, w, h) {
    const r = Math.min(4, w / 2, h);
    if (h <= 0) return '';
    return `M${x},${y + h} V${y + r} Q${x},${y} ${x + r},${y} H${x + w - r} Q${x + w},${y} ${x + w},${y + r} V${y + h} Z`;
}

function Legend({ series }) {
    if (series.length < 2) return null;
    return (
        <div className="chart-legend">
            {series.map((s, i) => (
                <span key={s.name}><i style={{ background: SERIES[i] }} />{s.name}</span>
            ))}
        </div>
    );
}

/**
 * Grouped columns. data: [{ label, values: [n, n] }], series: [{ name }].
 */
/** Width of an element in CSS pixels, kept up to date (so SVG text stays 11px at any width). */
function useWidth(fallback = 640) {
    const ref = useRef(null);
    const [width, setWidth] = useState(fallback);
    useLayoutEffect(() => {
        if (!ref.current) return undefined;
        const ro = new ResizeObserver(([entry]) => setWidth(Math.max(240, Math.round(entry.contentRect.width))));
        ro.observe(ref.current);
        return () => ro.disconnect();
    }, []);
    return [ref, width];
}

export function ColumnChart({ data, series, height = 220, title }) {
    const [hover, setHover] = useState(null);
    const [boxRef, W] = useWidth();
    const H = height, padL = 32, padB = 26, padT = 10;
    const max = Math.max(0, ...data.flatMap(d => d.values));
    const { top, step } = ticks(max);
    const innerW = W - padL - 4, innerH = H - padB - padT;
    const band = innerW / data.length;
    const gap = 2;
    const barW = Math.max(3, Math.min(18, (band * 0.7 - gap * (series.length - 1)) / series.length));
    const y = v => padT + innerH - (v / top) * innerH;

    return (
        <figure className="chart">
            {title && <figcaption>{title}</figcaption>}
            <Legend series={series} />
            <div className="chart-box" ref={boxRef}>
                <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title}>
                    {Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step).map(t => (
                        <g key={t}>
                            <line x1={padL} x2={W - 4} y1={y(t)} y2={y(t)} className={t === 0 ? 'axis' : 'grid'} />
                            <text x={padL - 6} y={y(t) + 4} textAnchor="end" className="tick">{t}</text>
                        </g>
                    ))}
                    {data.map((d, i) => {
                        const groupW = barW * series.length + gap * (series.length - 1);
                        const x0 = padL + i * band + (band - groupW) / 2;
                        // keep about 56px per date label so they never collide
                        const every = Math.max(1, Math.ceil(56 / band));
                        const showLabel = (data.length - 1 - i) % every === 0;
                        return (
                            <g key={d.label}>
                                {hover === i && <rect x={padL + i * band} y={padT} width={band} height={innerH} className="hover-band" />}
                                {d.values.map((v, s) => (
                                    <path key={s} d={columnPath(x0 + s * (barW + gap), y(v), barW, y(0) - y(v))} fill={SERIES[s]} />
                                ))}
                                {showLabel && <text x={padL + i * band + band / 2} y={H - 8} textAnchor="middle" className="tick">{d.label}</text>}
                                <rect x={padL + i * band} y={padT} width={band} height={innerH + padB} fill="transparent"
                                      onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
                            </g>
                        );
                    })}
                </svg>
                {hover !== null && (
                    <div className="chart-tip" style={{ left: `${((padL + (hover + 0.5) * band) / W) * 100}%` }}>
                        <b>{data[hover].fullLabel || data[hover].label}</b>
                        {series.map((s, i) => (
                            <div key={s.name}><i style={{ background: SERIES[i] }} />{s.name}: <b>{data[hover].values[i]}</b></div>
                        ))}
                    </div>
                )}
            </div>
            <TableView headers={['', ...series.map(s => s.name)]}
                       rows={data.map(d => [d.fullLabel || d.label, ...d.values])} />
        </figure>
    );
}

/** One series of horizontal bars: rows [{ label, value, note }]. */
export function HBarChart({ rows, title, color = SERIES[0], unit = '' }) {
    const [hover, setHover] = useState(null);
    const max = Math.max(1, ...rows.map(r => r.value));
    return (
        <figure className="chart">
            {title && <figcaption>{title}</figcaption>}
            <div className="hbars">
                {rows.map((r, i) => (
                    <div key={r.label} className={`hbar-row ${hover === i ? 'hover' : ''}`}
                         onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}
                         title={`${r.label}: ${r.value}${unit}${r.note ? ' · ' + r.note : ''}`}>
                        <span className="hbar-label">{r.label}</span>
                        <span className="hbar-track">
                            <span className="hbar" style={{ width: `${(r.value / max) * 100}%`, background: color }} />
                        </span>
                        <span className="hbar-value">{r.value}{unit}</span>
                    </div>
                ))}
            </div>
            <TableView headers={['', title || 'Value']} rows={rows.map(r => [r.label, r.value])} />
        </figure>
    );
}

function TableView({ headers, rows }) {
    return (
        <details className="chart-table">
            <summary>View as table</summary>
            <table>
                <thead><tr>{headers.map(h => <th key={h}>{h}</th>)}</tr></thead>
                <tbody>{rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody>
            </table>
        </details>
    );
}
