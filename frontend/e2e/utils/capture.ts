import process from "node:process";

// 시안과 맞춰 보는 캡처는 비교하지 않고 남기기만 해서, E2E_CAPTURE=1로 켤 때만 찍는다.
export const captureEnabled = process.env.E2E_CAPTURE === "1";
