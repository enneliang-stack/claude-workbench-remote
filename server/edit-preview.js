'use strict';

const editTools = new Set(['Edit', 'MultiEdit', 'Write', 'NotebookEdit']);
const summaries = new WeakMap();
const maxChars = 256 * 1024;
const maxChanges = 200;

function lines(text) {
  if (!text) return [];
  const parts = text.split(/\r\n|\n|\r/);
  if (parts.at(-1) === '') parts.pop();
  return parts;
}

function* changes(name, input) {
  if (name === 'Edit' || name === 'MultiEdit') {
    for (const item of name === 'Edit' ? [input] : (Array.isArray(input.edits) ? input.edits : [])) {
      if (typeof item?.old_string === 'string' && typeof item.new_string === 'string') {
        yield [item.old_string, item.new_string];
      }
    }
  } else {
    const text = name === 'Write' ? input.content : input.new_source;
    if (typeof text === 'string' && !(name === 'NotebookEdit' && input.edit_mode === 'delete')) yield ['', text];
  }
}

function compactEdit(block) {
  if (!editTools.has(block.name)) return null;
  const id = block.id || block.toolUseId;
  const input = block.input;
  if (!input || typeof input !== 'object') return { id, input: null };
  let summary = summaries.get(input);
  if (!summary) {
    const path = (block.name === 'NotebookEdit' && input.notebook_path) || input.file_path;
    const safe = {};
    for (const key of ['file_path', 'notebook_path', 'edit_mode']) {
      if (typeof input[key] === 'string') safe[key] = input[key];
    }
    summary = { input: safe };
    if (typeof path === 'string' && path) {
      let added = 0, removed = 0, hasDetails = false;
      for (const [before, after] of changes(block.name, input)) {
        const a = lines(before), b = lines(after);
        let left = 0, right = 0;
        while (left < a.length && left < b.length && a[left] === b[left]) left++;
        while (right < a.length - left && right < b.length - left &&
          a[a.length - right - 1] === b[b.length - right - 1]) right++;
        removed += a.length - left - right;
        added += b.length - left - right;
        hasDetails = true;
      }
      summary.editSummary = { path, added, removed, hasDetails };
    }
    summaries.set(input, summary);
  }
  return { id, ...summary };
}

function validToolIds(ids) {
  return Array.isArray(ids) && ids.length > 0 && ids.length <= 128 &&
    ids.every(id => typeof id === 'string' && id.length > 0 && id.length <= 256) &&
    new Set(ids).size === ids.length;
}

// Resolve tool records inside this conversation only. Never read the current file:
// it may have changed, been deleted, or been edited outside the working directory.
async function readEditDiff(toolIds, liveBlocks, loadHistory) {
  const wanted = new Set(toolIds);
  const found = new Map();
  function collect(blocks) {
    for (const block of blocks || []) {
      const id = block?.id || block?.toolUseId;
      if (block?.type === 'tool_use' && editTools.has(block.name) && !block.isError &&
          block.input && wanted.delete(id)) found.set(id, block);
    }
  }
  collect(liveBlocks);
  if (wanted.size) {
    for (const msg of await loadHistory()) {
      if (msg.role === 'assistant') collect(msg.blocks);
      if (!wanted.size) break;
    }
  }
  if (wanted.size) return null;
  const result = { changes: [], truncated: false };
  let remaining = maxChars;
  for (const id of toolIds) {
    const block = found.get(id);
    for (const [oldText, newText] of changes(block.name, block.input)) {
      if (result.changes.length === maxChanges || !remaining) {
        result.truncated = true;
        return result;
      }
      const limited = oldText.length + newText.length > remaining;
      const oldLimit = limited ? Math.min(oldText.length, Math.max(Math.floor(remaining / 2), remaining - newText.length)) : oldText.length;
      const entry = { oldText: oldText.slice(0, oldLimit), newText: newText.slice(0, remaining - oldLimit) };
      result.changes.push(entry);
      remaining -= entry.oldText.length + entry.newText.length;
      if (limited) { result.truncated = true; return result; }
    }
  }
  return result;
}

module.exports = { compactEdit, validToolIds, readEditDiff };
