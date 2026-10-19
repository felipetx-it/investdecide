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
