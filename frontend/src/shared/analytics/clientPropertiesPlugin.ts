import type { Types } from "@amplitude/unified";
import type { ClientProperties } from "@/shared/bridge/clientProperties";

/**
 * 모든 이벤트에 실행 환경 값을 사용자 속성으로 싣는다.
 * initAll 전에 등록해야 autocapture의 첫 세션 시작과 Page Viewed에도 붙는다. identify는 그 이벤트들보다 늦게 처리된다.
 */
export function clientPropertiesPluginFor(properties: ClientProperties): Types.EnrichmentPlugin {
  return {
    name: "client-properties",
    type: "enrichment",
    execute: async (event) =>
      event.event_type === "$identify"
        ? event
        : { ...event, user_properties: { ...event.user_properties, ...properties } },
  };
}
