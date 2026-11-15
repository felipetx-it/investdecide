function showDemoNotice(message) {
  const toast = document.getElementById('demo-toast');
  if (!toast) return;
  toast.textContent = message;
  toast.classList.add('show');
  window.clearTimeout(window.__demoToastTimer);
  window.__demoToastTimer = window.setTimeout(() => toast.classList.remove('show'), 3400);
}

function togglePassword() {
  const field = document.getElementById('password');
  const buttonIcon = document.querySelector('.password-toggle i');
  if (!field || !buttonIcon) return;
  const showing = field.type === 'text';
  field.type = showing ? 'password' : 'text';
  buttonIcon.className = showing ? 'bi bi-eye' : 'bi bi-eye-slash';
}

document.addEventListener('DOMContentLoaded', () => {
  const objetivoSelect = document.getElementById('objetivo');
  if (!objetivoSelect) return;
  const presets = {
    'Preservar': { retorno: 25, risco: 40, tributacao: 15, liquidez: 20 },
    'Fazer crescer': { retorno: 40, risco: 25, tributacao: 20, liquidez: 15 },
    'Previdência': { retorno: 35, risco: 20, tributacao: 35, liquidez: 10 },
  };
  objetivoSelect.addEventListener('change', () => {
    const p = presets[objetivoSelect.value];
    if (!p) return;
    ['retorno', 'risco', 'tributacao', 'liquidez'].forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.value = p[id];
    });
  });
});
