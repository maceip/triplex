"""The upgrade path onto a database that already has the tables.

This is the case `create_all()` silently does nothing about, and the one a
fresh test database never exercises: every test elsewhere starts from an empty
schema, where `create_all` produces every column and nothing is ever missing.
Production is the opposite — the tables have been there for months.

So these tests drop a column back out of a live table to recreate the shape a
deployed database is actually in, and then assert the service can start against
it and answer a request.
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import text

from app.db.models import Base
from app.db.schema_sync import sync_additive_columns


async def drop_column(session_maker, table: str, column: str) -> None:
    async with session_maker() as session:
        await session.execute(text(f'ALTER TABLE "{table}" DROP COLUMN "{column}"'))
        await session.commit()


async def columns_of(session_maker, table: str) -> set[str]:
    async with session_maker() as session:
        rows = await session.execute(
            text(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_name = :table"
            ),
            {"table": table},
        )
        return {row[0] for row in rows}


class TestAdditiveColumnSync:
    async def test_a_column_added_to_a_model_reaches_an_existing_table(
        self, engine, session_maker
    ):
        # The exact shape of a database deployed before `media_ready` existed.
        await drop_column(session_maker, "device_registrations", "media_ready")
        assert "media_ready" not in await columns_of(session_maker, "device_registrations")

        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
            result = await conn.run_sync(
                lambda sync_conn: sync_additive_columns(sync_conn, Base.metadata)
            )

        assert "media_ready" in await columns_of(session_maker, "device_registrations")
        assert [(c.table, c.column) for c in result.applied] == [
            ("device_registrations", "media_ready")
        ]
        assert result.unsafe == []

    async def test_existing_rows_survive_and_get_the_server_default(
        self, engine, session_maker, enrolled
    ):
        await drop_column(session_maker, "device_registrations", "media_ready")

        async with engine.begin() as conn:
            await conn.run_sync(
                lambda sync_conn: sync_additive_columns(sync_conn, Base.metadata)
            )

        async with session_maker() as session:
            row = (
                await session.execute(
                    text(
                        "SELECT device_token, media_ready FROM device_registrations "
                        "WHERE device_token = :token"
                    ),
                    {"token": enrolled.device_token},
                )
            ).one()
        # The registration is still there, and the new column took its default
        # rather than NULL — a device does not become media-ready by upgrade.
        assert row[0] == enrolled.device_token
        assert row[1] is False

    async def test_the_sync_is_idempotent(self, engine, session_maker):
        await drop_column(session_maker, "device_registrations", "media_ready")

        async with engine.begin() as conn:
            first = await conn.run_sync(
                lambda c: sync_additive_columns(c, Base.metadata)
            )
        async with engine.begin() as conn:
            second = await conn.run_sync(
                lambda c: sync_additive_columns(c, Base.metadata)
            )

        assert len(first.applied) == 1
        assert second.applied == [], "a second start must be a no-op"

    async def test_an_up_to_date_database_is_left_alone(self, engine):
        async with engine.begin() as conn:
            result = await conn.run_sync(
                lambda c: sync_additive_columns(c, Base.metadata)
            )

        assert result.applied == []
        assert result.unsafe == []

    async def test_a_not_null_column_without_a_default_is_reported_not_attempted(
        self, engine, session_maker
    ):
        """The one case that cannot be done automatically, handled honestly."""
        from sqlalchemy import Column, MetaData, String, Table

        metadata = MetaData()
        Table(
            "device_registrations",
            metadata,
            Column("id", String, primary_key=True),
            # No default and NOT NULL: no value exists for the rows already
            # there, so no ALTER can succeed. It must not be attempted.
            Column("mandatory_new_field", String, nullable=False),
        )

        async with engine.begin() as conn:
            result = await conn.run_sync(lambda c: sync_additive_columns(c, metadata))

        assert result.applied == []
        assert [(c.table, c.column) for c in result.unsafe] == [
            ("device_registrations", "mandatory_new_field")
        ]
        # And the table is untouched.
        assert "mandatory_new_field" not in await columns_of(
            session_maker, "device_registrations"
        )


class TestServiceStartsAgainstAnOlderDatabase:
    """The failure the review caught, end to end."""

    async def test_device_routing_works_after_upgrading_an_older_database(
        self, engine, session_maker, client, enrolled
    ):
        await drop_column(session_maker, "device_registrations", "media_ready")

        # Before the fix this is where production was: any query naming the
        # column fails outright.
        with pytest.raises(Exception):
            async with session_maker() as session:
                await session.execute(text("SELECT media_ready FROM device_registrations"))

        # Startup reconciles the schema...
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
            await conn.run_sync(lambda c: sync_additive_columns(c, Base.metadata))

        # ...and the endpoints that read device registrations work again.
        ready = await client.post(
            "/devices/ready?ready=true&media_ready=true", headers=enrolled.auth
        )
        assert ready.status_code == 200

        status = await client.get("/devices/status", headers=enrolled.auth)
        assert status.status_code == 200
        assert status.json()["media_ready"] is True
