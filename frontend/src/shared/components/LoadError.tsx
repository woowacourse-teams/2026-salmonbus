import type { ReactNode } from "react";
import * as styles from "./LoadError.css";

interface LoadErrorProps {
  title: string;
  description: string;
  children: ReactNode;
}

export function LoadError({ title, description, children }: LoadErrorProps) {
  return (
    <div className={styles.container}>
      <svg
        width="34"
        height="34"
        viewBox="0 0 24 24"
        fill="none"
        stroke="#C6B9AB"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <circle cx="12" cy="12" r="10" />
        <path d="m15 9-6 6m0-6 6 6" />
      </svg>
      <p className={styles.title} role="alert">
        {title}
      </p>
      <p className={styles.description}>{description}</p>
      {children}
    </div>
  );
}
