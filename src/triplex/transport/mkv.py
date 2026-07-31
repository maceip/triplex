"""Incremental Matroska parser for Kinesis Video Streams audio.

Kinesis Video Streams ``GetMedia`` returns a long-lived stream of Matroska
elements, not raw audio bytes.  This parser consumes arbitrarily split network
chunks and emits the payloads from Matroska ``SimpleBlock`` elements as soon as
each block is complete.  It also extracts track metadata and the KVS
continuation token embedded in fragment tags.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field


class MatroskaParseError(ValueError):
    """Raised when the KVS payload is not a supported Matroska stream."""


@dataclass(frozen=True)
class MatroskaTrack:
    """Relevant metadata from one Matroska track entry."""

    number: int
    track_type: int
    codec_id: str
    sample_rate: int | None = None
    channels: int = 1
    bit_depth: int = 16
    codec_private: bytes | None = None

    @property
    def is_audio(self) -> bool:
        return self.track_type == 2


@dataclass(frozen=True)
class MatroskaFrame:
    """One encoded media frame extracted from a Matroska block."""

    track_number: int
    timestamp_ms: float
    payload: bytes
    keyframe: bool


@dataclass
class _MasterContext:
    element_id: int
    end_offset: int | None
    values: dict[str, object] = field(default_factory=dict)


# Matroska master/container element IDs used by KVS. Unknown leaf elements are
# safely skipped by size, while these elements are entered so their children
# can be decoded incrementally.
_MASTER_IDS = {
    0x1A45DFA3,  # EBML
    0x18538067,  # Segment
    0x114D9B74,  # SeekHead
    0x4DBB,  # Seek
    0x1549A966,  # Info
    0x1654AE6B,  # Tracks
    0xAE,  # TrackEntry
    0xE0,  # Video
    0xE1,  # Audio
    0x1F43B675,  # Cluster
    0xA0,  # BlockGroup
    0x1254C367,  # Tags
    0x7373,  # Tag
    0x63C0,  # Targets
    0x67C8,  # SimpleTag
    0x1043A770,  # Chapters
    0x1941A469,  # Attachments
    0x1C53BB6B,  # Cues
}

_EBML_ID = 0x1A45DFA3
_SEGMENT_ID = 0x18538067
_TRACK_ENTRY_ID = 0xAE
_CLUSTER_ID = 0x1F43B675
_SIMPLE_TAG_ID = 0x67C8
_SIMPLE_BLOCK_ID = 0xA3


class StreamingMatroskaParser:
    """Parse a streaming sequence of KVS Matroska fragments."""

    def __init__(self) -> None:
        self._buffer = bytearray()
        self._offset = 0
        self._stack: list[_MasterContext] = []
        self._tracks: dict[int, MatroskaTrack] = {}
        self._timecode_scale_ns = 1_000_000
        self._continuation_token: str | None = None

    @property
    def tracks(self) -> dict[int, MatroskaTrack]:
        return dict(self._tracks)

    @property
    def continuation_token(self) -> str | None:
        return self._continuation_token

    def feed(self, data: bytes) -> list[MatroskaFrame]:
        """Consume one network chunk and return every complete audio frame."""
        if data:
            self._buffer.extend(data)

        frames: list[MatroskaFrame] = []
        while True:
            self._close_finished_contexts()
            header = _parse_element_header(self._buffer)
            if header is None:
                break

            element_id, payload_size, header_size = header
            payload_offset = self._offset + header_size

            # Every KVS fragment repeats the EBML header. A prior Segment often
            # has an unknown size, so the new header is the unambiguous boundary.
            if element_id == _EBML_ID and self._stack:
                self._close_all_contexts()

            if element_id in _MASTER_IDS:
                self._consume(header_size)
                end_offset = (
                    None if payload_size is None else payload_offset + payload_size
                )
                self._stack.append(_MasterContext(element_id, end_offset))
                continue

            if payload_size is None:
                raise MatroskaParseError(
                    f"leaf element 0x{element_id:x} has an unknown size"
                )
            total_size = header_size + payload_size
            if len(self._buffer) < total_size:
                break

            payload = bytes(self._buffer[header_size:total_size])
            frames.extend(self._handle_leaf(element_id, payload))
            self._consume(total_size)

        return frames

    def finish(self) -> None:
        """Validate that no truncated finite element remains at end-of-stream."""
        self._close_finished_contexts()
        if self._buffer:
            raise MatroskaParseError(
                f"stream ended with {len(self._buffer)} bytes of an incomplete element"
            )
        self._close_all_contexts()

    def _consume(self, size: int) -> None:
        del self._buffer[:size]
        self._offset += size

    def _close_finished_contexts(self) -> None:
        while self._stack and self._stack[-1].end_offset is not None:
            end_offset = self._stack[-1].end_offset
            if end_offset is None:
                raise MatroskaParseError("invalid unbounded parent context")
            if self._offset < end_offset:
                break
            if self._offset > end_offset:
                raise MatroskaParseError("child element exceeds its parent size")
            self._finish_context(self._stack.pop())

    def _close_all_contexts(self) -> None:
        while self._stack:
            self._finish_context(self._stack.pop())

    def _finish_context(self, context: _MasterContext) -> None:
        if context.element_id == _TRACK_ENTRY_ID:
            number = _required_int(context.values.get("number", 0))
            if number:
                self._tracks[number] = MatroskaTrack(
                    number=number,
                    track_type=_required_int(context.values.get("track_type", 0)),
                    codec_id=str(context.values.get("codec_id", "")),
                    sample_rate=_optional_int(context.values.get("sample_rate")),
                    channels=_required_int(context.values.get("channels", 1)),
                    bit_depth=_required_int(context.values.get("bit_depth", 16)),
                    codec_private=_optional_bytes(
                        context.values.get("codec_private")
                    ),
                )
        elif context.element_id == _SIMPLE_TAG_ID:
            name = context.values.get("name")
            value = context.values.get("value")
            if name == "AWS_KINESISVIDEO_CONTINUATION_TOKEN" and isinstance(value, str):
                self._continuation_token = value

    def _handle_leaf(self, element_id: int, payload: bytes) -> list[MatroskaFrame]:
        track = self._nearest(_TRACK_ENTRY_ID)
        cluster = self._nearest(_CLUSTER_ID)
        simple_tag = self._nearest(_SIMPLE_TAG_ID)

        if element_id == 0x2AD7B1:  # TimecodeScale
            self._timecode_scale_ns = _unsigned(payload)
        elif element_id == 0xE7 and cluster is not None:  # Cluster Timecode
            cluster.values["timecode"] = _unsigned(payload)
        elif track is not None:
            if element_id == 0xD7:  # TrackNumber
                track.values["number"] = _unsigned(payload)
            elif element_id == 0x83:  # TrackType
                track.values["track_type"] = _unsigned(payload)
            elif element_id == 0x86:  # CodecID
                track.values["codec_id"] = _text(payload)
            elif element_id == 0x63A2:  # CodecPrivate (AAC AudioSpecificConfig)
                track.values["codec_private"] = payload
            elif element_id == 0xB5:  # SamplingFrequency
                track.values["sample_rate"] = int(round(_float(payload)))
            elif element_id == 0x9F:  # Channels
                track.values["channels"] = _unsigned(payload)
            elif element_id == 0x6264:  # BitDepth
                track.values["bit_depth"] = _unsigned(payload)
        elif simple_tag is not None:
            if element_id == 0x45A3:  # TagName
                simple_tag.values["name"] = _text(payload)
            elif element_id == 0x4487:  # TagString
                simple_tag.values["value"] = _text(payload)

        if element_id != _SIMPLE_BLOCK_ID:
            return []
        if cluster is None:
            raise MatroskaParseError("SimpleBlock found outside a Cluster")
        cluster_timecode = _required_int(cluster.values.get("timecode", 0))
        return self._parse_simple_block(payload, cluster_timecode)

    def _parse_simple_block(
        self, payload: bytes, cluster_timecode: int
    ) -> list[MatroskaFrame]:
        track_vint = _parse_vint(payload, strip_marker=True)
        if track_vint is None:
            raise MatroskaParseError("truncated SimpleBlock track number")
        track_number, track_size, unknown = track_vint
        if unknown or len(payload) < track_size + 3:
            raise MatroskaParseError("invalid SimpleBlock header")

        relative_timecode = struct.unpack(">h", payload[track_size : track_size + 2])[0]
        flags = payload[track_size + 2]
        frame_data = payload[track_size + 3 :]
        lace_mode = (flags >> 1) & 0x03
        laced_frames = _split_laced_frames(frame_data, lace_mode)
        timestamp_ms = (
            (cluster_timecode + relative_timecode) * self._timecode_scale_ns / 1_000_000
        )
        return [
            MatroskaFrame(
                track_number=track_number,
                timestamp_ms=timestamp_ms,
                payload=frame,
                keyframe=bool(flags & 0x80),
            )
            for frame in laced_frames
        ]

    def _nearest(self, element_id: int) -> _MasterContext | None:
        for context in reversed(self._stack):
            if context.element_id == element_id:
                return context
        return None


def _parse_element_header(buffer: bytes | bytearray) -> tuple[int, int | None, int] | None:
    element = _parse_vint(buffer, strip_marker=False)
    if element is None:
        return None
    element_id, id_size, _ = element
    size = _parse_vint(buffer[id_size:], strip_marker=True)
    if size is None:
        return None
    payload_size, size_size, unknown = size
    return element_id, None if unknown else payload_size, id_size + size_size


def _parse_vint(
    data: bytes | bytearray, *, strip_marker: bool
) -> tuple[int, int, bool] | None:
    if not data:
        return None
    first = data[0]
    if first == 0:
        raise MatroskaParseError("invalid EBML variable-length integer")
    marker = 0x80
    length = 1
    while length <= 8 and not first & marker:
        marker >>= 1
        length += 1
    if length > 8:
        raise MatroskaParseError("EBML variable-length integer exceeds eight bytes")
    if len(data) < length:
        return None

    value = first & (marker - 1) if strip_marker else first
    for byte in data[1:length]:
        value = (value << 8) | byte
    unknown = strip_marker and value == (1 << (7 * length)) - 1
    return value, length, unknown


def _split_laced_frames(payload: bytes, lace_mode: int) -> list[bytes]:
    if lace_mode == 0:
        return [payload]
    if not payload:
        raise MatroskaParseError("laced SimpleBlock has no lace count")

    frame_count = payload[0] + 1
    cursor = 1
    sizes: list[int] = []

    if lace_mode == 2:  # fixed-size lacing
        remaining = len(payload) - cursor
        if remaining % frame_count:
            raise MatroskaParseError("fixed lacing payload is not evenly divisible")
        sizes = [remaining // frame_count] * frame_count
    elif lace_mode == 1:  # Xiph lacing
        for _ in range(frame_count - 1):
            size = 0
            while True:
                if cursor >= len(payload):
                    raise MatroskaParseError("truncated Xiph lace size")
                value = payload[cursor]
                cursor += 1
                size += value
                if value != 255:
                    break
            sizes.append(size)
        sizes.append(len(payload) - cursor - sum(sizes))
    elif lace_mode == 3:  # EBML lacing
        first_size = _parse_vint(payload[cursor:], strip_marker=True)
        if first_size is None or first_size[2]:
            raise MatroskaParseError("invalid first EBML lace size")
        previous_size, encoded_size, _ = first_size
        cursor += encoded_size
        sizes.append(previous_size)
        for _ in range(frame_count - 2):
            encoded = _parse_vint(payload[cursor:], strip_marker=True)
            if encoded is None or encoded[2]:
                raise MatroskaParseError("invalid EBML lace size delta")
            unsigned_delta, encoded_size, _ = encoded
            cursor += encoded_size
            bias = (1 << (7 * encoded_size - 1)) - 1
            previous_size += unsigned_delta - bias
            if previous_size < 0:
                raise MatroskaParseError("negative EBML laced frame size")
            sizes.append(previous_size)
        sizes.append(len(payload) - cursor - sum(sizes))
    else:  # pragma: no cover - two flag bits make this unreachable
        raise MatroskaParseError(f"unsupported lace mode {lace_mode}")

    if any(size < 0 for size in sizes) or sum(sizes) != len(payload) - cursor:
        raise MatroskaParseError("laced frame sizes do not match block payload")

    frames = []
    for size in sizes:
        frames.append(payload[cursor : cursor + size])
        cursor += size
    return frames


def _unsigned(payload: bytes) -> int:
    return int.from_bytes(payload, "big", signed=False)


def _float(payload: bytes) -> float:
    if len(payload) == 4:
        return float(struct.unpack(">f", payload)[0])
    if len(payload) == 8:
        return float(struct.unpack(">d", payload)[0])
    raise MatroskaParseError("Matroska float must contain four or eight bytes")


def _text(payload: bytes) -> str:
    return payload.decode("utf-8").rstrip("\x00")


def _optional_int(value: object | None) -> int | None:
    return None if value is None else _required_int(value)


def _optional_bytes(value: object | None) -> bytes | None:
    if value is None:
        return None
    if not isinstance(value, bytes):
        raise MatroskaParseError(
            f"expected binary metadata, received {type(value).__name__}"
        )
    return value


def _required_int(value: object) -> int:
    if not isinstance(value, int):
        raise MatroskaParseError(f"expected integer metadata, received {type(value).__name__}")
    return value
