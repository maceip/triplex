from datetime import datetime, timezone
from typing import Literal, Optional
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models.schemas import AuditLog, TaskStatus
from ..db.models import AuditLogDB, TaskDefinitionDB


class TaskService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_task(
        self,
        user_id: UUID,
        task_type: str,
        destination_number: str,
        task_params: dict,
    ) -> TaskDefinitionDB:
        task = TaskDefinitionDB(
            user_id=user_id,
            task_type=task_type,
            destination_number=destination_number,
            task_params=task_params,
        )
        self.db.add(task)
        await self.db.commit()
        await self.db.refresh(task)
        return task

    async def get_task(self, task_id: UUID) -> Optional[TaskDefinitionDB]:
        result = await self.db.execute(
            select(TaskDefinitionDB).where(TaskDefinitionDB.id == task_id)
        )
        return result.scalar_one_or_none()

    async def list_user_tasks(
        self,
        user_id: UUID,
        status: Optional[TaskStatus] = None,
        limit: int = 50,
    ) -> list[TaskDefinitionDB]:
        query = select(TaskDefinitionDB).where(TaskDefinitionDB.user_id == user_id)
        
        if status:
            query = query.where(TaskDefinitionDB.status == status)
        
        query = query.order_by(TaskDefinitionDB.created_at.desc()).limit(limit)
        result = await self.db.execute(query)
        return list(result.scalars().all())

    async def start_task(self, task_id: UUID) -> Optional[TaskDefinitionDB]:
        task = await self.get_task(task_id)
        if not task or task.status != "pending":
            return None
        
        task.status = "active"
        task.started_at = datetime.now(timezone.utc)
        await self.db.commit()
        await self.db.refresh(task)
        return task

    async def complete_task(
        self,
        task_id: UUID,
        outcome: str,
        outcome_evidence: dict,
    ) -> Optional[TaskDefinitionDB]:
        task = await self.get_task(task_id)
        if not task or task.status != "active":
            return None
        
        task.status = "completed"
        task.outcome = outcome
        task.outcome_evidence = outcome_evidence
        task.completed_at = datetime.now(timezone.utc)
        await self.db.commit()
        await self.db.refresh(task)
        return task

    async def stop_task(self, task_id: UUID) -> Optional[TaskDefinitionDB]:
        task = await self.get_task(task_id)
        if not task or task.status not in ("pending", "active"):
            return None
        
        task.status = "stopped"
        task.completed_at = datetime.now(timezone.utc)
        await self.db.commit()
        await self.db.refresh(task)
        return task


class AuditService:
    def __init__(self, db: AsyncSession, emit: bool = True):
        self.db = db
        self.emit = emit

    async def log(
        self,
        user_id: Optional[UUID],
        event_type: str,
        event_data: dict,
        placement: Optional[Literal["LOCAL", "REMOTE_ASR", "REMOTE_REASONING", "REMOTE_TTS", "REMOTE_AGENT"]] = None,
        latency_ms: Optional[int] = None,
    ) -> AuditLogDB:
        if not self.emit:
            return None
        
        log = AuditLogDB(
            user_id=user_id,
            event_type=event_type,
            event_data=event_data,
            placement=placement,
            latency_ms=latency_ms,
        )
        self.db.add(log)
        await self.db.commit()
        return log
