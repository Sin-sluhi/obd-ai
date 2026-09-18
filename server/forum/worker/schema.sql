CREATE TABLE IF NOT EXISTS messages(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  room TEXT NOT NULL,
  device TEXT NOT NULL,
  name TEXT NOT NULL,
  text TEXT NOT NULL,
  time INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_room ON messages(room, id);
CREATE INDEX IF NOT EXISTS idx_messages_device ON messages(device, time);
CREATE TABLE IF NOT EXISTS presence(
  room TEXT NOT NULL,
  device TEXT NOT NULL,
  name TEXT NOT NULL,
  seen INTEGER NOT NULL,
  PRIMARY KEY(room, device)
);
