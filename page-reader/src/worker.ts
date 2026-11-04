import http from 'node:http';
import { ApplicationFailure, Context } from '@temporalio/activity';
import { NativeConnection, Worker } from '@temporalio/worker';
import { publicWebOnly } from './addresses.js';
import { startEgressProxy } from './egressProxy.js';
import { createReader, UnreadableUrl, type ReadPageInput, type ReadPageResult } from './readPage.js';

/**
 * An activity-only worker on the `page-reader` task queue. The workflow that schedules
 * `readPage` lives in software-factory (`com.simonrowe.factory.pageread`), which keeps the
 * orchestration, retries and the console's run view with every other factory workflow; this
 * container only ever drives the browser.
 *
 * One read at a time: Chromium needs a few hundred MB on the Pi, whose memory limits are not
 * enforced, and summaries are requested one click at a time anyway.
 */
export const TASK_QUEUE = 'page-reader';

async function main(): Promise<void> {
  const proxy = await startEgressProxy(publicWebOnly, (detail) =>
    console.warn(JSON.stringify({ level: 'WARN', message: 'Egress proxy refused a destination', detail })),
  );
  const read = createReader({
    proxyPort: proxy.port,
    heartbeat: () => Context.current().heartbeat(),
  });

  const activities = {
    async readPage(input: ReadPageInput): Promise<ReadPageResult> {
      try {
        const result = await read(input);
        console.info(JSON.stringify({
          level: 'INFO',
          message: 'Read page',
          url: input.url,
          outcome: result.outcome,
          httpStatus: result.httpStatus,
          characters: result.text?.length ?? 0,
        }));
        return result;
      } catch (error) {
        if (error instanceof UnreadableUrl) {
          throw ApplicationFailure.nonRetryable(error.message, 'UnreadableUrl');
        }
        throw error;
      }
    },
  };

  const connection = await NativeConnection.connect({
    address: process.env.TEMPORAL_ADDRESS ?? 'temporal:7233',
  });
  const worker = await Worker.create({
    connection,
    namespace: process.env.TEMPORAL_NAMESPACE ?? 'default',
    taskQueue: TASK_QUEUE,
    activities,
    maxConcurrentActivityTaskExecutions: 1,
  });

  // Loopback only: the healthcheck runs inside the container, and nothing else needs it.
  const health = http.createServer((_request, response) => {
    const running = worker.getState() === 'RUNNING';
    response.writeHead(running ? 200 : 503, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ status: running ? 'UP' : 'DOWN', taskQueue: TASK_QUEUE }));
  });
  health.listen(Number(process.env.HEALTH_PORT ?? 8090), '127.0.0.1');

  try {
    await worker.run();
  } finally {
    health.close();
    await proxy.close();
    await connection.close();
  }
}

main().catch((error: unknown) => {
  console.error(JSON.stringify({ level: 'ERROR', message: 'Page reader stopped', error: String(error) }));
  process.exit(1);
});
