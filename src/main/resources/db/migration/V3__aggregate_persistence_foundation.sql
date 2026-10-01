CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

ALTER TABLE exercises
    ADD COLUMN owner_id uuid REFERENCES accounts(id),
    ADD COLUMN created_at timestamptz,
    ADD COLUMN updated_at timestamptz,
    ADD COLUMN revision bigint,
    ADD COLUMN deleted_at timestamptz;
ALTER TABLE exercises ADD CONSTRAINT exercises_catalogue_or_custom CHECK (
    (owner_id IS NULL AND is_user_created = false AND revision IS NULL AND deleted_at IS NULL)
 OR (owner_id IS NOT NULL AND is_user_created = true AND revision > 0 AND created_at IS NOT NULL AND updated_at IS NOT NULL)
);
CREATE UNIQUE INDEX exercises_custom_id_owner ON exercises(id, owner_id);

CREATE TABLE workouts (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES accounts(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    revision bigint NOT NULL CHECK (revision > 0),
    deleted_at timestamptz,
    name text NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    status text NOT NULL CHECK (status IN ('active','finished')),
    UNIQUE (id, owner_id),
    CHECK ((status = 'active' AND finished_at IS NULL) OR (status = 'finished' AND finished_at IS NOT NULL))
);
CREATE INDEX workouts_owner_status_started ON workouts(owner_id, status, started_at DESC);
CREATE UNIQUE INDEX workouts_one_live_active_per_account ON workouts(owner_id)
    WHERE status = 'active' AND deleted_at IS NULL;

CREATE TABLE routines (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES accounts(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    revision bigint NOT NULL CHECK (revision > 0),
    deleted_at timestamptz,
    name text NOT NULL,
    is_draft boolean NOT NULL,
    UNIQUE (id, owner_id)
);

CREATE TABLE workout_exercises (
    id uuid PRIMARY KEY,
    workout_id uuid NOT NULL REFERENCES workouts(id) ON DELETE RESTRICT,
    exercise_id uuid NOT NULL REFERENCES exercises(id),
    position integer NOT NULL CHECK (position >= 0),
    notes text,
    rest_duration_seconds integer NOT NULL CHECK (rest_duration_seconds >= 0),
    UNIQUE (workout_id, position),
    UNIQUE (id, workout_id)
);
CREATE INDEX workout_exercises_by_exercise ON workout_exercises(exercise_id, workout_id);
CREATE TABLE workout_sets (
    id uuid PRIMARY KEY,
    workout_exercise_id uuid NOT NULL REFERENCES workout_exercises(id) ON DELETE RESTRICT,
    position integer NOT NULL CHECK (position >= 0),
    repetitions integer CHECK (repetitions IS NULL OR repetitions >= 0),
    weight_kg double precision CHECK (weight_kg IS NULL OR (weight_kg >= 0 AND weight_kg < 'Infinity'::float8)),
    rest_seconds integer CHECK (rest_seconds IS NULL OR rest_seconds >= 0),
    is_completed boolean NOT NULL,
    UNIQUE (workout_exercise_id, position)
);
CREATE TABLE routine_exercises (
    id uuid PRIMARY KEY,
    routine_id uuid NOT NULL REFERENCES routines(id) ON DELETE RESTRICT,
    exercise_id uuid NOT NULL REFERENCES exercises(id),
    position integer NOT NULL CHECK (position >= 0),
    rest_duration_seconds integer NOT NULL CHECK (rest_duration_seconds >= 0),
    UNIQUE (routine_id, position),
    UNIQUE (id, routine_id)
);
CREATE INDEX routine_exercises_by_exercise ON routine_exercises(exercise_id, routine_id);
CREATE TABLE routine_sets (
    id uuid PRIMARY KEY,
    routine_exercise_id uuid NOT NULL REFERENCES routine_exercises(id) ON DELETE RESTRICT,
    position integer NOT NULL CHECK (position >= 0),
    repetitions integer CHECK (repetitions IS NULL OR repetitions >= 0),
    weight_kg double precision CHECK (weight_kg IS NULL OR (weight_kg >= 0 AND weight_kg < 'Infinity'::float8)),
    UNIQUE (routine_exercise_id, position)
);

-- Reservations intentionally have no child-row FK: deleting an entry/set keeps its
-- account/root/parent binding for the account lifetime.
CREATE TABLE root_id_reservations (
    aggregate_type text NOT NULL CHECK (aggregate_type IN ('workout','routine','customExercise')),
    aggregate_id uuid NOT NULL,
    owner_id uuid NOT NULL REFERENCES accounts(id),
    PRIMARY KEY (aggregate_type, aggregate_id)
);
CREATE TABLE child_id_reservations (
    family text NOT NULL CHECK (family IN ('workout','routine')),
    child_id uuid NOT NULL,
    owner_id uuid NOT NULL REFERENCES accounts(id),
    root_id uuid NOT NULL,
    role text NOT NULL CHECK (role IN ('root','entry','set')),
    parent_id uuid,
    PRIMARY KEY (family, child_id),
    CHECK ((role IN ('root','entry') AND parent_id IS NULL) OR (role = 'set' AND parent_id IS NOT NULL))
);

CREATE FUNCTION reserve_root_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE kind text := TG_ARGV[0];
BEGIN
    IF NEW.deleted_at IS NOT NULL THEN RAISE EXCEPTION 'new roots cannot start tombstoned' USING ERRCODE='23514'; END IF;
    INSERT INTO root_id_reservations(aggregate_type, aggregate_id, owner_id)
    VALUES (kind, NEW.id, NEW.owner_id);
    IF kind IN ('workout','routine') THEN
        INSERT INTO child_id_reservations(family, child_id, owner_id, root_id, role, parent_id)
        VALUES (kind, NEW.id, NEW.owner_id, NEW.id, 'root', NULL);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER workouts_reserve_identity BEFORE INSERT ON workouts
    FOR EACH ROW EXECUTE FUNCTION reserve_root_identity('workout');
CREATE TRIGGER routines_reserve_identity BEFORE INSERT ON routines
    FOR EACH ROW EXECUTE FUNCTION reserve_root_identity('routine');
CREATE TRIGGER custom_exercises_reserve_identity BEFORE INSERT ON exercises
    FOR EACH ROW WHEN (NEW.owner_id IS NOT NULL) EXECUTE FUNCTION reserve_root_identity('customExercise');

CREATE FUNCTION prevent_root_identity_rebinding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.owner_id IS DISTINCT FROM OLD.owner_id THEN
        RAISE EXCEPTION 'root identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER workouts_identity_immutable BEFORE UPDATE OF id, owner_id ON workouts
    FOR EACH ROW EXECUTE FUNCTION prevent_root_identity_rebinding();
CREATE TRIGGER routines_identity_immutable BEFORE UPDATE OF id, owner_id ON routines
    FOR EACH ROW EXECUTE FUNCTION prevent_root_identity_rebinding();
CREATE TRIGGER custom_exercise_identity_immutable BEFORE UPDATE OF id, owner_id ON exercises
    FOR EACH ROW WHEN (OLD.owner_id IS NOT NULL) EXECUTE FUNCTION prevent_root_identity_rebinding();

CREATE FUNCTION prevent_tombstoned_root_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.deleted_at IS NOT NULL AND (
        (to_jsonb(NEW) - 'revision' - 'updated_at') IS DISTINCT FROM (to_jsonb(OLD) - 'revision' - 'updated_at')
        OR NEW.revision <= OLD.revision OR NEW.updated_at < OLD.updated_at
    ) THEN
        RAISE EXCEPTION 'tombstoned root permits revision metadata bumps only' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER workouts_tombstone_immutable BEFORE UPDATE ON workouts
    FOR EACH ROW EXECUTE FUNCTION prevent_tombstoned_root_mutation();
CREATE TRIGGER routines_tombstone_immutable BEFORE UPDATE ON routines
    FOR EACH ROW EXECUTE FUNCTION prevent_tombstoned_root_mutation();
CREATE TRIGGER custom_exercise_tombstone_immutable BEFORE UPDATE ON exercises
    FOR EACH ROW WHEN (OLD.owner_id IS NOT NULL) EXECUTE FUNCTION prevent_tombstoned_root_mutation();

CREATE FUNCTION reserve_child_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner uuid; root uuid; family_name text; parent uuid; role_name text; deleted timestamptz;
        reserved child_id_reservations%ROWTYPE;
BEGIN
    family_name := TG_ARGV[0]; role_name := TG_ARGV[1];
    IF TG_OP = 'UPDATE' AND NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'child UUID is immutable' USING ERRCODE='23514';
    END IF;
    IF role_name = 'entry' THEN
        parent := NULL;
        IF family_name = 'workout' THEN root := NEW.workout_id; SELECT owner_id,deleted_at INTO owner,deleted FROM workouts WHERE id=root;
        ELSE root := NEW.routine_id; SELECT owner_id,deleted_at INTO owner,deleted FROM routines WHERE id=root; END IF;
    ELSE
        IF family_name = 'workout' THEN
            SELECT we.workout_id, w.owner_id, w.deleted_at INTO root, owner, deleted FROM workout_exercises we JOIN workouts w ON w.id=we.workout_id WHERE we.id=NEW.workout_exercise_id;
            parent := NEW.workout_exercise_id;
        ELSE
            SELECT re.routine_id, r.owner_id, r.deleted_at INTO root, owner, deleted FROM routine_exercises re JOIN routines r ON r.id=re.routine_id WHERE re.id=NEW.routine_exercise_id;
            parent := NEW.routine_exercise_id;
        END IF;
    END IF;
    IF owner IS NULL OR deleted IS NOT NULL THEN
        RAISE EXCEPTION 'cannot mutate children of missing or tombstoned root' USING ERRCODE='23503';
    END IF;
    INSERT INTO child_id_reservations(family, child_id, owner_id, root_id, role, parent_id)
    VALUES (family_name, NEW.id, owner, root, role_name, parent) ON CONFLICT DO NOTHING;
    SELECT * INTO reserved FROM child_id_reservations WHERE family=family_name AND child_id=NEW.id;
    IF reserved.owner_id IS DISTINCT FROM owner OR reserved.root_id IS DISTINCT FROM root
       OR reserved.role IS DISTINCT FROM role_name OR reserved.parent_id IS DISTINCT FROM parent THEN
        RAISE EXCEPTION 'child identity reservation conflict' USING ERRCODE='23505';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER workout_entries_reserve_identity BEFORE INSERT OR UPDATE ON workout_exercises
    FOR EACH ROW EXECUTE FUNCTION reserve_child_identity('workout','entry');
CREATE TRIGGER workout_sets_reserve_identity BEFORE INSERT OR UPDATE ON workout_sets
    FOR EACH ROW EXECUTE FUNCTION reserve_child_identity('workout','set');
CREATE TRIGGER routine_entries_reserve_identity BEFORE INSERT OR UPDATE ON routine_exercises
    FOR EACH ROW EXECUTE FUNCTION reserve_child_identity('routine','entry');
CREATE TRIGGER routine_sets_reserve_identity BEFORE INSERT OR UPDATE ON routine_sets
    FOR EACH ROW EXECUTE FUNCTION reserve_child_identity('routine','set');

CREATE FUNCTION prevent_tombstoned_child_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE deleted timestamptz; account_deleted timestamptz; root uuid; owner uuid;
BEGIN
    IF TG_TABLE_NAME='workout_exercises' THEN root:=OLD.workout_id; SELECT owner_id,deleted_at INTO owner,deleted FROM workouts WHERE id=root;
    ELSIF TG_TABLE_NAME='routine_exercises' THEN root:=OLD.routine_id; SELECT owner_id,deleted_at INTO owner,deleted FROM routines WHERE id=root;
    ELSIF TG_TABLE_NAME='workout_sets' THEN SELECT w.owner_id,w.deleted_at INTO owner,deleted FROM workout_exercises e JOIN workouts w ON w.id=e.workout_id WHERE e.id=OLD.workout_exercise_id;
    ELSE SELECT r.owner_id,r.deleted_at INTO owner,deleted FROM routine_exercises e JOIN routines r ON r.id=e.routine_id WHERE e.id=OLD.routine_exercise_id;
    END IF;
    IF deleted IS NOT NULL THEN
        SELECT accounts.deleted_at INTO account_deleted FROM accounts WHERE id=owner;
        IF account_deleted IS NULL THEN RAISE EXCEPTION 'cannot mutate children of tombstoned root' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER workout_entries_tombstone_delete BEFORE DELETE ON workout_exercises FOR EACH ROW EXECUTE FUNCTION prevent_tombstoned_child_delete();
CREATE TRIGGER workout_sets_tombstone_delete BEFORE DELETE ON workout_sets FOR EACH ROW EXECUTE FUNCTION prevent_tombstoned_child_delete();
CREATE TRIGGER routine_entries_tombstone_delete BEFORE DELETE ON routine_exercises FOR EACH ROW EXECUTE FUNCTION prevent_tombstoned_child_delete();
CREATE TRIGGER routine_sets_tombstone_delete BEFORE DELETE ON routine_sets FOR EACH ROW EXECUTE FUNCTION prevent_tombstoned_child_delete();
CREATE FUNCTION enforce_exercise_visibility() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE account uuid; exercise_owner uuid; user_created boolean;
BEGIN
    IF TG_TABLE_NAME = 'workout_exercises' THEN SELECT owner_id INTO account FROM workouts WHERE id=NEW.workout_id;
    ELSE SELECT owner_id INTO account FROM routines WHERE id=NEW.routine_id; END IF;
    SELECT owner_id, is_user_created INTO exercise_owner, user_created FROM exercises WHERE id=NEW.exercise_id;
    IF NOT FOUND OR (user_created AND exercise_owner IS DISTINCT FROM account) THEN
        RAISE EXCEPTION 'exercise not visible to aggregate owner' USING ERRCODE='23503';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER workout_exercise_visibility BEFORE INSERT OR UPDATE OF exercise_id, workout_id ON workout_exercises
    FOR EACH ROW EXECUTE FUNCTION enforce_exercise_visibility();
CREATE TRIGGER routine_exercise_visibility BEFORE INSERT OR UPDATE OF exercise_id, routine_id ON routine_exercises
    FOR EACH ROW EXECUTE FUNCTION enforce_exercise_visibility();

CREATE TABLE account_sync_state (
    account_id uuid PRIMARY KEY REFERENCES accounts(id),
    committed_cursor bigint NOT NULL DEFAULT 0 CHECK (committed_cursor >= 0),
    pruned_through bigint NOT NULL DEFAULT 0 CHECK (pruned_through >= 0),
    CHECK (pruned_through <= committed_cursor)
);
CREATE FUNCTION initialize_account_sync_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN INSERT INTO account_sync_state(account_id) VALUES (NEW.id); RETURN NEW; END $$;
CREATE TRIGGER accounts_initialize_sync_state AFTER INSERT ON accounts
    FOR EACH ROW EXECUTE FUNCTION initialize_account_sync_state();
CREATE TABLE sync_change_batches (
    account_id uuid NOT NULL REFERENCES accounts(id),
    cursor bigint NOT NULL CHECK (cursor > 0),
    batch_id uuid NOT NULL UNIQUE,
    committed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (account_id, cursor)
);
CREATE TABLE sync_changes (
    account_id uuid NOT NULL,
    cursor bigint NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    aggregate_type text NOT NULL CHECK (aggregate_type IN ('workout','routine','customExercise')),
    aggregate_id uuid NOT NULL,
    operation text NOT NULL CHECK (operation IN ('upsert','delete')),
    revision bigint NOT NULL CHECK (revision > 0),
    payload jsonb,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (account_id, cursor, position),
    FOREIGN KEY (account_id,cursor) REFERENCES sync_change_batches(account_id,cursor) ON DELETE RESTRICT,
    CHECK ((operation='upsert' AND payload IS NOT NULL) OR (operation='delete' AND payload IS NULL))
);
CREATE INDEX sync_changes_expiry ON sync_changes(account_id, expires_at, cursor);

-- Durable replay storage is only a foundation; request execution/replay is not implemented.
CREATE TABLE processed_mutations (
    owner_id uuid NOT NULL REFERENCES accounts(id),
    mutation_id uuid NOT NULL,
    request_kind text NOT NULL,
    request_hash bytea NOT NULL,
    outcome jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_id, mutation_id)
);

-- A newly installed seed can be inserted before its version marker is written.
-- Once published, global seed rows and bundled relationships are immutable.
CREATE FUNCTION protect_published_catalogue() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE exercise uuid; old_exercise uuid; bundled boolean;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM catalogue_versions WHERE version='gymtracka-catalogue-v1') THEN
        IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;
    IF TG_TABLE_NAME IN ('muscle_groups','equipment') THEN
        RAISE EXCEPTION 'published catalogue is read-only' USING ERRCODE='23514';
    ELSIF TG_TABLE_NAME='exercises' THEN
        IF (TG_OP='INSERT' AND NEW.owner_id IS NULL) OR (TG_OP<>'INSERT' AND OLD.owner_id IS NULL) THEN
            RAISE EXCEPTION 'published catalogue is read-only' USING ERRCODE='23514';
        END IF;
    ELSE
        exercise := CASE WHEN TG_OP='DELETE' THEN OLD.exercise_id ELSE NEW.exercise_id END;
        IF TG_OP='UPDATE' THEN old_exercise := OLD.exercise_id; ELSE old_exercise := exercise; END IF;
        SELECT owner_id IS NULL INTO bundled FROM exercises WHERE id=exercise;
        IF bundled THEN RAISE EXCEPTION 'published catalogue relationship is read-only' USING ERRCODE='23514'; END IF;
        IF TG_OP='UPDATE' THEN
            SELECT owner_id IS NULL INTO bundled FROM exercises WHERE id=old_exercise;
            IF bundled THEN RAISE EXCEPTION 'published catalogue relationship is read-only' USING ERRCODE='23514'; END IF;
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER muscle_groups_catalogue_read_only BEFORE INSERT OR UPDATE OR DELETE ON muscle_groups
    FOR EACH ROW EXECUTE FUNCTION protect_published_catalogue();
CREATE TRIGGER equipment_catalogue_read_only BEFORE INSERT OR UPDATE OR DELETE ON equipment
    FOR EACH ROW EXECUTE FUNCTION protect_published_catalogue();
CREATE TRIGGER exercises_catalogue_read_only BEFORE INSERT OR UPDATE OR DELETE ON exercises
    FOR EACH ROW EXECUTE FUNCTION protect_published_catalogue();
CREATE TRIGGER exercise_muscle_groups_catalogue_read_only BEFORE INSERT OR UPDATE OR DELETE ON exercise_muscle_groups
    FOR EACH ROW EXECUTE FUNCTION protect_published_catalogue();
CREATE TRIGGER exercise_equipment_catalogue_read_only BEFORE INSERT OR UPDATE OR DELETE ON exercise_equipment
    FOR EACH ROW EXECUTE FUNCTION protect_published_catalogue();
