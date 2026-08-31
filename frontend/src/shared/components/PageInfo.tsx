interface PageInfoProps {
  title: string;
  caption: string;
}

export function PageInfo({ title, caption }: PageInfoProps) {
  return (
    <div>
      <h1>{title}</h1>
      <p>{caption}</p>
    </div>
  );
}
