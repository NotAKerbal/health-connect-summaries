CREATE TABLE `sync_devices` (
	`id` text PRIMARY KEY NOT NULL,
	`owner_id` text NOT NULL,
	`token_hash` text NOT NULL,
	`name` text NOT NULL,
	`paired_at` text NOT NULL,
	`paired_at_ms` integer NOT NULL,
	`revoked_at` text,
	`revoked_reason` text,
	`last_sync_at` text,
	`last_import_id` text,
	`last_attempt_at` text,
	`last_attempt_status` text,
	`last_trigger` text,
	`auto_sync_reported` integer
);
--> statement-breakpoint
CREATE UNIQUE INDEX `sync_devices_token_hash` ON `sync_devices` (`token_hash`);--> statement-breakpoint
CREATE INDEX `sync_devices_owner` ON `sync_devices` (`owner_id`,`paired_at_ms`);--> statement-breakpoint
CREATE UNIQUE INDEX `sync_devices_one_active` ON `sync_devices` (`owner_id`) WHERE revoked_at IS NULL;--> statement-breakpoint
CREATE TABLE `sync_pairings` (
	`token_hash` text PRIMARY KEY NOT NULL,
	`owner_id` text NOT NULL,
	`created_at_ms` integer NOT NULL,
	`expires_at_ms` integer NOT NULL,
	`redeemed_at_ms` integer,
	`device_id` text
);
--> statement-breakpoint
CREATE INDEX `sync_pairings_owner` ON `sync_pairings` (`owner_id`);--> statement-breakpoint
ALTER TABLE `health_imports` ADD `upload_method` text DEFAULT 'browser_upload' NOT NULL;--> statement-breakpoint
ALTER TABLE `health_imports` ADD `device_id` text;