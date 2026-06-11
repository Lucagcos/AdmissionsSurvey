const leftBtn = document.getElementById('left');
const rightBtn = document.getElementById('right');
const skipBtn = document.getElementById('skip');
const quitBtn = document.getElementById('quit');
const status = document.getElementById('status');

let current = null; // {a, b, aLabel, bLabel}
let pendingVotes = []; // queued as "winner loser"
let items = [];
let pairCount = 0; // number of pairs shown so far
let maxPairsPerUser = 15; // will be set by server

async function fetchPair() {
  if (pairCount >= maxPairsPerUser) {
    current = null;
    leftBtn.disabled = true;
    rightBtn.disabled = true;
    skipBtn.disabled = true;
    leftBtn.textContent = 'Done';
    rightBtn.textContent = 'Done';
    updateQueuedStatus(`Survey complete (${pairCount}/${maxPairsPerUser})`);
    return;
  }

  try {
    const res = await fetch('/pair');
    if (!res.ok) throw new Error('Failed to fetch pair');
    const data = await res.json();
    if (data.maxPairsPerUser !== undefined) {
      maxPairsPerUser = data.maxPairsPerUser;
    }
    current = {
      a: data.a,
      b: data.b,
      aLabel: data.aLabel,
      bLabel: data.bLabel
    };
    leftBtn.textContent = current.aLabel;
    rightBtn.textContent = current.bLabel;
    updateQueuedStatus(`Pair ${pairCount + 1}/${maxPairsPerUser}`);
  } catch (e) {
    setStatus('Error loading pair');
    console.error(e);
  }
}

function setStatus(s) { status.textContent = s; }
function updateQueuedStatus(prefix) {
  const base = `${pendingVotes.length} queued`;
  setStatus(prefix ? `${prefix} (${base})` : base);
}

async function initPairs() {
  setStatus('Loading items...');
  try {
    const res = await fetch('/items');
    if (!res.ok) throw new Error('Failed to fetch items');
    items = await res.json();
    if (!Array.isArray(items)) throw new Error('Invalid items response');
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
  pairCount++;
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
  pairCount++;
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

