import pytest
from conftest import TEST_SETTINGS
from pydantic import ValidationError

from app.config import GatewaySettings


def test_production_settings_have_no_operational_fallbacks(monkeypatch):
    for name in TEST_SETTINGS:
        monkeypatch.delenv(name, raising=False)
    with pytest.raises(ValidationError):
        GatewaySettings(_env_file=None)
