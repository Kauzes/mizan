/**
 * A day of business, twice: how much of it went through, and how much of it there was.
 *
 * Two charts rather than one with two scales. Putting a rate and a volume on one plot means
 * choosing where the two axes line up, and that choice invents a correlation the data does not
 * contain — it is the single most common way a dashboard misleads the person reading it. So
 * these are small multiples sharing an x axis: the rate above, the payments behind it below,
 * which is exactly the "a rate without a denominator is a rumour" point made visually.
 *
 * One series each, so no legend: the heading names it. Every mark carries its own numbers as a
 * native tooltip, and the same figures are available as a table for anybody who cannot hover
 * or cannot see colour.
 */
export interface Day {
  readonly day: string;
  readonly attempted: number;
  readonly authorized: number;
  readonly captured: number;
  readonly declined: number;
  readonly held: number;
}

const WIDTH = 720;
const HEIGHT = 120;
const PAD = { top: 8, right: 8, bottom: 18, left: 34 };

export function Daily({ days }: { days: readonly Day[] }) {
  const attempted = days.filter((day) => day.attempted > 0);

  if (attempted.length === 0) {
    return null;
  }

  return (
    <>
      <h3>How much went through</h3>
      <Rate days={attempted} />

      <h3>How many were tried</h3>
      <Volume days={attempted} />
    </>
  );
}

/** The authorization rate, as a line with a marker per day. */
function Rate({ days }: { days: readonly Day[] }) {
  const plot = {
    width: WIDTH - PAD.left - PAD.right,
    height: HEIGHT - PAD.top - PAD.bottom,
  };

  const x = (index: number) =>
    PAD.left + (days.length === 1 ? plot.width / 2 : (index / (days.length - 1)) * plot.width);
  // Fixed at nought to one hundred per cent. A rate axis that rescaled to its own data would
  // make a bad week and a perfect one look identical.
  const y = (rate: number) => PAD.top + plot.height - rate * plot.height;

  const points = days.map((day, index) => ({
    ...day,
    rate: day.authorized / day.attempted,
    cx: x(index),
  }));

  const line = points.map((point) => `${point.cx},${y(point.rate)}`).join(" ");

  return (
    <figure className="chart">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={`Authorization rate for each of ${days.length} days`}
        preserveAspectRatio="none"
      >
        {[0, 0.5, 1].map((at) => (
          <g key={at}>
            <line
              className="grid"
              x1={PAD.left}
              x2={WIDTH - PAD.right}
              y1={y(at)}
              y2={y(at)}
            />
            <text className="tick" x={PAD.left - 6} y={y(at) + 4} textAnchor="end">
              {Math.round(at * 100)}%
            </text>
          </g>
        ))}

        <polyline className="rate-line" points={line} fill="none" />

        {points.map((point) => (
          <circle key={point.day} className="rate-point" cx={point.cx} cy={y(point.rate)} r={4}>
            <title>
              {point.day}: {Math.round(point.rate * 100)}% of {point.attempted} authorized
            </title>
          </circle>
        ))}

        <Days days={days} x={x} />
      </svg>
    </figure>
  );
}

/** How many payments were tried each day, as bars. */
function Volume({ days }: { days: readonly Day[] }) {
  const plot = {
    width: WIDTH - PAD.left - PAD.right,
    height: HEIGHT - PAD.top - PAD.bottom,
  };

  const most = Math.max(...days.map((day) => day.attempted));
  const slot = plot.width / days.length;
  // A two pixel gap of surface between neighbours, so adjacent bars read as two bars.
  const bar = Math.max(2, Math.min(28, slot - 2));

  const x = (index: number) => PAD.left + index * slot + (slot - bar) / 2;
  const height = (count: number) => (count / most) * plot.height;

  return (
    <figure className="chart">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={`Payments attempted on each of ${days.length} days`}
        preserveAspectRatio="none"
      >
        {[0, most].map((at) => (
          <g key={at}>
            <line
              className="grid"
              x1={PAD.left}
              x2={WIDTH - PAD.right}
              y1={PAD.top + plot.height - height(at)}
              y2={PAD.top + plot.height - height(at)}
            />
            <text
              className="tick"
              x={PAD.left - 6}
              y={PAD.top + plot.height - height(at) + 4}
              textAnchor="end"
            >
              {at}
            </text>
          </g>
        ))}

        {days.map((day, index) => (
          <rect
            key={day.day}
            className="volume-bar"
            x={x(index)}
            y={PAD.top + plot.height - height(day.attempted)}
            width={bar}
            height={Math.max(1, height(day.attempted))}
            // Rounded at the data end only, anchored to the baseline.
            rx={2}
          >
            <title>
              {day.day}: {day.attempted} tried, {day.captured} captured, {day.declined}{" "}
              declined, {day.held} held
            </title>
          </rect>
        ))}

        <Days days={days} x={(index) => x(index) + bar / 2} />
      </svg>
    </figure>
  );
}

/**
 * The first and last day, and nothing in between.
 *
 * A label under every bar is unreadable at a month and illegible at a year, and the tooltip
 * on each mark already says which day it is.
 */
function Days({
  days,
  x,
}: {
  days: readonly Day[];
  x: (index: number) => number;
}) {
  const first = days[0];
  const last = days[days.length - 1];
  if (!first || !last) {
    return null;
  }

  return (
    <>
      <text className="tick" x={x(0)} y={HEIGHT - 4} textAnchor="start">
        {short(first.day)}
      </text>
      {days.length > 1 ? (
        <text className="tick" x={x(days.length - 1)} y={HEIGHT - 4} textAnchor="end">
          {short(last.day)}
        </text>
      ) : null}
    </>
  );
}

function short(day: string): string {
  return new Date(`${day}T00:00:00`).toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
  });
}
