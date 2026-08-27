import * as styles from "./BoardHeader.css";

interface BoardHeaderProps {
  routeName: string;
}

export function BoardHeader({ routeName }: BoardHeaderProps) {
  return (
    <header className={styles.header}>
      <h1 className={styles.routeName}>{routeName}</h1>
    </header>
  );
}
