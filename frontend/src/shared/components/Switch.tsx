import type { ComponentPropsWithoutRef } from "react";
import * as styles from "./Switch.css";

type SwitchProps = Omit<ComponentPropsWithoutRef<"input">, "type" | "role" | "className">;

export function Switch(props: SwitchProps) {
  return (
    <span className={styles.track}>
      <input {...props} className={styles.input} type="checkbox" role="switch" />
      <span className={styles.thumb} aria-hidden="true" />
    </span>
  );
}
