/**
 * Ordenação das alternativas dentro de cada grupo (cluster).
 * A ordem inicial é alfabética; os botões reordenam por retorno, risco, liquidez ou índice.
 */
function ordenarTabela(botao) {
  const bloco = botao.closest('.cluster-block');
  if (!bloco) return;

  const criterio = botao.dataset.criterio;
  const corpo = bloco.querySelector('tbody');
  const linhas = Array.from(corpo.querySelectorAll('tr'));

  const numero = (linha, chave) => parseFloat(linha.dataset[chave]) || 0;
  const texto = (linha, chave) => (linha.dataset[chave] || '').toLocaleLowerCase('pt-BR');

  linhas.sort((a, b) => {
    if (criterio === 'nome') return texto(a, 'nome').localeCompare(texto(b, 'nome'), 'pt-BR');
    if (criterio === 'risco' || criterio === 'liquidez') return numero(a, criterio) - numero(b, criterio);
    return numero(b, criterio) - numero(a, criterio);
  });

  linhas.forEach(linha => corpo.appendChild(linha));
  bloco.querySelectorAll('.sort-btn').forEach(b => b.classList.remove('ativo'));
  botao.classList.add('ativo');
}
