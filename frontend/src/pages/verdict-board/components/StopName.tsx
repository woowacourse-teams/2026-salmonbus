import * as styles from "./StopName.css";

interface StopNameProps {
  name: string;
  muted?: boolean;
}

export function StopName({ name, muted = false }: StopNameProps) {
  return <span className={muted ? styles.mutedName : styles.name}>{name}</span>;
}
