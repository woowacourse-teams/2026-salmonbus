interface RouteListItemProps {
  displayName: string;
  startStopName: string;
  endStopName: string;
}

export function RouteListItem({ displayName, startStopName, endStopName }: RouteListItemProps) {
  return (
    <li>
      <span>{displayName}</span>
      <p>
        {startStopName} ↔ {endStopName}
      </p>
    </li>
  );
}
