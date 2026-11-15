/** Ajusta os campos de remuneração conforme o indexador escolhido. */
document.addEventListener('DOMContentLoaded', () => {
  const indexador = document.getElementById('indexador');
  const percentual = document.getElementById('percentualIndexador');
  const spread = document.getElementById('spreadAnual');
  if (!indexador || !percentual || !spread) return;

  function atualizar() {
    const usaPercentual = indexador.value === 'CDI';
    percentual.disabled = !usaPercentual;
    percentual.closest('.form-field').style.opacity = usaPercentual ? '1' : '.45';
    spread.disabled = usaPercentual;
    spread.closest('.form-field').style.opacity = usaPercentual ? '.45' : '1';
  }

  indexador.addEventListener('change', atualizar);
  atualizar();
});
