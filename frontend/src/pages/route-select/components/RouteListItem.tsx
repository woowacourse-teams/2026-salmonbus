interface RouteListItemProps {
  displayName: string;
  startStopName: string;
  endStopName: string;
}

export function RouteListItem({ displayName, startStopName, endStopName }: RouteListItemProps) {
  return (
    <li>
      <button type="button">
        <span>{displayName}</span>
        <span>
          {startStopName} ↔ {endStopName}
        </span>
      </button>
    </li>
  );
}
