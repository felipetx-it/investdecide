document.addEventListener('DOMContentLoaded', () => {
  const search = document.getElementById('reportSearch');
  const filter = document.getElementById('reportFilter');
  const rows = Array.from(document.querySelectorAll('#reportRows tr'));
  const count = document.getElementById('reportCount');
  const empty = document.getElementById('noReports');

  function applyFilters() {
    const query = (search?.value || '').trim().toLocaleLowerCase('pt-BR');
    const category = filter?.value || 'all';
    let visible = 0;
    rows.forEach(row => {
      const rowCategory = row.dataset.category || '';
      const rowSearch = (row.dataset.search || '').toLocaleLowerCase('pt-BR');
      const show = (category === 'all' || rowCategory === category) && rowSearch.includes(query);
      row.classList.toggle('d-none', !show);
      if (show) visible++;
    });
    if (count) count.textContent = String(visible);
    if (empty) empty.classList.toggle('d-none', visible > 0);
  }
  search?.addEventListener('input', applyFilters);
  filter?.addEventListener('change', applyFilters);
});
