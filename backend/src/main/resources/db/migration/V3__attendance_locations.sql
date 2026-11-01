ALTER TABLE system_setting ADD COLUMN attendance_mode VARCHAR(32) NOT NULL DEFAULT 'FACE';

CREATE TABLE attendance_location (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(255) NOT NULL,
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    radius_meters INT NOT NULL,
    active BOOLEAN NOT NULL,
    deleted BOOLEAN NOT NULL
);

ALTER TABLE punch_event ADD COLUMN location_id BIGINT;
ALTER TABLE punch_event ADD COLUMN location_name VARCHAR(255);
ALTER TABLE punch_event ADD COLUMN distance_meters DOUBLE;
ALTER TABLE punch_event ADD COLUMN accuracy_meters DOUBLE;
ALTER TABLE punch_event ADD COLUMN location_request_hash VARCHAR(64);
ALTER TABLE punch_event ADD CONSTRAINT fk_punch_event_location FOREIGN KEY (location_id) REFERENCES attendance_location(id);
