package br.com.realtec.controleestoque.controller;

import br.com.realtec.controleestoque.entity.Produto;
import br.com.realtec.controleestoque.service.ProdutoService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/produtos")
public class ProdutoController {

    private final ProdutoService produtoService;

    public ProdutoController(ProdutoService produtoService) {
        this.produtoService = produtoService;
    }

    @GetMapping
    public String listarProdutos(@RequestParam(required = false) String termo, Model model) {
        model.addAttribute("produtos", produtoService.buscarPorTermo(termo));
        model.addAttribute("termo", termo);
        model.addAttribute("novoProduto", new Produto());
        return "produtos";
    }

    @PostMapping("/salvar")
    public String salvarProduto(@ModelAttribute Produto novoProduto, RedirectAttributes redirectAttributes) {
        try {
            produtoService.cadastrar(novoProduto);
            redirectAttributes.addFlashAttribute("sucesso", "Produto cadastrado com sucesso!");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/produtos";
    }

    @PostMapping("/{codigo}/atualizar")
    public String atualizarProduto(@PathVariable String codigo,
                                   @RequestParam String descricao,
                                   @RequestParam BigDecimal precoUnitario,
                                   RedirectAttributes redirectAttributes) {
        try {
            produtoService.atualizar(codigo, descricao, precoUnitario);
            redirectAttributes.addFlashAttribute("sucesso", "Produto atualizado com sucesso!");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/produtos";
    }

    @PostMapping("/{codigo}/entrada")
    public String registrarEntrada(@PathVariable String codigo,
                                   @RequestParam int quantidade,
                                   RedirectAttributes redirectAttributes) {
        try {
            if (quantidade <= 0) {
                throw new IllegalArgumentException("A quantidade de entrada deve ser maior que zero.");
            }
            produtoService.ajustarEstoque(codigo, quantidade);
            redirectAttributes.addFlashAttribute("sucesso", "Entrada de estoque registrada!");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/produtos";
    }

    @PostMapping("/{codigo}/saida")
    public String registrarSaida(@PathVariable String codigo,
                                 @RequestParam int quantidade,
                                 RedirectAttributes redirectAttributes) {
        try {
            if (quantidade <= 0) {
                throw new IllegalArgumentException("A quantidade de saída deve ser maior que zero.");
            }
            produtoService.ajustarEstoque(codigo, -quantidade);
            redirectAttributes.addFlashAttribute("sucesso", "Saída de estoque registrada!");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/produtos";
    }
}