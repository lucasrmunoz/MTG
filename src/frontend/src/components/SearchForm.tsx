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
      className="panel p-4 sm:p-6 mb-6 sm:mb-8"
    >
      <label htmlFor="card-name" className="section-title mb-3">
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
          className="field min-w-0 flex-1 px-4"
        />
        <button
          type="submit"
          disabled={value.trim() === "" || loading}
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
    </form>
  );
}
