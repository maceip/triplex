"""Idempotently provision the API-only Chime pool, SIP app, and phone rule."""

from __future__ import annotations

import argparse
import json
import time
import uuid
from typing import Any, cast

import boto3


def ensure_pool(client: Any, name: str, region: str, retention_hours: int) -> str:
    try:
        result = client.get_media_pipeline_kinesis_video_stream_pool(Identifier=name)
        configuration = result["KinesisVideoStreamPoolConfiguration"]
    except client.exceptions.NotFoundException:
        result = client.create_media_pipeline_kinesis_video_stream_pool(
            StreamConfiguration={
                "Region": region,
                "DataRetentionInHours": retention_hours,
            },
            PoolName=name,
            ClientRequestToken=uuid.uuid4().hex,
            Tags=[{"Key": "triplex:managed", "Value": "true"}],
        )
        configuration = result["KinesisVideoStreamPoolConfiguration"]
    stream_configuration = configuration.get("StreamConfiguration", {})
    if (
        configuration.get("PoolStatus") == "ACTIVE"
        and stream_configuration.get("DataRetentionInHours") != retention_hours
    ):
        configuration = client.update_media_pipeline_kinesis_video_stream_pool(
            Identifier=name,
            StreamConfiguration={"DataRetentionInHours": retention_hours},
        )["KinesisVideoStreamPoolConfiguration"]
    for _ in range(60):
        if configuration["PoolStatus"] == "ACTIVE":
            return str(configuration["PoolArn"])
        if configuration["PoolStatus"] in {"FAILED", "DELETING"}:
            raise RuntimeError(f"KVS pool entered {configuration['PoolStatus']}")
        time.sleep(5)
        configuration = client.get_media_pipeline_kinesis_video_stream_pool(
            Identifier=name
        )["KinesisVideoStreamPoolConfiguration"]
    raise TimeoutError("KVS pool did not become ACTIVE within five minutes")


def ensure_sip_application(
    client: Any, name: str, region: str, lambda_arn: str
) -> dict[str, Any]:
    token: str | None = None
    while True:
        options = {"NextToken": token} if token else {}
        response = client.list_sip_media_applications(**options)
        existing = next(
            (item for item in response["SipMediaApplications"] if item["Name"] == name),
            None,
        )
        if existing:
            result = client.update_sip_media_application(
                SipMediaApplicationId=existing["SipMediaApplicationId"],
                Name=name,
                Endpoints=[{"LambdaArn": lambda_arn}],
            )["SipMediaApplication"]
            return dict(cast(dict[str, Any], result))
        token = response.get("NextToken")
        if not token:
            break
    result = client.create_sip_media_application(
        AwsRegion=region,
        Name=name,
        Endpoints=[{"LambdaArn": lambda_arn}],
        Tags=[{"Key": "triplex:managed", "Value": "true"}],
    )["SipMediaApplication"]
    return dict(cast(dict[str, Any], result))


def ensure_sip_rule(
    client: Any, name: str, phone_number: str, region: str, application_id: str
) -> dict[str, Any]:
    target = [{"SipMediaApplicationId": application_id, "Priority": 1, "AwsRegion": region}]
    token: str | None = None
    while True:
        options = {"NextToken": token} if token else {}
        response = client.list_sip_rules(**options)
        existing = next(
            (item for item in response["SipRules"] if item["Name"] == name), None
        )
        if existing:
            if (
                existing.get("TriggerType") != "ToPhoneNumber"
                or existing.get("TriggerValue") != phone_number
            ):
                raise ValueError(
                    f"SIP rule {name!r} already exists for a different trigger"
                )
            result = client.update_sip_rule(
                SipRuleId=existing["SipRuleId"],
                Name=name,
                Disabled=False,
                TargetApplications=target,
            )["SipRule"]
            return dict(cast(dict[str, Any], result))
        token = response.get("NextToken")
        if not token:
            break
    result = client.create_sip_rule(
        Name=name,
        TriggerType="ToPhoneNumber",
        TriggerValue=phone_number,
        Disabled=False,
        TargetApplications=target,
    )["SipRule"]
    return dict(cast(dict[str, Any], result))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--lambda-arn")
    parser.add_argument("--phone-number")
    parser.add_argument("--pool-name", default="triplex-audio")
    parser.add_argument("--retention-hours", type=int, default=24)
    arguments = parser.parse_args()

    pool_arn = ensure_pool(
        boto3.client("chime-sdk-media-pipelines", region_name=arguments.region),
        arguments.pool_name,
        arguments.region,
        arguments.retention_hours,
    )
    if bool(arguments.lambda_arn) != bool(arguments.phone_number):
        parser.error("--lambda-arn and --phone-number must be supplied together")
    result = {"KvsPoolArn": pool_arn}
    if arguments.lambda_arn and arguments.phone_number:
        voice = boto3.client("chime-sdk-voice", region_name=arguments.region)
        application = ensure_sip_application(
            voice, "triplex-sip-application", arguments.region, arguments.lambda_arn
        )
        rule = ensure_sip_rule(
            voice,
            "triplex-inbound-rule",
            arguments.phone_number,
            arguments.region,
            application["SipMediaApplicationId"],
        )
        result.update(
            {
                "SipMediaApplicationId": application["SipMediaApplicationId"],
                "SipRuleId": rule["SipRuleId"],
            }
        )
    print(
        json.dumps(
            result,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
