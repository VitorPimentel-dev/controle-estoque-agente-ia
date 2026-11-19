package br.com.realtec.controleestoque.controller;

import br.com.realtec.controleestoque.entity.Pedido;
import br.com.realtec.controleestoque.service.PedidoService;
import br.com.realtec.controleestoque.service.ProdutoService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/pedidos")
public class PedidoController {

    private final PedidoService pedidoService;
    private final ProdutoService produtoService;

    public PedidoController(PedidoService pedidoService, ProdutoService produtoService) {
        this.pedidoService = pedidoService;
        this.produtoService = produtoService;
    }

    @GetMapping
    public String listarPedidos(Model model) {
        model.addAttribute("pedidos", pedidoService.listarTodos());
        model.addAttribute("produtos", produtoService.listarTodos());
        return "pedidos";
    }

    @PostMapping("/criar")
    public String criarRascunho(@RequestParam(required = false) String identificador, RedirectAttributes redirectAttributes) {
        try {
            Pedido criado = pedidoService.criarRascunho(identificador);
            redirectAttributes.addFlashAttribute("sucesso", "Rascunho de pedido " + criado.getIdentificador() + " criado com sucesso!");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/pedidos";
    }

    @PostMapping("/{identificador}/itens/adicionar")
    public String adicionarItem(@PathVariable String identificador,
                                @RequestParam String codigoProduto,
                                @RequestParam int quantidade,
                                RedirectAttributes redirectAttributes) {
        try {
            pedidoService.adicionarItem(identificador, codigoProduto, quantidade);
            redirectAttributes.addFlashAttribute("sucesso", "Item adicionado ao pedido!");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/pedidos";
    }

    @PostMapping("/{identificador}/itens/{itemId}/remover")
    public String removerItem(@PathVariable String identificador,
                              @PathVariable Long itemId,
                              RedirectAttributes redirectAttributes) {
        try {
            pedidoService.removerItem(identificador, itemId);
            redirectAttributes.addFlashAttribute("sucesso", "Item removido do pedido!");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/pedidos";
    }

    @PostMapping("/{identificador}/confirmar")
    public String confirmarPedido(@PathVariable String identificador, RedirectAttributes redirectAttributes) {
        try {
            pedidoService.confirmarPedido(identificador);
            redirectAttributes.addFlashAttribute("sucesso", "Pedido confirmado e estoque atualizado!");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/pedidos";
    }

    @PostMapping("/{identificador}/cancelar")
    public String cancelarPedido(@PathVariable String identificador, RedirectAttributes redirectAttributes) {
        try {
            pedidoService.cancelarPedido(identificador);
            redirectAttributes.addFlashAttribute("sucesso", "Pedido cancelado!");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/pedidos";
    }
}