CREATE TABLE `health_days` (
	`owner_id` text NOT NULL,
	`date` text NOT NULL,
	`import_id` text NOT NULL,
	`day_start` text NOT NULL,
	`day_end` text NOT NULL,
	`day_end_ms` integer NOT NULL,
	`sleep_window_start` text,
	`sleep_window_end` text,
	`steps` integer,
	`sleep_minutes` real,
	`heart_rate_avg_bpm` real,
	`weight_kg` real,
	`distance_meters` real,
	`active_calories_kcal` real,
	PRIMARY KEY(`owner_id`, `date`)
);
--> statement-breakpoint
CREATE INDEX `health_days_owner_import` ON `health_days` (`owner_id`,`import_id`);--> statement-breakpoint
CREATE TABLE `health_imports` (
	`id` text PRIMARY KEY NOT NULL,
	`owner_id` text NOT NULL,
	`payload_sha256` text NOT NULL,
	`schema_version` integer NOT NULL,
	`source` text NOT NULL,
	`generated_at` text NOT NULL,
	`generated_at_ms` integer NOT NULL,
	`timezone` text NOT NULL,
	`range_start` text NOT NULL,
	`range_end` text NOT NULL,
	`categories` text NOT NULL,
	`day_count` integer NOT NULL,
	`payload_bytes` integer NOT NULL,
	`imported_at` text NOT NULL,
	`imported_at_ms` integer NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `health_imports_owner_hash` ON `health_imports` (`owner_id`,`payload_sha256`);--> statement-breakpoint
CREATE INDEX `health_imports_owner_imported` ON `health_imports` (`owner_id`,`imported_at_ms`);--> statement-breakpoint
CREATE TABLE `owner_settings` (
	`owner_id` text PRIMARY KEY NOT NULL,
	`chatgpt_access` integer DEFAULT 0 NOT NULL,
	`chatgpt_access_updated_at` text
);
