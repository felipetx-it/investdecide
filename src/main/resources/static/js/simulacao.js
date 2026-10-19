document.addEventListener('DOMContentLoaded', () => {
  const toggle = document.getElementById('onlyEligible');
  const rows = Array.from(document.querySelectorAll('.comparison-table tbody tr'));

  if (!toggle || rows.length === 0) return;

  toggle.addEventListener('change', () => {
    rows.forEach(row => {
      const eligible = row.dataset.eligible === 'true';
      const hide = toggle.checked && !eligible;
      row.classList.toggle('d-none', hide);
    });
  });
});
