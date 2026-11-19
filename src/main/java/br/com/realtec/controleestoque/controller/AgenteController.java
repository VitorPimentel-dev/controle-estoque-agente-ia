package br.com.realtec.controleestoque.controller;

import br.com.realtec.controleestoque.service.AgenteService;
import br.com.realtec.controleestoque.service.LogExecucaoAgenteService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/agente")
public class AgenteController {

    private final AgenteService agenteService;
    private final LogExecucaoAgenteService logService;

    public AgenteController(AgenteService agenteService, LogExecucaoAgenteService logService) {
        this.agenteService = agenteService;
        this.logService = logService;
    }

    @GetMapping
    public String abrirAgente(Model model) {
        model.addAttribute("logs", logService.listarTodos());
        return "agente";
    }

    @PostMapping("/executar")
    public String executar(@RequestParam String prompt, RedirectAttributes ra) {
        if (prompt == null || prompt.isBlank()) {
            ra.addFlashAttribute("respostaAgente", "Digite um comando.");
            return "redirect:/agente";
        }
        String resposta = agenteService.executarComando(prompt);
        // flash attributes sobrevivem ao redirect e somem no proximo refresh
        ra.addFlashAttribute("ultimoPrompt", prompt);
        ra.addFlashAttribute("respostaAgente", resposta);
        return "redirect:/agente";
    }

    // evita o 405 se alguem abrir a rota por GET
    @GetMapping("/executar")
    public String voltar() {
        return "redirect:/agente";
    }
}