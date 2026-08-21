import { RouteLists } from "./components/RouteLists";
import salmongProud from "./assets/salmong-proud.png";

export function RouteSelectPage() {
  return (
    <>
      <header>
        <img src={salmongProud}></img>
        <h1>어떤 버스를 타시나요?</h1>
        <p>노선을 선택하면 정류잘별 도착 예상 좌석과 탑승 판정을 빠르게 확인할 수 있어요.</p>
      </header>
      <main>
        <RouteLists />
      </main>
    </>
  );
}
