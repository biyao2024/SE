-- Run interactively as a MySQL administrator. Replace the example password before use.
-- Match DB_USERNAME / DB_PASSWORD / DB_NAME in .env. Never commit real credentials.
CREATE DATABASE IF NOT EXISTS face_attendance CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'face_attendance'@'127.0.0.1' IDENTIFIED BY 'REPLACE_WITH_A_RANDOM_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP ON face_attendance.* TO 'face_attendance'@'127.0.0.1';
-- Local TCP may resolve to localhost. Both accounts stay restricted to this computer.
CREATE USER IF NOT EXISTS 'face_attendance'@'localhost' IDENTIFIED BY 'REPLACE_WITH_A_RANDOM_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP ON face_attendance.* TO 'face_attendance'@'localhost';
-- IF NOT EXISTS does not reset an existing account's password. Keep its known password.
