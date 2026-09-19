import type { TodoStats } from '../types';

interface StatsBarProps {
  stats: TodoStats | null;
}

/** The "X active · Y completed · Z overdue" line under the heading. */
export function StatsBar({ stats }: StatsBarProps) {
  if (stats === null) {
    // Reserve the line's height so the header doesn't jump once stats load.
    return <p className="stats stats-placeholder">{' '}</p>;
  }
  return (
    <p className="stats">
      {`${stats.active} active · ${stats.completed} completed · ${stats.overdue} overdue`}
    </p>
  );
}
