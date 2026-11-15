package br.com.investdecide.web;

import br.com.investdecide.dominio.Indexador;
import br.com.investdecide.dominio.Produto;
import br.com.investdecide.dominio.TipoProduto;
import br.com.investdecide.servico.CatalogoService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Cadastro manual de CDB, LCI e LCA (não há produtos pré-cadastrados). */
@Controller
public class ProdutoController {

    private final CatalogoService catalogo;

    public ProdutoController(CatalogoService catalogo) {
        this.catalogo = catalogo;
    }

    @GetMapping("/produtos")
    public String listar(Model model,
                         @RequestParam(name = "ok", required = false) String ok,
                         @RequestParam(name = "erro", required = false) String erro) {
        model.addAttribute("produto", novoProduto());
        model.addAttribute("manuais", catalogo.manuais());
        model.addAttribute("totalFundos", catalogo.tamanho() - catalogo.manuais().size());
        model.addAttribute("tipos", new TipoProduto[]{TipoProduto.CDB, TipoProduto.LCI, TipoProduto.LCA});
        model.addAttribute("indexadores", Indexador.values());
        model.addAttribute("ok", ok);
        model.addAttribute("erro", erro);
        return "produtos";
    }

    @PostMapping("/produtos")
    public String salvar(@ModelAttribute("produto") Produto produto, RedirectAttributes redirect) {
        try {
            boolean novo = produto.getId() == null || produto.getId().isBlank();
            catalogo.salvarManual(produto);
            redirect.addAttribute("ok", novo ? "Produto cadastrado com sucesso." : "Produto atualizado.");
        } catch (IllegalArgumentException e) {
            redirect.addAttribute("erro", e.getMessage());
        }
        return "redirect:/produtos";
    }

    @PostMapping("/produtos/{id}/excluir")
    public String excluir(@PathVariable String id, RedirectAttributes redirect) {
        try {
            catalogo.excluir(id);
            redirect.addAttribute("ok", "Produto excluído.");
        } catch (IllegalArgumentException e) {
            redirect.addAttribute("erro", e.getMessage());
        }
        return "redirect:/produtos";
    }

    private static Produto novoProduto() {
        Produto produto = new Produto();
        produto.setTipo(TipoProduto.CDB);
        produto.setIndexador(Indexador.CDI);
        produto.setPercentualIndexador(100);
        produto.setVencimentoMeses(24);
        produto.setLiquidezDias(0);
        produto.setRisco(2);
        produto.setMinimo(1000);
        return produto;
    }
}
