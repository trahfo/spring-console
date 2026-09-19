import type { Filter } from '../types';

const FILTERS: ReadonlyArray<{ value: Filter; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'active', label: 'Active' },
  { value: 'completed', label: 'Completed' },
];

interface FilterTabsProps {
  filter: Filter;
  onChange: (filter: Filter) => void;
}

export function FilterTabs({ filter, onChange }: FilterTabsProps) {
  return (
    <div className="filter-tabs" role="group" aria-label="Filter todos">
      {FILTERS.map(({ value, label }) => (
        <button
          key={value}
          type="button"
          className={`filter-tab${filter === value ? ' is-active' : ''}`}
          aria-pressed={filter === value}
          onClick={() => onChange(value)}
        >
          {label}
        </button>
      ))}
    </div>
  );
}
