import type { AppendResult, ChunkRepository, RecordingChunkInput } from './chunkRepository';

type ChunkRecorderAdapterOptions = {
  repository: ChunkRepository;
  sessionId: string;
  onStored?: (result: AppendResult) => void;
  onError?: (error: unknown) => void;
};

export class ChunkRecorderAdapter {
  private readonly options: ChunkRecorderAdapterOptions;
  private queue: Promise<void> = Promise.resolve();

  constructor(options: ChunkRecorderAdapterOptions) { this.options = options; }

  accept(chunk: RecordingChunkInput): Promise<AppendResult> {
    const task = this.queue.then(async () => {
      const result = await this.options.repository.append(this.options.sessionId, chunk);
      try { this.options.onStored?.(result); } catch { /* consumer callbacks cannot break persistence ordering */ }
      return result;
    });
    this.queue = task.then(() => undefined, (error: unknown) => {
      try { this.options.onError?.(error); } catch { /* report callback failures do not poison later writes */ }
    });
    return task;
  }
}
