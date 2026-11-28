'use strict';

// Run after building the phone assets. Only a disposable profile and a temporary server are used.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { fork } = require('node:child_process');
const { once } = require('node:events');

const pc = process.env.WORKBENCH_PC_SOURCE;
test((pc ? 'PC' : 'packaged phone') + ' server sends only edit summaries until the requested tool IDs are opened', { timeout: 20000 }, async t => {
  const assets = pc || process.env.WORKBENCH_PHONE_ASSETS || path.join(__dirname, '../android/app/assets/phone');
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'workbench-phone-diff-'));
  const id = 'd5128265-f1f3-4524-880c-028bc1a6a6f1';
  const cwd = path.join(root, 'workspace');
  const dir = path.join(root, '.claude/projects', cwd.replace(/[^A-Za-z0-9]/g, '-'));
  await Promise.all([dir, cwd, path.join(root, 'public'), path.join(root, 'data')].map(p => fs.mkdir(p, { recursive: true })));
  const filename = path.join(root, 'outside', 'absent.js');
  const old = 'only after clicking '.repeat(500);
  const history = { type: 'tool_use', id: 'historical', name: 'Edit', input: { file_path: filename, old_string: old, new_string: 'new line' } };
  const live = { type: 'tool_use', id: 'in-progress', name: 'Write', input: { file_path: filename, content: 'live payload' } };
  await fs.writeFile(path.join(dir, id + '.jsonl'), JSON.stringify({
    type: 'assistant', timestamp: new Date().toISOString(), message: { id: 'message', content: [history] }
  }) + '\n');
  await fs.writeFile(path.join(root, 'data/state.json'), JSON.stringify({ conversations: { [id]: { cwd } } }));
  const headers = { 'x-gui-token': 'test-fixture-uses-only-disposable-data' };
  await fs.writeFile(path.join(root, 'data/gui-token.json'), JSON.stringify({ token: headers['x-gui-token'] }));
  await fs.copyFile(path.join(assets, 'public/ref-digest.js'), path.join(root, 'public/ref-digest.js'));
  if (pc) await fs.mkdir(path.join(root, 'tools'));
  for (const name of (pc ? ['tools/transcript-cache.js', 'tools/edit-preview.js'] : ['edit-preview.js'])) {
    await fs.copyFile(path.join(assets, name), path.join(root, name));
  }
  const serverFile = path.join(root, 'server.js');
  await fs.copyFile(path.join(assets, 'server.js'), serverFile);
  // Inject only CLI input into this temporary copy. Production HTTP, SSE and transcript parsing stay intact.
  await fs.appendFile(serverFile, '\n(' + (function fixtureBridge() {
    server.once('listening', () => process.send(server.address().port));
    process.on('message', ({ id, block }) => {
      const runner = new Runner(lookupConv(id));
      runner.busy = true;
      runner.live = { users: [], blocks: [block] };
      runners.set(id, runner);
      const key = 'tool:' + block.id;
      runner.emit({ t: 'block_start', key, block: { ...block, input: null } });
      runner.emit({ t: 'delta', key, kind: 'input_json', text: JSON.stringify(block.input) });
      runner.emit({ t: 'block_set', key, block });
      runner.emit({ t: 'fixture_end' });
    });
  }).toString() + ')();\n');
  const child = fork(serverFile, { cwd: root, silent: true, env: {
    ...process.env, HOME: root, USERPROFILE: root, WORKBENCH_PHONE: pc ? '0' : '1', PORT: '0', NO_OPEN: '1'
  } });
  t.after(async () => {
    if (child.exitCode == null && !child.signalCode) {
      const stopped = once(child, 'exit');
      child.kill();
      await stopped;
    }
    await fs.rm(root, { recursive: true, force: true, maxRetries: 4 });
  });
  const [port] = await once(child, 'message');
  const origin = 'http://127.0.0.1:' + port;
  const historyUrl = origin + '/api/conversation?id=' + id + '&light=1&compact=1';
  const read = url => fetch(url, { headers }).then(r => { assert.equal(r.status, 200); return r.json(); });
  const getDiff = toolIds => fetch(origin + '/api/edit-diff', { method: 'POST',
    headers: { ...headers, 'content-type': 'application/json' }, body: JSON.stringify({ id, toolIds }) });
  const first = await read(historyUrl + '&lazyEdits=1&limit=30');
  const block = first.messages[0].blocks[0];
  assert.deepEqual(block.editSummary, { path: filename, added: 1, removed: 1, hasDetails: true });
  assert.deepEqual(block.input, { file_path: filename });
  assert.equal(block.id, history.id);
  assert.ok(!JSON.stringify(first).includes('only after clicking'));
  assert.match((await read(historyUrl)).messages[0].blocks[0].input.old_string, /only after clicking/);
  const controller = new AbortController();
  try {
    const stream = await fetch(origin + '/api/stream?compact=1&lazyEdits=1', { headers, signal: controller.signal });
    child.send({ id, block: live });
    let events = '';
    const reader = stream.body.getReader();
    while (!events.includes('fixture_end')) {
      const chunk = await reader.read();
      assert.equal(chunk.done, false);
      events += Buffer.from(chunk.value).toString();
    }
    assert.match(events, /"editSummary"/);
    assert.doesNotMatch(events, /input_json|live payload/);
  } finally { controller.abort(); }
  const poll = await read(historyUrl + '&lazyEdits=1&limit=1');
  assert.equal(poll.live.blocks[0].id, live.id);
  assert.doesNotMatch(JSON.stringify(poll), /only after clicking|live payload/);
  assert.deepEqual(await (await getDiff([history.id, live.id])).json(), { changes: [
    { oldText: old, newText: 'new line' }, { oldText: '', newText: 'live payload' }
  ], truncated: false });
  assert.equal((await getDiff(['missing'])).status, 404);
  assert.equal((await getDiff([])).status, 400);
});
