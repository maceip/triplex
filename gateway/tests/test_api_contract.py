from app.api.main import CreateTaskRequest, app


def test_outbound_authorization_and_readiness_are_public_contracts():
    schema = app.openapi()
    assert "/ready" in schema["paths"]
    operation = schema["paths"]["/tasks/{task_id}/authorize-outbound"]["post"]
    response_schema = operation["responses"]["200"]["content"]["application/json"]
    assert response_schema["schema"]["$ref"].endswith("OutboundRouteGrantResponse")


def test_gateway_uses_maintained_rate_limit_middleware():
    middleware_names = {item.cls.__name__ for item in app.user_middleware}
    assert "SlowAPIMiddleware" in middleware_names


def test_android_task_enum_is_normalized_to_locked_wire_schema():
    request = CreateTaskRequest(
        task_type="APPOINTMENT_MODIFY",
        destination_number="+1 (415) 555-0123",
        task_params={"appointment_id": "abc"},
    )
    assert request.task_type == "appointment_modify"
    assert request.destination_number == "+14155550123"
