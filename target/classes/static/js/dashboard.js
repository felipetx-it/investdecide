document.addEventListener('DOMContentLoaded', () => {
  const canvas = document.getElementById('scenarioChart');
  if (!canvas || typeof Chart === 'undefined') return;

  new Chart(canvas, {
    type: 'line',
    data: {
      labels: ['Nov', 'Dez', 'Jan', 'Fev', 'Mar', 'Abr', 'Mai', 'Jun', 'Jul', 'Ago', 'Set', 'Out'],
      datasets: [
        { label: 'Cenário base', data: [100, 102, 103, 105, 106, 109, 112, 113, 117, 119, 122, 125], borderColor: '#203c66', backgroundColor: 'rgba(32,60,102,.08)', fill: true, tension: .38, borderWidth: 2.5, pointRadius: 0, pointHoverRadius: 4 },
        { label: 'Cenário otimista', data: [100, 103, 105, 108, 110, 114, 118, 121, 125, 129, 134, 139], borderColor: '#35b99a', backgroundColor: 'transparent', fill: false, tension: .38, borderWidth: 2, pointRadius: 0, pointHoverRadius: 4 },
        { label: 'Cenário conservador', data: [100, 101, 102, 103, 104, 105, 107, 108, 110, 111, 113, 115], borderColor: '#eaa361', backgroundColor: 'transparent', fill: false, tension: .38, borderWidth: 2, pointRadius: 0, pointHoverRadius: 4, borderDash: [5, 4] }
      ]
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      interaction: { intersect: false, mode: 'index' },
      plugins: { legend: { display: false }, tooltip: { backgroundColor: '#162338', padding: 12, displayColors: true, titleFont: { family: 'DM Sans', size: 12 }, bodyFont: { family: 'DM Sans', size: 12 }, callbacks: { label: (context) => ` ${context.dataset.label}: índice ${context.parsed.y}` } } },
      scales: {
        x: { grid: { display: false }, border: { display: false }, ticks: { color: '#94a0b1', font: { family: 'DM Sans', size: 11 } } },
        y: { min: 95, max: 145, grid: { color: '#edf0f4', drawTicks: false }, border: { display: false, dash: [4, 5] }, ticks: { stepSize: 10, color: '#94a0b1', padding: 10, font: { family: 'DM Sans', size: 11 } } }
      }
    }
  });
});
