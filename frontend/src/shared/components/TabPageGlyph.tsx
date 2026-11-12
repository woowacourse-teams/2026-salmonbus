type TabPage = "routes" | "favorites" | "profile";

export function TabPageGlyph({ page }: { page: TabPage }) {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {page === "routes" ? (
        <>
          <circle data-tab-fill="" cx="5" cy="18" r="2.3" />
          <circle data-tab-fill="" cx="19" cy="6" r="2.3" />
          <path d="M7.3 18H14a3 3 0 0 0 0-6h-4a3 3 0 0 1 0-6h6.7" />
        </>
      ) : page === "favorites" ? (
        <path data-tab-fill="" d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9Z" />
      ) : (
        <>
          <circle data-tab-fill="" cx="12" cy="7" r="3.5" />
          <path data-tab-fill="" d="M5 21v-2a7 7 0 0 1 14 0v2" />
        </>
      )}
    </svg>
  );
}
