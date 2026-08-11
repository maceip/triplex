"""Bring an existing database up to the columns the models declare.

``Base.metadata.create_all()`` creates *tables* that do not exist. It does
nothing to tables that do — so adding a column to a model and deploying it
leaves every existing database without that column, and every ORM query blows
up on the spot with an undefined-column error, because SQLAlchemy names all the
mapped columns in its SELECT list. The failure is total and immediate: not the
new feature degrading, but enrollment, device status and call routing all
returning 500 on a service that was working a minute earlier.

This closes that hole for the case ``create_all`` cannot handle, and only that
case. It is deliberately narrow:

* it **only ever adds columns** — never drops, renames, retypes, or reorders,
  so it cannot destroy data even if the models and the database disagree;
* it only adds a column that is safe to add to a table with rows in it, which
  means nullable or carrying a server default. A ``NOT NULL`` column with no
  default cannot be added to a populated table by any means, so it is reported
  rather than attempted;
* it touches nothing else. Index changes, type widening, and constraint changes
  are exactly the operations that need a human and a maintenance window.

It is a stopgap, not a migration tool. `alembic.ini` has pointed at a
`script_location` that does not exist since the repository was created; the
right end state is real migrations with a baseline, and at that point this
module should be deleted. Until then, this is the difference between "a new
column ships" and "the gateway is down".
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from sqlalchemy import MetaData, Table, text
from sqlalchemy.engine import Connection
from sqlalchemy.schema import CreateColumn

logger = logging.getLogger("triplex.gateway.schema")


@dataclass(frozen=True)
class SchemaChange:
    table: str
    column: str
    ddl: str


@dataclass(frozen=True)
class SchemaSyncResult:
    applied: list[SchemaChange]
    #: Columns the models declare that cannot be added safely to a populated
    #: table. Surfaced rather than attempted — the service still starts, and
    #: the log says exactly what a human has to do.
    unsafe: list[SchemaChange]


def sync_additive_columns(connection: Connection, metadata: MetaData) -> SchemaSyncResult:
    """Add model columns that are missing from existing tables.

    Runs inside the caller's transaction, so a failure rolls the whole thing
    back rather than leaving the schema half-updated.
    """
    inspector = _inspector(connection)
    existing_tables = set(inspector.get_table_names())
    applied: list[SchemaChange] = []
    unsafe: list[SchemaChange] = []

    for table in metadata.sorted_tables:
        if table.name not in existing_tables:
            # create_all made it, or is about to, with every column present.
            continue
        present = {column["name"] for column in inspector.get_columns(table.name)}
        for column in table.columns:
            if column.name in present:
                continue
            change = SchemaChange(
                table=table.name,
                column=column.name,
                ddl=_add_column_ddl(connection, table, column),
            )
            if not _is_safe_to_add(column):
                unsafe.append(change)
                logger.error(
                    "column %s.%s cannot be added to a populated table: it is "
                    "NOT NULL with no server default. Add it by hand with a "
                    "backfill, or give the model a server_default.",
                    table.name,
                    column.name,
                )
                continue
            logger.warning("applying additive schema change: %s", change.ddl)
            connection.execute(text(change.ddl))
            applied.append(change)

    return SchemaSyncResult(applied=applied, unsafe=unsafe)


def _inspector(connection: Connection):
    from sqlalchemy import inspect

    return inspect(connection)


def _is_safe_to_add(column) -> bool:
    """A column is safe on a populated table if existing rows can get a value."""
    return bool(column.nullable) or column.server_default is not None


def _add_column_ddl(connection: Connection, table: Table, column) -> str:
    """Render `ALTER TABLE ... ADD COLUMN`, letting the dialect spell the type.

    `CreateColumn` produces the same column clause `create_all` would have
    written, so a column added here is indistinguishable from one created with
    the table — including its type, nullability and server default.
    """
    column_clause = CreateColumn(column).compile(bind=connection).string
    return f'ALTER TABLE "{table.name}" ADD COLUMN {column_clause}'
