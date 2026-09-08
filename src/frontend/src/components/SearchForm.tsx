"use client";

import { SearchFilterControls } from "@/components/SearchFilterControls";
import { hasFilters, type SearchFilters } from "@/lib/search";

interface SearchFormProps {
  value: string;
  loading: boolean;
  onChange: (value: string) => void;
  onSubmit: () => void;
  /** When given, the form shows filter controls under the name field. */
  filters?: SearchFilters | undefined;
  onFiltersChange?: ((filters: SearchFilters) => void) | undefined;
}

export function SearchForm({
  value,
  loading,
  onChange,
  onSubmit,
  filters,
  onFiltersChange,
}: SearchFormProps) {
  const filtered = filters !== undefined && hasFilters(filters);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
      className="panel p-4 sm:p-6 mb-6 sm:mb-8"
    >
      <label htmlFor="card-name" className="section-title mb-3">
        Card Name
        {filtered && (
          <span className="font-sans normal-case tracking-normal font-normal text-foreground/50 text-xs">
            optional while a filter is on
          </span>
        )}
      </label>
      <div className="flex gap-3">
        <input
          id="card-name"
          type="text"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={filtered ? "Any name" : "e.g. Lightning Bolt"}
          /* min-w-0 is required: a flex item defaults to min-width:auto, so without it the input
             refuses to shrink past its intrinsic width and pushes the button off narrow screens. */
          className="field min-w-0 flex-1 px-4"
        />
        <button
          type="submit"
          disabled={(value.trim() === "" && !filtered) || loading}
          className="btn btn-primary flex-shrink-0 px-4 sm:px-6"
        >
          {loading ? (
            <>
              <span className="spinner" />
              Searching...
            </>
          ) : (
            "Search"
          )}
        </button>
      </div>

      {filters !== undefined && onFiltersChange !== undefined && (
        <SearchFilterControls filters={filters} onChange={onFiltersChange} />
      )}
    </form>
  );
}
