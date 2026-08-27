interface RouteListItemProps {
  displayName: string;
  startStopName: string;
  endStopName: string;
}

export function RouteListItem({ displayName, startStopName, endStopName }: RouteListItemProps) {
  return (
    <li>
      <button type="button" aria-label={`${displayName}번 노선, ${startStopName}부터 ${endStopName} 구간`}>
        <span>{displayName}</span>
        <span>
          {startStopName} ↔ {endStopName}
        </span>
      </button>
    </li>
  );
}
