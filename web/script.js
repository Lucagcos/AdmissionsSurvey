const leftBtn = document.getElementById('left');
const rightBtn = document.getElementById('right');
const skipBtn = document.getElementById('skip');
const quitBtn = document.getElementById('quit');
const status = document.getElementById('status');

let current = null; // {a, b}
let pendingVotes = []; // queued as "winner loser"
let items = [];
let allPairs = []; // array of [i, j] where i < j
let pairIndex = 0;

async function fetchPair() {
  if (pairIndex >= allPairs.length) {
    current = null;
    leftBtn.disabled = true;
    rightBtn.disabled = true;
    skipBtn.disabled = true;
    leftBtn.textContent = 'Done';
    rightBtn.textContent = 'Done';
    updateQueuedStatus(`All pairs shown (${allPairs.length}/${allPairs.length})`);
    return;
  }

  const pair = allPairs[pairIndex++];
  let a = pair[0];
  let b = pair[1];
  // Randomize on-screen left/right order while keeping pair uniqueness.
  if (Math.random() < 0.5) {
    const t = a; a = b; b = t;
  }
  current = { a, b };
  leftBtn.textContent = items[a];
  rightBtn.textContent = items[b];
  updateQueuedStatus(`Pair ${pairIndex}/${allPairs.length}`);
}

function setStatus(s) { status.textContent = s; }
function updateQueuedStatus(prefix) {
  const base = `${pendingVotes.length} queued`;
  setStatus(prefix ? `${prefix} (${base})` : base);
}

function buildAllPairs(count) {
  const pairs = [];
  for (let i = 0; i < count; i++) {
    for (let j = i + 1; j < count; j++) {
      pairs.push([i, j]);
    }
  }
  // Fisher-Yates shuffle for unbiased random ordering
  for (let i = pairs.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    const t = pairs[i];
    pairs[i] = pairs[j];
    pairs[j] = t;
  }
  return pairs;
}

async function initPairs() {
  setStatus('Loading items...');
  try {
    const res = await fetch('/items');
    if (!res.ok) throw new Error('Failed to fetch items');
    items = await res.json();
    if (!Array.isArray(items)) throw new Error('Invalid items response');
    allPairs = buildAllPairs(items.length);
    pairIndex = 0;
    await fetchPair();
  } catch (e) {
    setStatus('Error loading items');
    console.error(e);
  }
}

function queueVote(winner, loser) {
  pendingVotes.push(`${winner} ${loser}`);
  updateQueuedStatus('Queued');
}

async function flushVotes() {
  if (pendingVotes.length === 0) {
    updateQueuedStatus('Nothing to save');
    return true;
  }
  updateQueuedStatus('Saving');
  const payload = pendingVotes.join('\n') + '\n';
  try {
    const res = await fetch('/flush', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      body: payload
    });
    if (!res.ok) throw new Error('Flush failed');
    const j = await res.json();
    if (j && j.ok) {
      pendingVotes = [];
      updateQueuedStatus(`Saved ${j.written ?? 0}`);
      return true;
    }
  } catch (e) {
    updateQueuedStatus('Save failed');
    console.error(e);
  }
  return false;
}

async function voteAndNext(winner, loser) {
  queueVote(winner, loser);
  await fetchPair();
}

leftBtn.addEventListener('click', () => {
  if (!current) return;
  voteAndNext(current.a, current.b);
});
rightBtn.addEventListener('click', () => {
  if (!current) return;
  voteAndNext(current.b, current.a);
});
skipBtn.addEventListener('click', () => {
  fetchPair();
});

quitBtn.addEventListener('click', async () => {
  leftBtn.disabled = true;
  rightBtn.disabled = true;
  skipBtn.disabled = true;
  quitBtn.disabled = true;
  const ok = await flushVotes();
  setStatus(ok ? 'Thanks! Votes saved.' : 'Could not save. Please try again.');
  if (!ok) {
    leftBtn.disabled = false;
    rightBtn.disabled = false;
    skipBtn.disabled = false;
    quitBtn.disabled = false;
  }
});

// Best-effort save if the tab closes without pressing quit.
window.addEventListener('pagehide', () => {
  if (pendingVotes.length === 0) return;
  const payload = pendingVotes.join('\n') + '\n';
  if (navigator.sendBeacon) {
    const blob = new Blob([payload], { type: 'text/plain; charset=utf-8' });
    navigator.sendBeacon('/flush', blob);
  }
});

// initial load
initPairs();
updateQueuedStatus('');

