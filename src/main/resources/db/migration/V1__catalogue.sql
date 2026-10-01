-- Bundled catalogue is global and independent of account sync. Custom exercises
-- and their ownership/revisions will be introduced in a later migration.
CREATE TABLE catalogue_versions (
    version text PRIMARY KEY,
    manifest_sha256 text NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE muscle_groups (
    id uuid PRIMARY KEY,
    slug text NOT NULL UNIQUE,
    name text NOT NULL
);

CREATE TABLE equipment (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    type text NOT NULL,
    usage_type text
);

CREATE TABLE exercises (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    type text NOT NULL,
    mechanics text,
    category text,
    instructions jsonb,
    difficulty_level text,
    force_type text,
    is_user_created boolean NOT NULL DEFAULT false CHECK (is_user_created = false)
);

CREATE TABLE exercise_muscle_groups (
    exercise_id uuid NOT NULL REFERENCES exercises(id),
    muscle_group_id uuid NOT NULL REFERENCES muscle_groups(id),
    type text NOT NULL,
    PRIMARY KEY (exercise_id, muscle_group_id, type)
);

CREATE TABLE exercise_equipment (
    exercise_id uuid NOT NULL REFERENCES exercises(id),
    equipment_id uuid NOT NULL REFERENCES equipment(id),
    num_items integer CHECK (num_items IS NULL OR num_items >= 0),
    PRIMARY KEY (exercise_id, equipment_id)
);
CREATE INDEX exercise_muscle_groups_by_muscle ON exercise_muscle_groups (muscle_group_id, exercise_id);
CREATE INDEX exercise_equipment_by_equipment ON exercise_equipment (equipment_id, exercise_id);
