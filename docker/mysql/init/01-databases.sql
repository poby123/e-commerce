-- Database per service: each service gets its own schema and an account that can only see that schema.
CREATE DATABASE IF NOT EXISTS user_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'user_svc'@'%' IDENTIFIED BY 'user_svc_pw';
GRANT ALL PRIVILEGES ON user_db.* TO 'user_svc'@'%';

FLUSH PRIVILEGES;
