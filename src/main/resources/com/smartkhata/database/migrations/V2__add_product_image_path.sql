-- SmartKhata Schema Migration V2
-- Adds nullable image_path column to products table for product thumbnails

ALTER TABLE products ADD COLUMN image_path TEXT;

