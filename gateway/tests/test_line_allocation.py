"""Entitlement claim + DID/SIP allocation against a mocked Plivo API."""

from __future__ import annotations

import uuid
from typing import Any, Optional

import pytest
from pydantic import SecretStr

from app.api import main as main_module
from app.config import settings
from app.services.plivo_provisioning import PlivoProvisioningClient


class FakePlivoHttp:
    def __init__(self, search_numbers: Optional[list[str]] = None):
        self.search_numbers = list(search_numbers or ["14155550999"])
        self.created_endpoints: list[dict[str, Any]] = []
        self.bought: list[str] = []
        self.attached: list[str] = []

    async def request(
        self,
        method: str,
        path: str,
        *,
        json: Optional[dict[str, Any]] = None,
        params: Optional[dict[str, Any]] = None,
    ) -> dict[str, Any]:
        if method == "POST" and path == "/Endpoint/":
            assert json is not None
            username = f"{json['username']}123456789012"
            payload = {
                "api_id": "test",
                "endpoint_id": f"ep-{len(self.created_endpoints) + 1}",
                "username": username,
                "message": "created",
            }
            self.created_endpoints.append(payload)
            return payload
        if method == "GET" and path == "/PhoneNumber/":
            return {
                "objects": [{"number": number} for number in self.search_numbers]
            }
        if method == "POST" and path.startswith("/PhoneNumber/"):
            number = path.strip("/").split("/")[-1]
            self.bought.append(number)
            return {
                "status": "fulfilled",
                "numbers": [{"number": number, "status": "Success"}],
            }
        if method == "POST" and path.startswith("/Number/"):
            self.attached.append(path)
            return {"message": "changed", "api_id": "test"}
        raise AssertionError(f"unexpected Plivo call {method} {path}")


@pytest.fixture
def fake_plivo(monkeypatch):
    http = FakePlivoHttp()
    client = PlivoProvisioningClient(
        http,
        application_id="APPTEST",
        number_country="US",
        number_type="local",
        sip_domain="phone.plivo.com",
    )
    monkeypatch.setattr(main_module, "_plivo_provisioning_client", lambda: client)
    monkeypatch.setattr(settings, "entitlement_stub_mode", True)
    monkeypatch.setattr(settings, "entitlement_product_id", "triplex.line.v1")
    monkeypatch.setattr(settings, "entitlement_stub_secret", SecretStr("unlock-me"))
    monkeypatch.setattr(settings, "admin_api_key", SecretStr("test-admin-key"))
    monkeypatch.setattr(settings, "plivo_auth_id", SecretStr("MAYTESTAUTHID"))
    return http


async def _register(client) -> dict[str, str]:
    email = f"{uuid.uuid4().hex}@example.test"
    response = await client.post(
        "/auth/register",
        json={"email": email, "phone_number": "+14155550123"},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    return {"X-Device-Token": body["device_token"]}


@pytest.mark.asyncio
async def test_claim_without_stub_is_rejected(client, fake_plivo):
    auth = await _register(client)
    response = await client.post(
        "/entitlements/claim",
        headers=auth,
        json={"product_id": "triplex.line.v1"},
    )
    assert response.status_code == 402
    assert fake_plivo.created_endpoints == []


@pytest.mark.asyncio
async def test_claim_with_stub_allocates_endpoint_and_number(client, fake_plivo, plivo):
    auth = await _register(client)
    response = await client.post(
        "/entitlements/claim",
        headers=auth,
        json={
            "product_id": "triplex.line.v1",
            "stub_unlock": "unlock-me",
            "purchase_token": "stub.device-1",
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["status"] == "allocated"
    assert body["did"] == "+14155550999"
    assert body["sip"]["username"].startswith("tx")
    assert body["sip"]["domain"] == "phone.plivo.com"
    assert body["entitlement_source"] == "stub"
    assert len(fake_plivo.created_endpoints) == 1
    assert fake_plivo.bought == ["14155550999"]

    line = await client.get("/devices/line", headers=auth)
    assert line.status_code == 200
    assert line.json()["did"] == "+14155550999"

    creds = await client.get("/devices/sip-credentials", headers=auth)
    assert creds.status_code == 200
    assert creds.json()["username"] == body["sip"]["username"]

    # Device advertises readiness, then inbound to the allocated DID bridges.
    await client.post(
        "/devices/register",
        headers=auth,
        json={"sip_endpoint": f"sip:{body['sip']['username']}@phone.plivo.com"},
    )
    await client.post(
        "/devices/ready",
        headers=auth,
        params={"ready": "true", "media_ready": "true"},
    )
    answer = await plivo.post(
        "/answer",
        {
            "CallUUID": uuid.uuid4().hex,
            "From": "14155550000",
            "To": "14155550999",
            "Direction": "inbound",
        },
    )
    assert answer.status_code == 200
    assert f"<User>sip:{body['sip']['username']}@phone.plivo.com</User>" in answer.text


@pytest.mark.asyncio
async def test_second_claim_is_idempotent(client, fake_plivo):
    auth = await _register(client)
    first = await client.post(
        "/entitlements/claim",
        headers=auth,
        json={"product_id": "triplex.line.v1", "purchase_token": "stub.first"},
    )
    assert first.status_code == 200, first.text
    second = await client.post(
        "/entitlements/claim",
        headers=auth,
        json={"product_id": "triplex.line.v1", "purchase_token": "stub.second"},
    )
    assert second.status_code == 200, second.text
    assert second.json()["status"] == "existing"
    assert second.json()["did"] == first.json()["did"]
    assert second.json()["sip"]["username"] == first.json()["sip"]["username"]
    assert len(fake_plivo.created_endpoints) == 1
    assert len(fake_plivo.bought) == 1


@pytest.mark.asyncio
async def test_inventory_preferred_over_buy(client, fake_plivo):
    seed = await client.post(
        "/admin/inventory/numbers",
        headers={"X-Admin-Key": "test-admin-key"},
        json={"numbers": ["+14155550888"]},
    )
    assert seed.status_code == 200, seed.text
    assert seed.json()["seeded"] == ["+14155550888"]

    auth = await _register(client)
    response = await client.post(
        "/entitlements/claim",
        headers=auth,
        json={"product_id": "triplex.line.v1", "stub_unlock": "unlock-me"},
    )
    assert response.status_code == 200, response.text
    assert response.json()["did"] == "+14155550888"
    assert fake_plivo.bought == []
    assert len(fake_plivo.created_endpoints) == 1


@pytest.mark.asyncio
async def test_sip_credentials_require_entitlement_when_unprovisioned(client, fake_plivo):
    auth = await _register(client)
    response = await client.get("/devices/sip-credentials", headers=auth)
    assert response.status_code == 402
