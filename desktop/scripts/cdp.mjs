/**
 * Minimal Chrome DevTools Protocol client for the installed-app smoke scripts.
 *
 * The packaged app has DevTools removed (ADR-037) and issues no requests while
 * anonymous, so nothing about its renderer was observable until Electron's
 * `--remote-debugging-port` was used. Node 24 has a global WebSocket, so no
 * dependency is needed.
 */

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Poll the CDP HTTP endpoint until a renderer page target shows up. */
export async function findPageTarget(debugUrl, { timeoutMs = 40000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let lastError = 'no response';
  while (Date.now() < deadline) {
    try {
      const targets = await (await fetch(`${debugUrl}/json`)).json();
      const page = targets.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) {
        return page;
      }
      lastError = 'no page target yet';
    } catch (error) {
      lastError = error.message;
    }
    await sleep(800);
  }
  throw new Error(`no CDP page target at ${debugUrl} after ${timeoutMs}ms (${lastError})`);
}

/** Open one CDP session and expose `send`/`evaluate`/`close`. */
export async function openClient(webSocketDebuggerUrl) {
  const ws = new WebSocket(webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
  });

  let nextId = 0;
  const pending = new Map();
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)(message);
      pending.delete(message.id);
    }
  });

  const send = (method, params = {}) =>
    new Promise((resolve) => {
      const id = ++nextId;
      pending.set(id, resolve);
      ws.send(JSON.stringify({ id, method, params }));
    });

  /** Evaluate in the page, awaiting a promise if the expression returns one. */
  const evaluate = async (expression) => {
    const response = await send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (response.result?.exceptionDetails) {
      throw new Error(
        `page threw: ${JSON.stringify(response.result.exceptionDetails).slice(0, 220)}`
      );
    }
    return response.result?.result?.value;
  };

  return { send, evaluate, close: () => ws.close() };
}
