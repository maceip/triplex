class TriplexPcmSource extends AudioWorkletProcessor {
  constructor() {
    super();
    this.queue = [];
    this.playedByGeneration = new Map();
    this.port.onmessage = (event) => this.handleMessage(event.data);
  }

  handleMessage(message) {
    if (message.type === "audio") {
      this.queue.push({
        samples: message.samples,
        position: 0,
        sourceSampleCount: message.source_sample_count,
        generationId: message.generation_id,
        sequence: message.sequence,
      });
      return;
    }
    if (message.type === "flush") {
      let playedSamples = this.playedByGeneration.get(message.generation_id) || 0;
      const current = this.queue[0];
      if (current && current.generationId === message.generation_id) {
        playedSamples += Math.floor(
          (current.position / current.samples.length) * current.sourceSampleCount,
        );
      }
      this.queue = this.queue.filter(
        (chunk) => chunk.generationId !== message.generation_id,
      );
      this.playedByGeneration.set(message.generation_id, playedSamples);
      this.port.postMessage({
        type: "flushed",
        generation_id: message.generation_id,
        played_samples: playedSamples,
      });
    }
  }

  process(_inputs, outputs) {
    const output = outputs[0][0];
    output.fill(0);
    let outputPosition = 0;

    while (outputPosition < output.length && this.queue.length > 0) {
      const chunk = this.queue[0];
      const count = Math.min(
        output.length - outputPosition,
        chunk.samples.length - chunk.position,
      );
      output.set(
        chunk.samples.subarray(chunk.position, chunk.position + count),
        outputPosition,
      );
      chunk.position += count;
      outputPosition += count;

      if (chunk.position === chunk.samples.length) {
        const total =
          (this.playedByGeneration.get(chunk.generationId) || 0) +
          chunk.sourceSampleCount;
        this.playedByGeneration.set(chunk.generationId, total);
        this.port.postMessage({
          type: "played",
          generation_id: chunk.generationId,
          sequence: chunk.sequence,
          samples: chunk.sourceSampleCount,
          played_samples: total,
        });
        this.queue.shift();
      }
    }
    return true;
  }
}

registerProcessor("triplex-pcm-source", TriplexPcmSource);
