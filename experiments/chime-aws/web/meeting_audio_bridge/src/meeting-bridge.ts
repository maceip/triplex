import DefaultDeviceController from "amazon-chime-sdk-js/build/devicecontroller/DefaultDeviceController";
import ConsoleLogger from "amazon-chime-sdk-js/build/logger/ConsoleLogger";
import { LogLevel } from "amazon-chime-sdk-js/build/logger/LogLevel";
import DefaultMeetingSession from "amazon-chime-sdk-js/build/meetingsession/DefaultMeetingSession";
import MeetingSessionConfiguration from "amazon-chime-sdk-js/build/meetingsession/MeetingSessionConfiguration";

type AudioMetadata = {
  type: "audio";
  generation_id: string;
  sequence: number;
  sample_count: number;
  sample_rate: number;
};

type GatewayMessage =
  | AudioMetadata
  | { type: "flush"; generation_id: string }
  | { type: "close" };

const statusElement = document.querySelector<HTMLParagraphElement>("#status");

function setStatus(message: string): void {
  if (statusElement) statusElement.textContent = message;
}

function pcmToContextRate(
  pcm: Int16Array,
  sourceRate: number,
  targetRate: number,
): Float32Array {
  if (sourceRate <= 0 || targetRate <= 0) {
    throw new Error("PCM sample rates must be positive");
  }
  if (pcm.length === 0) return new Float32Array(0);
  const outputLength = Math.max(
    1,
    Math.round((pcm.length * targetRate) / sourceRate),
  );
  const output = new Float32Array(outputLength);
  for (let index = 0; index < outputLength; index += 1) {
    const sourcePosition = (index * sourceRate) / targetRate;
    const left = Math.min(pcm.length - 1, Math.floor(sourcePosition));
    const right = Math.min(pcm.length - 1, left + 1);
    const fraction = sourcePosition - left;
    output[index] =
      (pcm[left] * (1 - fraction) + pcm[right] * fraction) / 32768;
  }
  return output;
}

async function main(): Promise<void> {
  const pathParts = window.location.pathname.split("/");
  const sessionId = pathParts.at(-2);
  const browserToken = window.location.hash.slice(1);
  if (!sessionId || !browserToken) {
    throw new Error("The bridge URL is missing its ephemeral session token");
  }

  const credentialsResponse = await fetch(
    `/sessions/${encodeURIComponent(sessionId)}/credentials`,
    { headers: { Authorization: `Bearer ${browserToken}` } },
  );
  if (!credentialsResponse.ok) {
    throw new Error(`Credential request failed (${credentialsResponse.status})`);
  }
  const credentials = await credentialsResponse.json();

  const logger = new ConsoleLogger("TriplexMeetingBridge", LogLevel.WARN);
  const deviceController = new DefaultDeviceController(logger);
  const meetingConfiguration = new MeetingSessionConfiguration(
    credentials.meeting,
    credentials.attendee,
  );
  const meetingSession = new DefaultMeetingSession(
    meetingConfiguration,
    logger,
    deviceController,
  );

  const audioContext = new AudioContext({
    latencyHint: "interactive",
    sampleRate: 16000,
  });
  await audioContext.audioWorklet.addModule("/static/pcm-worklet.js");
  const source = new AudioWorkletNode(audioContext, "triplex-pcm-source", {
    numberOfInputs: 0,
    numberOfOutputs: 1,
    outputChannelCount: [1],
  });
  const destination = audioContext.createMediaStreamDestination();
  source.connect(destination);
  await meetingSession.audioVideo.startAudioInput(destination.stream);

  const socketProtocol = window.location.protocol === "https:" ? "wss" : "ws";
  const socket = new WebSocket(
    `${socketProtocol}://${window.location.host}/ws/browser/${encodeURIComponent(sessionId)}`,
    ["triplex", browserToken],
  );
  socket.binaryType = "arraybuffer";

  let pendingMetadata: AudioMetadata | null = null;
  let closing = false;
  source.port.onmessage = (event: MessageEvent) => {
    if (socket.readyState !== WebSocket.OPEN) return;
    socket.send(JSON.stringify(event.data));
  };

  socket.onmessage = (event: MessageEvent) => {
    if (typeof event.data === "string") {
      const message = JSON.parse(event.data) as GatewayMessage;
      if (message.type === "audio") {
        pendingMetadata = message;
      } else if (message.type === "flush") {
        source.port.postMessage({
          type: "flush",
          generation_id: message.generation_id,
        });
      } else if (message.type === "close") {
        closing = true;
        meetingSession.audioVideo.stop();
        socket.close(1000);
      }
      return;
    }

    if (!pendingMetadata) {
      socket.close(1002, "binary audio arrived without metadata");
      return;
    }
    const pcm = new Int16Array(event.data as ArrayBuffer);
    if (pcm.length !== pendingMetadata.sample_count) {
      socket.close(1002, "PCM payload length does not match metadata");
      return;
    }
    const floatAudio = pcmToContextRate(
      pcm,
      pendingMetadata.sample_rate,
      audioContext.sampleRate,
    );
    source.port.postMessage(
      {
        type: "audio",
        samples: floatAudio,
        source_sample_count: pendingMetadata.sample_count,
        generation_id: pendingMetadata.generation_id,
        sequence: pendingMetadata.sequence,
      },
      [floatAudio.buffer],
    );
    pendingMetadata = null;
  };

  await new Promise<void>((resolve, reject) => {
    socket.onopen = () => resolve();
    socket.onerror = () => reject(new Error("PCM gateway WebSocket failed"));
  });

  let meetingStarted = false;
  let resolveMeetingStart: (() => void) | undefined;
  let rejectMeetingStart: ((reason: Error) => void) | undefined;
  const meetingStart = new Promise<void>((resolve, reject) => {
    resolveMeetingStart = resolve;
    rejectMeetingStart = reject;
  });
  meetingSession.audioVideo.addObserver({
    audioVideoDidStart: () => {
      meetingStarted = true;
      setStatus("Connected and sending Triplex audio");
      resolveMeetingStart?.();
    },
    audioVideoDidStop: (sessionStatus) => {
      setStatus(`Meeting stopped (${sessionStatus.statusCode()})`);
      if (!meetingStarted) {
        rejectMeetingStart?.(
          new Error(`Meeting failed to start (${sessionStatus.statusCode()})`),
        );
      } else if (!closing && socket.readyState === WebSocket.OPEN) {
        socket.close(1011, "Chime meeting stopped");
      }
    },
  });
  meetingSession.audioVideo.start();
  await Promise.all([audioContext.resume(), meetingStart]);
  socket.send('{"type":"ready"}');
}

main().catch((error: unknown) => {
  console.error(error);
  setStatus(error instanceof Error ? error.message : String(error));
});
