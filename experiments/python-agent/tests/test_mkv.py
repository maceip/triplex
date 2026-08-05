"""Incremental KVS Matroska parsing tests."""

from __future__ import annotations

import struct

import av
import numpy as np

from triplex.transport.kvs_consumer import KVSConfig, KVSConsumer
from triplex.transport.mkv import StreamingMatroskaParser


def _id_bytes(element_id: int) -> bytes:
    return element_id.to_bytes((element_id.bit_length() + 7) // 8, "big")


def _size(value: int) -> bytes:
    for length in range(1, 9):
        if value < (1 << (7 * length)) - 1:
            encoded = value | (1 << (7 * length))
            return encoded.to_bytes(length, "big")
    raise ValueError("payload is too large")


def _element(element_id: int, payload: bytes) -> bytes:
    return _id_bytes(element_id) + _size(len(payload)) + payload


def _uint(element_id: int, value: int) -> bytes:
    width = max(1, (value.bit_length() + 7) // 8)
    return _element(element_id, value.to_bytes(width, "big"))


def _text(element_id: int, value: str) -> bytes:
    return _element(element_id, value.encode())


def _simple_block(track: int, timecode: int, payload: bytes) -> bytes:
    assert 0 < track < 127
    block = bytes([0x80 | track]) + struct.pack(">hB", timecode, 0x80) + payload
    return _element(0xA3, block)


def _fragment(
    *frames: bytes,
    continuation_token: str = "next-token",
    codec_id: str = "A_PCM/INT/LIT",
    sample_rate: int = 16000,
    codec_private: bytes | None = None,
) -> bytes:
    ebml_header = _element(0x1A45DFA3, _uint(0x4286, 1))
    audio = _element(
        0xE1,
        _element(0xB5, struct.pack(">d", float(sample_rate)))
        + _uint(0x9F, 1)
        + _uint(0x6264, 16),
    )
    track_entry = _element(
        0xAE,
        _uint(0xD7, 1)
        + _uint(0x83, 2)
        + _text(0x86, codec_id)
        + (_element(0x63A2, codec_private) if codec_private else b"")
        + audio,
    )
    tracks = _element(0x1654AE6B, track_entry)
    cluster = _element(0x1F43B675, _uint(0xE7, 100) + b"".join(frames))
    simple_tag = _element(
        0x67C8,
        _text(0x45A3, "AWS_KINESISVIDEO_CONTINUATION_TOKEN")
        + _text(0x4487, continuation_token),
    )
    tags = _element(0x1254C367, _element(0x7373, simple_tag))
    return ebml_header + _element(0x18538067, tracks + cluster + tags)


def test_parser_extracts_pcm_from_arbitrarily_split_get_media_chunks() -> None:
    first = b"\x01\x02" * 320
    second = b"\x03\x04" * 320
    payload = _fragment(
        _simple_block(1, 0, first),
        _simple_block(1, 20, second),
        continuation_token="resume-here",
    )
    parser = StreamingMatroskaParser()
    output = []

    cursor = 0
    chunk_sizes = [1, 2, 7, 31, 3, 509, 17, 1024]
    index = 0
    while cursor < len(payload):
        chunk_size = chunk_sizes[index % len(chunk_sizes)]
        output.extend(parser.feed(payload[cursor : cursor + chunk_size]))
        cursor += chunk_size
        index += 1
    parser.finish()

    assert [frame.payload for frame in output] == [first, second]
    assert [frame.timestamp_ms for frame in output] == [100.0, 120.0]
    assert parser.tracks[1].codec_id == "A_PCM/INT/LIT"
    assert parser.tracks[1].sample_rate == 16000
    assert parser.continuation_token == "resume-here"


def test_parser_accepts_repeated_kvs_fragment_headers() -> None:
    first = _fragment(_simple_block(1, 0, b"a" * 640), continuation_token="one")
    second = _fragment(_simple_block(1, 0, b"b" * 640), continuation_token="two")
    parser = StreamingMatroskaParser()

    frames = parser.feed(first + second)
    parser.finish()

    assert [frame.payload for frame in frames] == [b"a" * 640, b"b" * 640]
    assert parser.continuation_token == "two"


def test_chime_aac_matroska_frames_decode_to_fixed_pcm_chunks() -> None:
    codec_private, access_units = _encode_aac_sine()
    payload = _fragment(
        *(
            _simple_block(1, index * 21, access_unit)
            for index, access_unit in enumerate(access_units)
        ),
        codec_id="A_AAC",
        sample_rate=48000,
        codec_private=codec_private,
    )
    parser = StreamingMatroskaParser()
    frames = parser.feed(payload)
    parser.finish()
    consumer = KVSConsumer(
        KVSConfig(stream_arn="arn:aws:kinesisvideo:us-east-1:1:stream/test/1"),
        control_client=object(),
    )

    chunks = [
        chunk
        for frame in frames
        for chunk in consumer._normalize_frame(frame, parser.tracks)
    ]

    assert parser.tracks[1].codec_id == "A_AAC"
    assert parser.tracks[1].codec_private == codec_private
    assert len(chunks) >= 4
    assert all(len(chunk) == 640 for chunk in chunks)
    decoded = np.frombuffer(b"".join(chunks), dtype="<i2")
    assert np.max(np.abs(decoded)) > 1000


def _encode_aac_sine() -> tuple[bytes, list[bytes]]:
    encoder = av.CodecContext.create("aac", "w")
    encoder.sample_rate = 48000
    encoder.layout = "mono"
    encoder.format = "fltp"
    encoder.bit_rate = 64000
    encoder.open()
    packets: list[bytes] = []
    for frame_index in range(6):
        positions = np.arange(1024, dtype=np.float32) + frame_index * 1024
        waveform = (0.4 * np.sin(2 * np.pi * 440 * positions / 48000)).reshape(
            1, -1
        )
        frame = av.AudioFrame.from_ndarray(
            waveform.astype(np.float32), format="fltp", layout="mono"
        )
        frame.sample_rate = 48000
        packets.extend(bytes(packet) for packet in encoder.encode(frame))
    packets.extend(bytes(packet) for packet in encoder.encode(None))
    codec_private = encoder.extradata
    if not codec_private:
        raise RuntimeError("AAC encoder did not expose AudioSpecificConfig")
    return codec_private, packets
