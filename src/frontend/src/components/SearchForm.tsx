"use client";

interface SearchFormProps {
  value: string;
  loading: boolean;
  onChange: (value: string) => void;
  onSubmit: () => void;
}

export function SearchForm({
  value,
  loading,
  onChange,
  onSubmit,
}: SearchFormProps) {
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
      className="bg-surface rounded-lg border border-orange/30 p-4 sm:p-6 mb-6 sm:mb-8"
    >
      <label
        htmlFor="card-name"
        className="text-orange font-semibold text-sm uppercase tracking-wide mb-3 block"
      >
        Card Name
      </label>
      <div className="flex gap-3">
        <input
          id="card-name"
          type="text"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder="e.g. Lightning Bolt"
          /* min-w-0 is required: a flex item defaults to min-width:auto, so without it the input
             refuses to shrink past its intrinsic width and pushes the button off narrow screens. */
          className="min-w-0 flex-1 bg-background border border-orange/30 rounded px-4 py-2 text-foreground placeholder:text-foreground/40 focus:outline-none focus:border-orange transition-colors"
        />
        <button
          type="submit"
          disabled={value.trim() === "" || loading}
          className="flex-shrink-0 bg-orange hover:bg-orange-hover text-background font-semibold px-4 sm:px-6 py-2 rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
        >
          {loading ? (
            <span className="flex items-center gap-2">
              <span className="inline-block h-4 w-4 border-2 border-background border-t-transparent rounded-full animate-spin" />
              Searching...
            </span>
          ) : (
            "Search"
          )}
        </button>
      </div>
    </form>
  );
}
