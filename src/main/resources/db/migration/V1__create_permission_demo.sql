CREATE DATABASE IF NOT EXISTS permission_sample;

CREATE TABLE IF NOT EXISTS permission_sample.test_table (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    value VARCHAR(255) NOT NULL
);

CREATE USER IF NOT EXISTS 'test-reader'@'%';
GRANT SELECT ON permission_sample.test_table TO 'test-reader'@'%';

CREATE OR REPLACE SQL SECURITY DEFINER VIEW permission_metadata.permission_table_privileges AS
SELECT TABLE_SCHEMA, TABLE_NAME, GRANTEE, PRIVILEGE_TYPE, IS_GRANTABLE
FROM information_schema.TABLE_PRIVILEGES
WHERE TABLE_SCHEMA = 'permission_sample';

CREATE USER IF NOT EXISTS '${managementUser}'@'%' IDENTIFIED BY '${managementPassword}';
GRANT ALL PRIVILEGES ON permission_sample.* TO '${managementUser}'@'%' WITH GRANT OPTION;
GRANT SELECT ON permission_metadata.permission_table_privileges TO '${managementUser}'@'%';
