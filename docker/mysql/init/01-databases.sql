-- Database per service: each service gets its own schema and an account that can only see that schema.
-- Runs only when the MySQL volume is created; after adding a service: docker compose down -v (drops local data).
CREATE DATABASE IF NOT EXISTS user_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'user_svc'@'%' IDENTIFIED BY 'user_svc_pw';
GRANT ALL PRIVILEGES ON user_db.* TO 'user_svc'@'%';

CREATE DATABASE IF NOT EXISTS product_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'product_svc'@'%' IDENTIFIED BY 'product_svc_pw';
GRANT ALL PRIVILEGES ON product_db.* TO 'product_svc'@'%';

CREATE DATABASE IF NOT EXISTS inventory_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'inventory_svc'@'%' IDENTIFIED BY 'inventory_svc_pw';
GRANT ALL PRIVILEGES ON inventory_db.* TO 'inventory_svc'@'%';

CREATE DATABASE IF NOT EXISTS order_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'order_svc'@'%' IDENTIFIED BY 'order_svc_pw';
GRANT ALL PRIVILEGES ON order_db.* TO 'order_svc'@'%';

FLUSH PRIVILEGES;
