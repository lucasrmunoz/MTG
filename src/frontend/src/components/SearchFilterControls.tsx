"use client";

import { COLOR_MATCH_MODES, COLORS, type ColorMatchMode } from "@/lib/colors";
import { CARD_TYPES, LAND_TRAITS, type SearchFilters } from "@/lib/search";

interface SearchFilterControlsProps {
  filters: SearchFilters;
  onChange: (filters: SearchFilters) => void;
}

interface ChipRowProps {
  label: string;
  options: readonly { id: string; label: string }[];
  selected: readonly string[];
  onToggle: (id: string) => void;
}

function ChipRow({ label, options, selected, onToggle }: ChipRowProps) {
  return (
    <div className="flex flex-wrap items-center gap-2" role="group" aria-label={label}>
      <span className="w-14 flex-shrink-0 text-orange font-semibold uppercase tracking-wide text-xs">
        {label}
      </span>
      {options.map((option) => {
        const active = selected.includes(option.id);
        return (
          <button
            key={option.id}
            type="button"
            aria-pressed={active}
            onClick={() => onToggle(option.id)}
            className={`btn btn-xs ${active ? "btn-primary" : "btn-ghost"}`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

function toggle(list: string[], id: string): string[] {
  return list.includes(id) ? list.filter((item) => item !== id) : [...list, id];
}

/**
 * Type, color and land-trait filters for the card search. The type is a single choice — click it
 * again to clear it — and picking Land reveals the land traits. Leaving Land drops those traits so
 * no hidden selection follows the user to the next type.
 */
export function SearchFilterControls({ filters, onChange }: SearchFilterControlsProps) {
  const isLand = filters.type === "land";

  function selectType(id: string) {
    const type = filters.type === id ? null : id;
    onChange({ ...filters, type, landTraits: type === "land" ? filters.landTraits : [] });
  }

  return (
    <div className="mt-4 flex flex-col gap-3">
      <ChipRow
        label="Type"
        options={CARD_TYPES}
        selected={filters.type === null ? [] : [filters.type]}
        onToggle={selectType}
      />

      <div className="flex flex-wrap items-center gap-x-5 gap-y-3">
        <ChipRow
          label="Colors"
          options={COLORS}
          selected={filters.colors}
          onToggle={(id) => onChange({ ...filters, colors: toggle(filters.colors, id) })}
        />
        <select
          value={filters.colorMode}
          onChange={(event) =>
            onChange({ ...filters, colorMode: event.target.value as ColorMatchMode })
          }
          disabled={filters.colors.length === 0}
          aria-label="Color match mode"
          className="field min-w-0 px-2 py-1 text-xs"
        >
          {COLOR_MATCH_MODES.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>
      </div>

      {isLand && (
        <ChipRow
          label="Land"
          options={LAND_TRAITS}
          selected={filters.landTraits}
          onToggle={(id) => onChange({ ...filters, landTraits: toggle(filters.landTraits, id) })}
        />
      )}

      {isLand && filters.colors.length > 0 && (
        <p className="text-foreground/50 text-xs">
          For lands, colors go by color identity — Island counts as blue.
        </p>
      )}
    </div>
  );
}
