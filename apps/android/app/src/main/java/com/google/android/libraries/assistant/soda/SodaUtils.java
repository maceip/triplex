package com.google.android.libraries.assistant.soda;

import com.google.speech.soda.AudioProto;
import com.google.speech.soda.SodaEventProto;
import com.google.speech.soda.client.SodaClientConfigProto;
import java.nio.ByteBuffer;

/**
 * Minimal stand-in for classes missing from {@code recovered-soda.jar}.
 * Methods mirror what {@link Soda} invokes reflectively from that jar.
 */
public final class SodaUtils {
  private SodaUtils() {}

  public static final class DirectByteBufferMaker {
    private ByteBuffer buffer;

    public DirectByteBufferMaker() {}

    public ByteBuffer createOrReuse(int size) {
      if (size <= 0) {
        throw new IllegalArgumentException("size must be positive: " + size);
      }
      if (buffer == null || buffer.capacity() != size) {
        buffer = ByteBuffer.allocateDirect(size);
      } else {
        buffer.clear();
      }
      return buffer;
    }
  }

  public static int numBytesPerFrame(AudioProto.RawAudioFormat format) {
    if (format == null) {
      return 2;
    }
    final int channels = Math.max(1, format.getChannelCount());
    // SODA raw capture is PCM16; 2 bytes per sample per channel.
    return 2 * channels;
  }

  public static SodaStopReason stopReasonFromInt(int reason) {
    for (SodaStopReason value : SodaStopReason.values()) {
      if (value.ordinal() == reason) {
        return value;
      }
    }
    return SodaStopReason.values()[0];
  }

  public static SodaEventProto.SodaEvent convertHotqueryToQuickPhraseEvent(
      SodaEventProto.SodaEvent.Builder builder) {
    return builder.build();
  }

  public static SodaEventProto.SodaEvent convertHotwordToQuickPhraseEvent(
      SodaEventProto.SodaEvent.Builder builder) {
    return builder.build();
  }

  public static SodaClientConfigProto.SodaClientConfig.Builder createDefaultConfig() {
    return SodaClientConfigProto.SodaClientConfig.newBuilder();
  }
}
