function showDemoNotice(message) {
  const toast = document.getElementById('demo-toast');
  if (!toast) return;
  toast.textContent = message;
  toast.classList.add('show');
  window.clearTimeout(window.__demoToastTimer);
  window.__demoToastTimer = window.setTimeout(() => toast.classList.remove('show'), 3400);
}

function togglePassword() {
  const field = document.getElementById('senha') || document.getElementById('password');
  const buttonIcon = document.querySelector('.password-toggle i');
  if (!field || !buttonIcon) return;
  const showing = field.type === 'text';
  field.type = showing ? 'password' : 'text';
  buttonIcon.className = showing ? 'bi bi-eye' : 'bi bi-eye-slash';
}

function atualizarBarraPesos() {
  const bar = document.getElementById('weightsLiveBar');
  if (!bar) return;
  const ids = ['retorno', 'risco', 'tributacao', 'liquidez'];
  const vals = ids.map(id => Math.max(0, parseFloat(document.getElementById(id)?.value) || 0));
  const total = vals.reduce((a, b) => a + b, 0);
  const segs = bar.querySelectorAll('.w-seg');
  const labels = bar.querySelectorAll('.w-pct');
  vals.forEach((v, idx) => {
    const pct = total > 0 ? (v / total * 100) : 25;
    if (segs[idx]) segs[idx].style.width = pct.toFixed(1) + '%';
    if (labels[idx]) labels[idx].textContent = Math.round(pct) + '%';
  });
}

document.addEventListener('DOMContentLoaded', () => {
  const objetivoSelect = document.getElementById('objetivo');
  const weightInputs = ['retorno', 'risco', 'tributacao', 'liquidez'];
  weightInputs.forEach(id => {
    const el = document.getElementById(id);
    if (el) el.addEventListener('input', atualizarBarraPesos);
  });
  atualizarBarraPesos();

  if (!objetivoSelect) return;
  const presets = {
    'Preservar': { retorno: 25, risco: 40, tributacao: 15, liquidez: 20 },
    'Fazer crescer': { retorno: 40, risco: 25, tributacao: 20, liquidez: 15 },
    'Previdência': { retorno: 35, risco: 20, tributacao: 35, liquidez: 10 },
  };
  objetivoSelect.addEventListener('change', () => {
    const p = presets[objetivoSelect.value];
    if (!p) return;
    weightInputs.forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.value = p[id];
    });
    atualizarBarraPesos();
  });
});
