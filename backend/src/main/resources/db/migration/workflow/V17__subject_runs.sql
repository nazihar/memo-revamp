-- Phase 4 E07 T4 (ADR-0068 amendment, design E07 §1.10): an item whose subject type runs again (WorkflowSubject.runsAgain,
-- the reopened action) gets a new instance after its last one ended. Each instance of an item is a numbered run; the
-- first is 1, as every existing instance is. Only one run of an item is ever running. Subject types that do not run
-- again keep one instance for the item's whole life (design E01 §2, invariant 1): the engine refuses a second start.
--
-- Expand only: one column with a default (no row is rewritten) and the uniqueness moved from the item to its runs. An
-- older application running beside this one reads an item's instance as before: no item has a second run until this
-- release reopens an action.
ALTER TABLE workflow_instance ADD COLUMN run int NOT NULL DEFAULT 1 CHECK (run >= 1);
ALTER TABLE workflow_instance DROP CONSTRAINT workflow_instance_tenant_id_subject_type_subject_id_key;
ALTER TABLE workflow_instance
    ADD CONSTRAINT workflow_instance_subject_run_uk UNIQUE (tenant_id, subject_type, subject_id, run);
-- At most one running instance per item, whatever its runs.
CREATE UNIQUE INDEX workflow_instance_subject_running_uk ON workflow_instance (tenant_id, subject_type, subject_id)
    WHERE status = 'RUNNING';
