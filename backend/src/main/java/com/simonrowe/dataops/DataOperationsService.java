package com.simonrowe.dataops;

import com.simonrowe.dataops.DataOperation.OperationStatus;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class DataOperationsService {

  private static final Logger LOG =
      LoggerFactory.getLogger(DataOperationsService.class);
  private static final long SSE_TIMEOUT = 600_000L;

  private final AtomicReference<DataOperation> currentOperation =
      new AtomicReference<>();
  private final AtomicReference<DataOperation> lastOperation =
      new AtomicReference<>();
  private final CopyOnWriteArrayList<SseEmitter> emitters =
      new CopyOnWriteArrayList<>();
  private final GoogleDriveService googleDriveService;

  public DataOperationsService(final GoogleDriveService googleDriveService) {
    this.googleDriveService = googleDriveService;
  }

  public DataOperation tryStartOperation(final OperationType type) {
    String id = UUID.randomUUID().toString();
    DataOperation operation = DataOperation.start(id, type);

    if (!currentOperation.compareAndSet(null, operation)) {
      return null;
    }

    LOG.info("Started data operation: type={}, id={}", type, id);
    broadcastProgress(operation);
    return operation;
  }

  public void updateProgress(final String message, final int percent) {
    DataOperation current = currentOperation.get();
    if (current == null) {
      return;
    }
    DataOperation updated = current.withProgress(message, percent);
    currentOperation.set(updated);
    broadcastProgress(updated);
  }

  public void completeOperation(final String summary) {
    DataOperation current = currentOperation.get();
    if (current == null) {
      return;
    }
    DataOperation completed = current.completed(summary);
    lastOperation.set(completed);
    currentOperation.set(null);
    LOG.info("Completed data operation: type={}, id={}, summary={}",
        completed.type(), completed.id(), summary);
    broadcastProgress(completed);
  }

  public void failOperation(final String error) {
    DataOperation current = currentOperation.get();
    if (current == null) {
      return;
    }
    DataOperation failed = current.failed(error);
    lastOperation.set(failed);
    currentOperation.set(null);
    LOG.error("Failed data operation: type={}, id={}, error={}",
        failed.type(), failed.id(), error);
    broadcastProgress(failed);
  }

  public DataOperationsStatus getStatus() {
    DataOperation current = currentOperation.get();
    boolean connected = googleDriveService.isConnected();
    String driveError = connected ? null : googleDriveService.getConnectionError();

    return new DataOperationsStatus(
        connected,
        driveError,
        current != null && current.status() == OperationStatus.IN_PROGRESS,
        current,
        lastOperation.get()
    );
  }

  public SseEmitter streamProgress() {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
    emitters.add(emitter);

    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(ex -> emitters.remove(emitter));

    DataOperation current = currentOperation.get();
    if (current != null) {
      sendEvent(emitter, current);
    }

    return emitter;
  }

  private void broadcastProgress(final DataOperation operation) {
    for (SseEmitter emitter : emitters) {
      sendEvent(emitter, operation);
    }
  }

  private void sendEvent(final SseEmitter emitter,
      final DataOperation operation) {
    try {
      emitter.send(SseEmitter.event()
          .name("progress")
          .data(operation));
    } catch (Exception ex) {
      emitters.remove(emitter);
    }
  }
}
