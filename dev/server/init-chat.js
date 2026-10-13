const chat = db.getSiblingDB("salmonbus_chat_dev");
const password = process.env.MONGO_CHAT_PASSWORD;
if (!/^[0-9a-f]{48}$/.test(password ?? "")) {
  throw new Error("개발 채팅 계정 설정을 확인해 주세요.");
}
chat.createUser({
  user: "salmonbus_dev_chat",
  pwd: password,
  roles: [{ role: "readWrite", db: "salmonbus_chat_dev" }],
});
chat.createCollection("messages");
chat.messages.createIndex(
  { roomId: 1, clientMessageId: 1 },
  { unique: true, name: "room_client_message_unique" },
);
chat.messages.createIndex(
  { roomId: 1, createdAt: 1, _id: 1 },
  { name: "room_recent" },
);
