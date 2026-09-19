interface ErrorBannerProps {
  message: string;
  onDismiss: () => void;
}

/** Dismissible banner shown when a request fails. */
export function ErrorBanner({ message, onDismiss }: ErrorBannerProps) {
  return (
    <div className="error-banner" role="alert">
      <span>{message}</span>
      <button
        type="button"
        className="error-banner-dismiss"
        onClick={onDismiss}
        aria-label="Dismiss error"
      >
        ✕
      </button>
    </div>
  );
}
