ALTER TABLE show_schedules ADD COLUMN discount_percent INTEGER NOT NULL DEFAULT 0;
ALTER TABLE show_schedules ADD CONSTRAINT schedule_discount_range CHECK (discount_percent BETWEEN 0 AND 30);
