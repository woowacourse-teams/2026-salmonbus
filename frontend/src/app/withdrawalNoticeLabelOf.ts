export type WithdrawalResult = "withdrawn" | "cancelled" | "failed";

export function withdrawalNoticeLabelOf(result: WithdrawalResult) {
  if (result === "withdrawn") return "회원탈퇴가 완료됐어요";
  return result === "cancelled" ? "회원탈퇴를 취소했어요" : "회원탈퇴를 완료하지 못했어요. 다시 시도해 주세요.";
}
