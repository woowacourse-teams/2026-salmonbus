import { RouteList } from "./components/RouteLists";
import salmongProud from "./assets/salmong-proud.png";
import { Pageinfo } from "./components/PageInfo";
import { titleMock, captionMock, routesMock } from "./api/RouteSelect.mock";

export function RouteSelectPage() {
  return (
    <>
      <header>
        <img src={salmongProud} alt="연어 버스 로고" />
        <Pageinfo title={titleMock} caption={captionMock} />
      </header>
      <main>
        <RouteList routes={routesMock} />
      </main>
    </>
  );
}
