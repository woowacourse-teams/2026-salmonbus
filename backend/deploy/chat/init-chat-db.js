const chat = db.getSiblingDB("salmonbus_chat");

if (!chat.getCollectionNames().includes("messages")) {
  chat.createCollection("messages");
}

chat.messages.createIndex(
  { roomId: 1, clientMessageId: 1 },
  { unique: true, name: "room_client_message_unique" }
);
chat.messages.createIndex(
  { roomId: 1, createdAt: 1, _id: 1 },
  { name: "room_recent" }
);

printjson(chat.messages.getIndexes());
